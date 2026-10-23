package org.ddport.darkest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the Linux x86_64 Darkest Dungeon in a child process (native/host: box64 + the game) and shows
 * its frames (native/ddview). The game can't run in this process: ART's heap occupies the fixed
 * addresses the game binary needs. Game data is never part of the APK: the app imports it from GOG's
 * Linux installer into getExternalFilesDir()/game (see importGame), and runs it through a private
 * mirror (see mirrorGame).
 *
 * A launch screen picks the resolution and frame rate first (lower ones save battery); the intent
 * extras "height" and "fps" skip it.
 *
 * Touch works like a laptop trackpad: one finger moves the cursor, a tap clicks, a two-finger tap
 * right-clicks, tap then hold drags. Back sends Escape.
 */
public class DDActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "DDActivity";
    /** Heights the game can render at (16:9); the display scales the frames to the screen for free. */
    private static final int[] HEIGHTS = { 1080, 900, 720, 540 };
    /** Frame rates that divide the 60 Hz refresh evenly: each frame stays up for whole refreshes. */
    private static final int[] FPS = { 60, 30, 20, 15 };
    private static final int SCANCODE_ESCAPE = 41;   // SDL_SCANCODE_ESCAPE
    private static final int PICK_INSTALLER = 1;     // request code of the file picker
    private static final String INSTALLER_GAME = "data/noarch/game/";   // the game folder inside the installer
    private static final long GAME_BYTES = 2_500_000_000L;               // it holds 2.35 GB

    static {
        System.loadLibrary("ddview");
    }

    private static native int nativeStart(String socketName, int width, int height, int frameRefreshes);
    private static native void nativeSetSurface(Object surface);
    private static native void nativeMoveCursor(float dx, float dy);
    private static native void nativeButton(int button, boolean down);
    private static native void nativeKey(int scancode, boolean down);

    private Process gameProcess;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int gameW, gameH;   // 0 until the game starts

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = getIntent();
        if (intent.hasExtra("height"))
            start(intent.getIntExtra("height", HEIGHTS[0]), intent.getIntExtra("fps", FPS[0]));
        else
            showLaunchScreen();
        hideSystemBars();   // needs the content view
    }

    private void showLaunchScreen() {
        if (!gameBinary().exists()) {
            showImportScreen(null);
            return;
        }
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        RadioGroup heights = choices(c, h -> h * 16 / 9 + " × " + h, HEIGHTS, prefs.getInt("height", HEIGHTS[0]));
        RadioGroup rates = choices(c, f -> f + " FPS", FPS, prefs.getInt("fps", FPS[0]));
        Button play = new Button(c);
        play.setText("Play");
        play.setOnClickListener(b -> {
            int height = heights.getCheckedRadioButtonId(), fps = rates.getCheckedRadioButtonId();
            prefs.edit().putInt("height", height).putInt("fps", fps).apply();
            start(height, fps);
        });
        play.setMinWidth(400);
        LinearLayout layout = column(c);
        for (View v : new View[] { label(c, "Resolution (lower saves battery)"), heights,
                label(c, "Frame rate (lower saves battery)"), rates, play }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (v == play)
                lp.topMargin = 160;   // well away from the choices, so it isn't hit by accident
            layout.addView(v, lp);
        }
        setContentView(layout);
    }

    /** One radio button per value, the button's id being its value; `selected` (else the first) is checked. */
    private static RadioGroup choices(Context c, IntFunction<String> label, int[] values, int selected) {
        RadioGroup group = new RadioGroup(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        int checked = values[0];
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setText(label.apply(values[i]));
            b.setId(values[i]);
            group.addView(b);
            if (values[i] == selected)
                checked = selected;
        }
        group.check(checked);
        return group;
    }

    private static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setPadding(0, 32, 0, 8);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        return layout;
    }

    /** Shown while the game is missing: GOG's Linux installer is picked here and imported. */
    private void showImportScreen(String error) {
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        Button pick = new Button(c);
        pick.setText("Import game");
        pick.setMinWidth(400);
        pick.setOnClickListener(b -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_INSTALLER));
        LinearLayout layout = column(c);
        layout.addView(label(c, "The game isn't installed yet. Pick GOG's Linux installer of Darkest Dungeon\n"
                + "(darkest_dungeon_…_linux_….sh) from this device's storage."));
        if (error != null)
            layout.addView(label(c, error));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        layout.addView(pick, lp);
        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_INSTALLER && resultCode == RESULT_OK && data != null && data.getData() != null)
            importGame(data.getData());
    }

    /** Unpacks the game from the picked installer on a background thread, showing the progress. */
    private void importGame(Uri installer) {
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout layout = column(c);
        layout.addView(label(c, "Importing the game (stay in the app)…"));
        layout.addView(bar, new LinearLayout.LayoutParams(800, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
        new Thread(() -> {
            try {
                unpackGame(installer, percent -> handler.post(() -> bar.setProgress(percent)));
                handler.post(this::showLaunchScreen);
            } catch (Exception e) {
                Log.e(TAG, "import failed", e);
                handler.post(() -> showImportScreen("Import failed: " + e.getMessage()));
            }
        }).start();
    }

    private void start(int height, int fps) {
        gameH = height;
        gameW = height * 16 / 9;
        SurfaceView view = new SurfaceView(this);
        view.getHolder().setFixedSize(gameW, gameH);
        view.getHolder().addCallback(this);
        view.setOnTouchListener((v, e) -> onTouch(e));
        setContentView(view);

        String socket = "ddport." + android.os.Process.myPid();
        int refreshes = Math.max(1, Math.round(getDisplay().getRefreshRate() / fps));
        if (nativeStart(socket, gameW, gameH, refreshes) != 0)
            Log.e(TAG, "cannot start the frame receiver");
        else
            startGame(socket);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            hideSystemBars();
    }

    private void startGame(String socket) {
        File home = getFilesDir();
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        try {
            extractAssets("x64lib", new File(home, "x64lib"));
            if (!gameBinary().exists()) {
                Log.e(TAG, "game data missing: import it from the launch screen");
                return;
            }
            File mirror = mirrorGame(gameDir(), new File(home, "game"));
            ProcessBuilder pb = new ProcessBuilder(nativeDir + "/libddhost.so");
            pb.environment().put("DD_GAME_DIR", mirror.getAbsolutePath());
            pb.environment().put("DD_HOME", home.getAbsolutePath());
            pb.environment().put("DD_NATIVE_DIR", nativeDir);
            pb.environment().put("DD_SOCKET", socket);
            pb.environment().put("LD_LIBRARY_PATH", nativeDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(home, "dd.log"));
            gameProcess = pb.start();
            Log.i(TAG, "game process started");
            new Thread(() -> {
                try {
                    int code = gameProcess.waitFor();
                    Log.i(TAG, "game process exited with " + code);
                    handler.post(this::finish);
                } catch (InterruptedException ignored) {
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "cannot start the game", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (gameProcess != null)
            gameProcess.destroy();
        super.onDestroy();
    }

    // ---- surface ----
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSetSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSetSurface(null);
    }

    // ---- input ----
    private static final long TAP_MS = 200;       // longer than this is not a tap
    private static final float TAP_SLOP = 12;     // moving more than this (view pixels) is not a tap
    private static final float SPEED = 1.0f;      // cursor speed: game pixels per view pixel, scaled below
    private float lastX, lastY, travel;
    private long downTime, lastTapUp;
    private int maxFingers;
    private boolean dragging;

    private boolean onTouch(MotionEvent e) {
        View v = getWindow().getDecorView();
        float scale = SPEED * gameW / Math.max(1, v.getWidth());
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            lastX = e.getX();
            lastY = e.getY();
            travel = 0;
            maxFingers = 1;
            downTime = e.getEventTime();
            // tap then touch again quickly: hold the button down and drag
            if (downTime - lastTapUp < TAP_MS) {
                dragging = true;
                nativeButton(1, true);
            }
            break;
        case MotionEvent.ACTION_POINTER_DOWN:
            maxFingers = Math.max(maxFingers, e.getPointerCount());
            break;
        case MotionEvent.ACTION_MOVE:
            if (e.getPointerCount() == 1) {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                travel += Math.abs(dx) + Math.abs(dy);
                nativeMoveCursor(dx * scale, dy * scale);
            }
            lastX = e.getX();
            lastY = e.getY();
            break;
        case MotionEvent.ACTION_UP:
            boolean tap = e.getEventTime() - downTime < TAP_MS && travel < TAP_SLOP;
            if (dragging) {
                dragging = false;
                nativeButton(1, false);
            } else if (tap) {
                int button = maxFingers >= 2 ? 3 : 1;   // two-finger tap = right click
                nativeButton(button, true);
                nativeButton(button, false);           // the game side keeps it pressed long enough
                lastTapUp = button == 1 ? e.getEventTime() : 0;
            }
            break;
        case MotionEvent.ACTION_POINTER_UP:
            // a finger lifted: continue moving from wherever the remaining one is
            int keep = e.getActionIndex() == 0 ? 1 : 0;
            lastX = e.getX(keep);
            lastY = e.getY(keep);
            break;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && gameW > 0) {
            if (event.getRepeatCount() == 0)   // a held Back must not toggle the menu over and over
                nativeKey(SCANCODE_ESCAPE, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && gameW > 0) {
            nativeKey(SCANCODE_ESCAPE, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---- files ----
    private File gameDir() {
        return new File(getExternalFilesDir(null), "game");
    }

    private File gameBinary() {
        return new File(gameDir(), "_linuxnosteam/darkest.bin.x86_64");
    }

    /**
     * Unpacks data/noarch/game/ of GOG's Linux installer (a shell script followed by a zip) into
     * game.import/, patches its shaders and renames it to game/, so a half-finished import is never
     * taken for the game. The zip is streamed from where it starts in the file, never copied.
     */
    private void unpackGame(Uri installer, IntConsumer progress) throws Exception {
        File game = gameDir(), tmp = new File(game.getParentFile(), "game.import");
        deleteTree(tmp);   // left by an import that didn't finish
        if (game.getParentFile().getUsableSpace() < GAME_BYTES)
            throw new IOException("not enough free space, the game needs 2.5 GB");
        String root = tmp.getCanonicalPath() + "/";
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(installer, "r");
             FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {
            long start = zipStart(ch), size = ch.size();
            ch.position(start);
            ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16));
            int shown = -1;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().startsWith(INSTALLER_GAME) || e.getName().equals(INSTALLER_GAME))
                    continue;
                File out = new File(tmp, e.getName().substring(INSTALLER_GAME.length()));
                if (!out.getCanonicalPath().startsWith(root))
                    throw new IOException("bad file name in the installer: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    copy(zip, o);
                }
                if (e.getTime() >= 0)   // mirrorGame copies the binary again only when its time changes
                    out.setLastModified(e.getTime());
                int percent = (int) (100 * (ch.position() - start) / (size - start));
                if (percent != shown)
                    progress.accept(shown = percent);
            }
        }
        if (!new File(tmp, "_linuxnosteam/darkest.bin.x86_64").exists())
            throw new IOException("this file doesn't hold the Linux Darkest Dungeon");
        patchShaders(tmp);
        deleteTree(game);   // only an incomplete copy can be here; saves live in getFilesDir()
        if (!tmp.renameTo(game))
            throw new IOException("cannot rename " + tmp + " to " + game);
    }

    /**
     * Where the zip starts in a file that has something in front of it. The zip's end record counts
     * the central directory's offset from the zip's own start, and the directory ends at the record.
     */
    private static long zipStart(FileChannel ch) throws IOException {
        long size = ch.size();
        int len = (int) Math.min(size, 22 + 65535);   // the end record plus its longest comment
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining() && ch.read(b, size - len + b.position()) > 0)
            ;
        for (int i = len - 22; i >= 0; i--)
            if (b.getInt(i) == 0x06054b50) {
                long start = size - len + i - (b.getInt(i + 12) & 0xffffffffL) - (b.getInt(i + 16) & 0xffffffffL);
                ByteBuffer sig = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                if (start >= 0 && ch.read(sig, start) == 4 && sig.getInt(0) == 0x04034b50)
                    return start;
                break;
            }
        throw new IOException("not GOG's installer (no zip inside)");
    }

    /** Runs assets/patch-shaders.sh on the game folder: the game's GLSL 1.20 shaders as GLSL ES. */
    private void patchShaders(File game) throws Exception {
        File script = new File(getFilesDir(), "patch-shaders.sh");
        try (InputStream in = getAssets().open("patch-shaders.sh")) {
            Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Process p = new ProcessBuilder("/system/bin/sh", script.getPath(), game.getPath())
                .redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(p.getInputStream(), out);
        if (p.waitFor() != 0)
            throw new IOException("shader patch failed: " + out.toString().trim());
        Log.i(TAG, out.toString().trim());
    }

    /** Deletes dir and everything in it (symlinks themselves, never what they point to). */
    private static void deleteTree(File dir) throws IOException {
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
            return;
        try (Stream<Path> paths = Files.walk(dir.toPath())) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.delete(p);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; )
            out.write(buf, 0, n);
    }

    /**
     * External storage is not executable, and the game finds its data next to its binary
     * (_linuxnosteam/..). So the game runs from a private mirror of the game folder: a real
     * _linuxnosteam/ holding copies of the binary and lib64/, and symlinks to everything else.
     */
    private static File mirrorGame(File game, File mirror) throws Exception {
        link(game, mirror, "_linuxnosteam");
        File linux = new File(game, "_linuxnosteam");
        File mirrorLinux = new File(mirror, "_linuxnosteam");
        copyIfChanged(new File(linux, "darkest.bin.x86_64"), new File(mirrorLinux, "darkest.bin.x86_64"));
        File[] libs = new File(linux, "lib64").listFiles();
        if (libs != null)
            for (File lib : libs)
                copyIfChanged(lib, new File(mirrorLinux, "lib64/" + lib.getName()));
        link(linux, mirrorLinux, "darkest.bin.x86_64", "lib64");
        return mirror;
    }

    /** Symlinks every entry of dir into mirror, except the names given and names already there. */
    private static void link(File dir, File mirror, String... except) throws Exception {
        mirror.mkdirs();
        String[] names = dir.list();
        if (names == null)
            return;
        outer:
        for (String name : names) {
            for (String e : except)
                if (e.equals(name))
                    continue outer;
            // already there: our symlink, or a file the game wrote itself (it replaces some, like app.log)
            File l = new File(mirror, name);
            if (!Files.exists(l.toPath(), LinkOption.NOFOLLOW_LINKS))
                Os.symlink(new File(dir, name).getAbsolutePath(), l.getAbsolutePath());
        }
    }

    /** Copies src to dest (made executable) unless dest already has the same size and time. */
    private static void copyIfChanged(File src, File dest) throws Exception {
        if (!src.isFile() || (dest.length() == src.length() && dest.lastModified() == src.lastModified()))
            return;
        dest.getParentFile().mkdirs();
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        dest.setLastModified(src.lastModified());
        dest.setExecutable(true);
    }

    /** Copies assets/<dir>/* to dest once per installed APK version (tracked in dest/.stamp). */
    private void extractAssets(String dir, File dest) throws Exception {
        String stamp = Long.toString(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        File stampFile = new File(dest, ".stamp");
        if (stampFile.exists() && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()))))
            return;
        dest.mkdirs();
        for (String name : getAssets().list(dir)) {
            try (InputStream in = getAssets().open(dir + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dest, name))) {
                copy(in, out);
            }
        }
        Files.write(stampFile.toPath(), stamp.getBytes());
    }
}
