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
import android.text.InputType;
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
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
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
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the Linux x86_64 Darkest Dungeon in a child process (native/host: box64 + the game) and shows
 * its frames (native/ddview). The game can't run in this process: ART's heap occupies the fixed
 * addresses the game binary needs. Game data is never part of the APK: the app imports it from GOG's
 * Linux installer, or from the Steam zip that port/steam/get-steam-game.bat makes, into
 * getExternalFilesDir()/game (see importGame), and runs it through a private mirror (see mirrorGame).
 *
 * A launch screen picks the resolution and frame rate first (lower ones save battery); the intent
 * extras "height" and "fps" skip it.
 *
 * Touch works like a laptop trackpad: one finger moves the cursor, a tap clicks, a two-finger tap
 * right-clicks, tap then hold drags. Back sends Escape. While the game wants text, the keyboard is up
 * and types into it; hardware keyboards always reach the game.
 */
public class DDActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "DDActivity";
    /** Heights the game can render at (16:9); the display scales the frames to the screen for free. */
    private static final int[] HEIGHTS = { 2160, 1440, 1080, 900, 720, 540 };
    private static final int DEFAULT_HEIGHT = 1080;
    /** Frame rates that divide the 60 Hz refresh evenly: each frame stays up for whole refreshes. */
    private static final int[] FPS = { 60, 30, 20, 15 };
    private static final int SCANCODE_ESCAPE = 41;   // SDL_SCANCODE_ESCAPE
    private static final int PICK_INSTALLER = 1;     // request code of the file picker
    private static final String INSTALLER_GAME = "data/noarch/game/";   // the game folder inside the installer
    private static final long GAME_BYTES = 2_500_000_000L;               // it holds 2.35 GB (more with DLCs)

    static {
        System.loadLibrary("ddview");
    }

    private static native int nativeStart(DDActivity activity, String socketName, int width, int height,
                                          int frameRefreshes);
    private static native void nativeSetSurface(Object surface);
    private static native void nativeMoveCursor(float dx, float dy);
    private static native void nativeButton(int button, boolean down);
    private static native void nativeKey(int scancode, boolean down);
    private static native void nativeText(int codePoint);

    private Process gameProcess;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int gameW, gameH;   // 0 until the game starts
    private GameView view;
    private boolean textInput;  // the game wants text: the keyboard is up
    private float lastClickY, lowestClickY;   // when the game asked for text, 0 (top) to 1 (see panAboveKeyboard)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = getIntent();
        if (intent.hasExtra("height"))
            start(intent.getIntExtra("height", DEFAULT_HEIGHT), intent.getIntExtra("fps", FPS[0]));
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
        Spinner heights = dropdown(c, h -> h * 16 / 9 + " × " + h + (h == 2160 ? " (4K)" : ""), HEIGHTS,
                prefs.getInt("height", DEFAULT_HEIGHT));
        Spinner rates = dropdown(c, f -> f + " FPS", FPS, prefs.getInt("fps", FPS[0]));
        Button play = new Button(c);
        play.setText("Play");
        play.setOnClickListener(b -> {
            int height = HEIGHTS[heights.getSelectedItemPosition()], fps = FPS[rates.getSelectedItemPosition()];
            prefs.edit().putInt("height", height).putInt("fps", fps).apply();
            start(height, fps);
        });
        play.setMinWidth(400);
        LinearLayout choices = new LinearLayout(c);   // the two dropdowns side by side
        choices.addView(labelled(c, "Resolution (lower saves battery)", heights));
        choices.addView(labelled(c, "Frame rate (lower saves battery)", rates));
        LinearLayout layout = column(c);
        layout.addView(choices, wrap());
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = 160;   // well away from the choices, so it isn't hit by accident
        layout.addView(play, lp);
        setContentView(layout);
    }

    /** A dropdown of the values' labels; `selected` (else the first) is shown. */
    private static Spinner dropdown(Context c, IntFunction<String> label, int[] values, int selected) {
        String[] labels = new String[values.length];
        int shown = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = label.apply(values[i]);
            if (values[i] == selected)
                shown = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(c);
        spinner.setAdapter(adapter);
        spinner.setSelection(shown);
        return spinner;
    }

    /** `view` under a label, with room on both sides for a neighbour. */
    private static LinearLayout labelled(Context c, String text, View view) {
        LinearLayout column = column(c);
        column.setPadding(48, 0, 48, 0);
        column.addView(label(c, text), wrap());
        column.addView(view, wrap());
        return column;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
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

    /** Shown while the game is missing: GOG's Linux installer or the Steam zip is picked here and imported. */
    private void showImportScreen(String error) {
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        Button pick = new Button(c);
        pick.setText("Import game");
        pick.setMinWidth(400);
        pick.setOnClickListener(b -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_INSTALLER));
        LinearLayout layout = column(c);
        layout.addView(label(c, "The game isn't installed yet. Pick GOG's Linux installer of Darkest Dungeon\n"
                + "(darkest_dungeon_…_linux_….sh), or darkest-dungeon-steam.zip made by get-steam-game.bat\n"
                + "(Steam), from this device's storage."));
        if (error != null)
            layout.addView(label(c, error));
        LinearLayout.LayoutParams lp = wrap();
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
        view = new GameView(this);
        view.getHolder().setFixedSize(gameW, gameH);
        view.getHolder().addCallback(this);
        view.setOnTouchListener((v, e) -> onTouch(e));
        setContentView(view);
        // the keyboard (and the bars that may show with it) cover the game rather than resizing it;
        // panAboveKeyboard moves it instead
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        getWindow().setDecorFitsSystemWindows(false);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            panAboveKeyboard(insets.getInsets(WindowInsets.Type.ime()).bottom);
            return insets;
        });

        String socket = "ddport." + android.os.Process.myPid();
        int refreshes = Math.max(1, Math.round(getDisplay().getRefreshRate() / fps));
        if (nativeStart(this, socket, gameW, gameH, refreshes) != 0)
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
                if (textInput)
                    showKeyboard(true);                // back after Back hid it
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

    // Keys from hardware keyboards and from the on-screen keyboard. Held keys repeat: the game
    // repeats Backspace, Delete and the arrows itself and ignores other repeats.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (gameW == 0)
            return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0)   // a held Back must not toggle the menu over and over
                nativeKey(SCANCODE_ESCAPE, true);
            return true;
        }
        if (typesText(event)) {
            nativeText(event.getUnicodeChar());
            return true;
        }
        int scancode = scancode(keyCode);
        if (scancode == 0)
            return super.onKeyDown(keyCode, event);
        nativeKey(scancode, true);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (gameW == 0)
            return super.onKeyUp(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            nativeKey(SCANCODE_ESCAPE, false);
            return true;
        }
        if (typesText(event))
            return true;                       // typed when it went down
        int scancode = scancode(keyCode);
        if (scancode == 0)
            return super.onKeyUp(keyCode, event);
        nativeKey(scancode, false);
        return true;
    }

    /** While the game wants text, a key that types a character sends that character, not the key. */
    private boolean typesText(KeyEvent event) {
        int c = event.getUnicodeChar();   // negative for a dead key (an accent waiting for its letter)
        return textInput && c >= 0x20 && c != 0x7f;
    }

    /** The SDL scancode (SDL_scancode.h: the USB keyboard usage IDs) of an Android key, or 0. */
    private static int scancode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            return 4 + keyCode - KeyEvent.KEYCODE_A;
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9)
            return 30 + keyCode - KeyEvent.KEYCODE_1;
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12)
            return 58 + keyCode - KeyEvent.KEYCODE_F1;
        switch (keyCode) {
        case KeyEvent.KEYCODE_0: return 39;
        case KeyEvent.KEYCODE_ENTER: return 40;
        case KeyEvent.KEYCODE_ESCAPE: return SCANCODE_ESCAPE;
        case KeyEvent.KEYCODE_DEL: return 42;            // Backspace
        case KeyEvent.KEYCODE_TAB: return 43;
        case KeyEvent.KEYCODE_SPACE: return 44;
        case KeyEvent.KEYCODE_MINUS: return 45;
        case KeyEvent.KEYCODE_EQUALS: return 46;
        case KeyEvent.KEYCODE_LEFT_BRACKET: return 47;
        case KeyEvent.KEYCODE_RIGHT_BRACKET: return 48;
        case KeyEvent.KEYCODE_BACKSLASH: return 49;
        case KeyEvent.KEYCODE_SEMICOLON: return 51;
        case KeyEvent.KEYCODE_APOSTROPHE: return 52;
        case KeyEvent.KEYCODE_GRAVE: return 53;
        case KeyEvent.KEYCODE_COMMA: return 54;
        case KeyEvent.KEYCODE_PERIOD: return 55;
        case KeyEvent.KEYCODE_SLASH: return 56;
        case KeyEvent.KEYCODE_INSERT: return 73;
        case KeyEvent.KEYCODE_MOVE_HOME: return 74;
        case KeyEvent.KEYCODE_PAGE_UP: return 75;
        case KeyEvent.KEYCODE_FORWARD_DEL: return 76;    // Delete
        case KeyEvent.KEYCODE_MOVE_END: return 77;
        case KeyEvent.KEYCODE_PAGE_DOWN: return 78;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return 79;
        case KeyEvent.KEYCODE_DPAD_LEFT: return 80;
        case KeyEvent.KEYCODE_DPAD_DOWN: return 81;
        case KeyEvent.KEYCODE_DPAD_UP: return 82;
        case KeyEvent.KEYCODE_NUMPAD_ENTER: return 88;
        case KeyEvent.KEYCODE_CTRL_LEFT: return 224;
        case KeyEvent.KEYCODE_SHIFT_LEFT: return 225;
        case KeyEvent.KEYCODE_ALT_LEFT: return 226;
        case KeyEvent.KEYCODE_CTRL_RIGHT: return 228;
        case KeyEvent.KEYCODE_SHIFT_RIGHT: return 229;
        case KeyEvent.KEYCODE_ALT_RIGHT: return 230;
        default: return 0;
        }
    }

    private static final float FIELD_MARGIN = 0.07f;   // of the screen height: kept clear around a click

    private static void tapKey(int keyCode) {
        nativeKey(scancode(keyCode), true);
        nativeKey(scancode(keyCode), false);   // the game side keeps it pressed long enough
    }

    /** Called by ddview (on its render thread) when the game starts or stops wanting text. */
    private void onTextInput(boolean on, float lastClick, float lowestClick) {
        handler.post(() -> {
            textInput = on;
            lastClickY = lastClick;
            lowestClickY = lowestClick;
            if (on)   // a new field: a fresh connection, nothing left over from the last one
                getSystemService(InputMethodManager.class).restartInput(view);
            showKeyboard(on);
        });
    }

    private void showKeyboard(boolean show) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (!show)
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        else if (view.requestFocus())   // the keyboard only serves the focused view
            imm.showSoftInput(view, 0);
    }

    /**
     * Slides the game up so the field being typed into stays above the keyboard (`keyboard` = its
     * height, 0 when hidden). The game doesn't say where its fields are, but the player clicked the
     * field just before: as the last click (a hero's name), or a little earlier and lower down (a save
     * slot, then the dialog that follows). So the lowest recent click goes just above the keyboard, as
     * long as the last click stays on screen.
     */
    private void panAboveKeyboard(int keyboard) {
        int h = view.getHeight();
        float lowestAbove = (lowestClickY + FIELD_MARGIN) * h - (h - keyboard);   // pan that shows the lowest
        float lastAtTop = (lastClickY - FIELD_MARGIN) * h;                       // most pan that shows the last
        float pan = Math.max(0, Math.min(lowestAbove, lastAtTop));
        view.setTranslationY(textInput && keyboard > 0 ? -pan : 0);
    }

    /** The game's view, and the text field the on-screen keyboard types into. */
    private static class GameView extends SurfaceView {
        GameView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);   // a key press would outline the whole game
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo info) {
            // no autocorrect or suggestions, and no full-screen text box in landscape
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_ACTION_DONE;
            return new GameInputConnection(this);
        }
    }

    /**
     * Sends what the on-screen keyboard types to the game. It keeps no text itself, so the keyboard
     * sees an empty field and sends Backspace as a key. A word being composed (keyboards with
     * suggestions) goes to the game as it changes: Backspaces for what changed, then the new characters.
     */
    private static class GameInputConnection extends BaseInputConnection {
        private String composing = "";

        GameInputConnection(View view) {
            super(view, false);
        }

        /** Turns the game's copy of the composed text into `text`. */
        private void typeOver(CharSequence text) {
            String t = text.toString();
            int same = 0;
            while (same < composing.length() && same < t.length() && composing.charAt(same) == t.charAt(same))
                same++;
            if (same > 0 && Character.isHighSurrogate(composing.charAt(same - 1)))
                same--;   // keep both halves of a character together
            for (int n = composing.codePointCount(same, composing.length()); n > 0; n--)
                tapKey(KeyEvent.KEYCODE_DEL);
            t.substring(same).codePoints().forEach(c -> {
                if (c == '\n')
                    tapKey(KeyEvent.KEYCODE_ENTER);
                else
                    nativeText(c);
            });
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            typeOver(text);
            composing = "";
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            typeOver(text);
            composing = text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";   // stays typed
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++)
                tapKey(KeyEvent.KEYCODE_DEL);
            for (int i = 0; i < afterLength; i++)
                tapKey(KeyEvent.KEYCODE_FORWARD_DEL);
            return true;
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            tapKey(KeyEvent.KEYCODE_ENTER);   // Done
            return true;
        }
    }

    // ---- files ----
    private File gameDir() {
        return new File(getExternalFilesDir(null), "game");
    }

    private File gameBinary() {
        return new File(gameDir(), "_linuxnosteam/darkest.bin.x86_64");
    }

    /**
     * Unpacks the game into game.import/, patches its shaders and renames it to game/, so a
     * half-finished import is never taken for the game. GOG's Linux installer is a shell script
     * followed by a zip that holds the game in data/noarch/game/; the Steam zip holds it at its root.
     * The zip is streamed from where it starts in the file, never copied.
     */
    private void unpackGame(Uri installer, IntConsumer progress) throws Exception {
        File game = gameDir(), tmp = new File(game.getParentFile(), "game.import");
        deleteTree(tmp);   // left by an import that didn't finish
        String root = tmp.getCanonicalPath() + "/";
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(installer, "r");
             FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {
            long start = zipStart(ch), size = ch.size();
            // the Steam zip is uncompressed, so with DLCs it can hold more than GAME_BYTES
            long need = Math.max(GAME_BYTES, size - start);
            if (game.getParentFile().getUsableSpace() < need)
                throw new IOException(String.format(Locale.US, "not enough free space, the game needs %.1f GB", need / 1e9));
            String prefix = start > 0 ? INSTALLER_GAME : "";   // something in front of the zip: GOG's installer
            ch.position(start);
            ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16));
            int shown = -1;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().startsWith(prefix) || e.getName().equals(prefix))
                    continue;
                File out = new File(tmp, e.getName().substring(prefix.length()));
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
     * Where the zip starts in the file: 0 for a plain zip, or after what is in front of it. The zip's
     * end record counts the central directory's offset from the zip's own start, and the directory
     * ends at the record. (A plain zip is checked first: past 4 GB it is zip64, whose end record holds
     * 0xffffffff instead of the offset.)
     */
    private static long zipStart(FileChannel ch) throws IOException {
        ByteBuffer first = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        if (ch.read(first, 0) == 4 && first.getInt(0) == 0x04034b50)
            return 0;
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
        throw new IOException("not GOG's installer or the Steam zip (no zip inside)");
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
