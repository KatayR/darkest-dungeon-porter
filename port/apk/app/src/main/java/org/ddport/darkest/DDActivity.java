package org.ddport.darkest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.text.InputType;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Runs the Linux x86_64 Darkest Dungeon in a child process (native/host: box64 + the game) and shows
 * its frames (native/ddview). The game can't run in this process: ART's heap occupies the fixed
 * addresses the game binary needs. Game data is never part of the APK: the app imports it from GOG's
 * Linux installer (and its DLC installers), or from the Steam zip that port/steam/get-steam-game.bat
 * makes, into getExternalFilesDir()/game (see importGame), and runs it through a private mirror (see
 * mirrorGame). The game's saves are in getFilesDir(); the launch screen exports them to a zip and
 * imports them from one (see unpackSaves), so they can outlive an uninstall and move between devices.
 *
 * A launch screen picks the resolution and frame rate first (lower ones save battery); the intent
 * extras "height" and "fps" skip it.
 *
 * Touch works like a laptop trackpad: one finger moves the cursor, a tap clicks, a two-finger tap
 * right-clicks, tap then hold drags. Back sends Escape. While the game wants text, the keyboard is up
 * and types into it; hardware keyboards always reach the game. While the trinket inventory is open, a
 * Shift button holds Shift, which makes a click sell the trinket.
 */
public class DDActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "DDActivity";
    /** Heights the game can render at (16:9); the display scales the frames to the screen for free. */
    private static final int[] HEIGHTS = { 2160, 1440, 1080, 900, 720, 540 };
    private static final int DEFAULT_HEIGHT = 1080;
    /** Frame rates that divide the 60 Hz refresh evenly: each frame stays up for whole refreshes. */
    private static final int[] FPS = { 60, 30, 20, 15 };
    private static final int SCANCODE_ESCAPE = 41;   // SDL_SCANCODE_ESCAPE
    private static final int PICK_INSTALLER = 1;     // request codes of the file pickers
    private static final int IMPORT_SAVES = 2, EXPORT_SAVES = 3;
    private static final String SAVE_OPTIONS = "persist.options.json";   // the game's options, in the save folder
    private static final long SAVES_MAX_BYTES = 100_000_000;   // saves are a few MB; a bigger file isn't read
    private static final String NOT_SAVES =
            "That file isn't a zip of Darkest Dungeon saves: it has no profile_0, profile_1, … folder.";
    private static final String INSTALLER_GAME = "data/noarch/game/";   // the game folder inside the installer
    private static final String GAME_BINARY = "_linuxnosteam/darkest.bin.x86_64";   // in the game folder
    private static final String NOT_A_GAME_FILE =
            "That file isn't a Darkest Dungeon or DLC installer from GOG, or the Steam zip.";
    private static final String DAMAGED = "The file looks damaged or incomplete. Try copying it to the phone again.";
    // colours of the launch and import screens
    private static final int BACKGROUND = 0xFF121214, CARD = 0xFF1D1D21, TEXT = 0xFFF2F2F2, MUTED = 0xFFA4A4AC,
            BUTTON = 0xFF2E2E35, ACCENT = 0xFFD9A441, ON_ACCENT = 0xFF1C1607;

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
    private static native void nativePad(boolean connected);
    private static native void nativePadButton(int button, boolean down);
    private static native void nativePadAxis(int axis, int value);

    private Process gameProcess;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int gameW, gameH;   // 0 until the game starts
    private GameView view;
    private boolean textInput;  // the game wants text: the keyboard is up
    private float lastClickY, lowestClickY;   // when the game asked for text, 0 (top) to 1 (see panAboveKeyboard)
    private Button shiftButton; // over the game while the trinket inventory is open (see onShiftScreen)
    private boolean shiftHeld;  // the Shift button holds Shift down in the game

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            recoverSaves();
        } catch (IOException e) {
            Log.e(TAG, "cannot recover the saves of an unfinished import", e);
        }
        Intent intent = getIntent();
        if (intent.hasExtra("height"))
            start(intent.getIntExtra("height", DEFAULT_HEIGHT), intent.getIntExtra("fps", FPS[0]));
        else
            showLaunchScreen(null, null);
        hideSystemBars();   // needs the content view
    }

    /**
     * Three cards: the resolution and frame rate on the left, with the save file's import and export
     * under them; importing the game or its DLCs, and what is imported so far, on the right. Play is
     * below them, in the middle, once the game is imported. A message from the last import or export,
     * if any, opens in a dialog with the title given.
     */
    private void showLaunchScreen(String dialogTitle, String dialogMessage) {
        boolean installed = gameBinary().exists();
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        Spinner heights = dropdown(c, h -> h * 16 / 9 + " × " + h + (h == 2160 ? " (4K)" : ""), HEIGHTS,
                prefs.getInt("height", DEFAULT_HEIGHT));
        Spinner rates = dropdown(c, f -> f + " FPS", FPS, prefs.getInt("fps", FPS[0]));
        LinearLayout settings = card(c);
        settings.setBackground(rounded(c));
        settings.addView(heading(c, "Settings"));
        settings.addView(text(c, "Resolution (lower saves battery)", 14, MUTED), below(c, 12));
        settings.addView(heights, below(c, 4));
        settings.addView(text(c, "Frame rate (lower saves battery)", 14, MUTED), below(c, 16));
        settings.addView(rates, below(c, 4));

        // the save file card under the settings: Import and Export side by side
        LinearLayout saves = card(c);
        saves.setBackground(rounded(c));
        saves.addView(heading(c, "Save file"));
        LinearLayout saveButtons = new LinearLayout(c);
        Button importSaves = button(c, "Import", BUTTON, TEXT);
        importSaves.setOnClickListener(b -> askImportSaves());
        Button exportSaves = button(c, "Export", BUTTON, TEXT);
        exportSaves.setOnClickListener(b -> pickExportFile());
        boolean hasSaves = hasSaves();
        exportSaves.setEnabled(hasSaves);
        if (!hasSaves)
            exportSaves.setTextColor(MUTED);
        for (Button b : new Button[] { importSaves, exportSaves }) {
            b.setMinWidth(0);   // two share the narrow card
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (b == importSaves)
                half.rightMargin = dp(c, 8);
            saveButtons.addView(b, half);
        }
        saves.addView(saveButtons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout leftCards = new LinearLayout(c);
        leftCards.setOrientation(LinearLayout.VERTICAL);
        leftCards.addView(settings, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams underSettings = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        underSettings.topMargin = dp(c, 12);
        leftCards.addView(saves, underSettings);
        ScrollView leftScroll = new ScrollView(c);   // both cards can be taller than a short screen
        leftScroll.setFillViewport(true);
        leftScroll.addView(leftCards);

        // what is imported (once the game is), then the hint and the import button, at the right
        LinearLayout files = card(c);
        files.addView(heading(c, "Game files"));
        if (installed) {
            TextView title = text(c, "Imported", 12, MUTED);
            title.setAllCaps(true);
            title.setLetterSpacing(0.1f);
            files.addView(title, below(c, 8));
            List<String> imported = imported();
            for (int i = 0; i < imported.size(); i++) {
                TextView item = text(c, "✓  " + imported.get(i), 16, TEXT);
                if (i > 0)   // the DLCs, after the game
                    item.setTypeface(null, Typeface.ITALIC);
                files.addView(item, below(c, 4));
            }
            files.addView(text(c, "Pick one or more of GOG's DLC installers.", 14, MUTED), below(c, 16));
        } else {
            files.addView(text(c, "Pick one of these:", 14, MUTED), below(c, 8));
            files.addView(source(c, "GOG's Linux installer", "darkest_dungeon_…_linux_….sh"), below(c, 4));
            files.addView(source(c, "The Steam zip", "darkest-dungeon-steam.zip"), below(c, 4));
            files.addView(text(c, "For GOG, DLC installers can be picked together with the game. "
                    + "The Steam zip already has your DLCs.", 14, MUTED), below(c, 8));
        }
        Button pick = button(c, installed ? "Add DLC" : "Import game", BUTTON, TEXT);
        pick.setOnClickListener(b -> pickFiles());
        LinearLayout.LayoutParams atRight = below(c, 12);
        atRight.gravity = Gravity.END;
        files.addView(pick, atRight);
        ScrollView filesScroll = new ScrollView(c);   // the hint and many DLCs can be taller than the card
        filesScroll.setBackground(rounded(c));
        filesScroll.setClipToOutline(true);
        filesScroll.addView(files);

        LinearLayout cards = new LinearLayout(c);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2);
        left.rightMargin = dp(c, 12);
        cards.addView(leftScroll, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3);
        right.leftMargin = dp(c, 12);
        cards.addView(filesScroll, right);

        Button play = button(c, "Play", ACCENT, ON_ACCENT);
        play.setTextSize(18);
        play.setMinWidth(dp(c, 240));
        play.setOnClickListener(b -> {
            int height = HEIGHTS[heights.getSelectedItemPosition()], fps = FPS[rates.getSelectedItemPosition()];
            prefs.edit().putInt("height", height).putInt("fps", fps).apply();
            start(height, fps);
        });
        play.setEnabled(installed);
        if (!installed) {
            play.setBackgroundTintList(ColorStateList.valueOf(BUTTON));
            play.setTextColor(MUTED);
        }
        LinearLayout bottom = column(c);
        bottom.addView(play, wrap());

        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BACKGROUND);
        layout.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 12));
        layout.addView(cards, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        layout.addView(bottom, lp);
        setContentView(layout);
        if (dialogMessage != null)   // what the last import has to say
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(dialogTitle)
                    .setMessage(dialogMessage)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
    }

    /** What is imported: the game (GOG's or Steam's) and its DLCs; empty while the game is missing. */
    private List<String> imported() {
        List<String> names = new ArrayList<>();
        if (!gameBinary().exists())
            return names;
        boolean gog = new File(gameDir(), "goggame-1450711444.info").exists();
        names.add(gog ? "Darkest Dungeon (GOG)" : "Darkest Dungeon (Steam)");
        String[] dlcs = new File(gameDir(), "dlc").list();
        if (dlcs != null) {
            Arrays.sort(dlcs);
            for (String dlc : dlcs)
                names.add(dlcName(dlc));
        }
        return names;
    }

    /** The name of the DLC in dlc/<id>_<key>; an unknown key is shown with its words capitalised. */
    private static String dlcName(String folder) {
        String key = folder.replaceFirst("^\\d+_", "");
        switch (key) {
            case "musketeer": return "The Musketeer";
            case "crimson_court": return "The Crimson Court";
            case "shieldbreaker": return "The Shieldbreaker";
            case "color_of_madness": return "The Color of Madness";
            case "arena_mp": return "The Butcher's Circus";
            case "fires_edge": return "The Fire's Edge";
        }
        StringBuilder s = new StringBuilder();
        for (String word : key.split("_"))
            if (!word.isEmpty())
                s.append(s.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return s.toString();
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
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {   // the closed dropdown
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextSize(16);
                t.setTextColor(TEXT);
                t.setPadding(0, t.getPaddingTop(), t.getPaddingRight(), t.getPaddingBottom());
                return t;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(c);
        spinner.setAdapter(adapter);
        spinner.setSelection(shown);
        return spinner;
    }

    private static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Wrapped, `dp` below the view before it. */
    private static LinearLayout.LayoutParams below(Context c, float dp) {
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(c, dp);
        return lp;
    }

    private static TextView text(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    /** A bullet naming something the import takes, with its file name in monospace under it. */
    private static LinearLayout source(Context c, String what, String file) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(c, 8), 0, 0, 0);
        item.addView(text(c, "•  " + what, 14, TEXT));
        TextView name = text(c, file, 13, MUTED);
        name.setTypeface(Typeface.MONOSPACE);
        name.setPadding(dp(c, 18), 0, 0, 0);
        item.addView(name);
        return item;
    }

    private static TextView heading(Context c, String text) {
        TextView t = text(c, text, 20, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private static Button button(Context c, String text, int background, int color) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundTintList(ColorStateList.valueOf(background));
        b.setMinWidth(dp(c, 200));
        return b;
    }

    /** A card's content: views stacked from the top, left-aligned. */
    private static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        return card;
    }

    /** A card's background: a rounded panel. */
    private static GradientDrawable rounded(Context c) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(c, 16));
        return background;
    }

    private static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        return layout;
    }

    /** The system file picker, for one or more installers or zips. */
    private void pickFiles() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_INSTALLER);
    }

    /** What the save import takes and where to get it, then the system file picker for the zip. */
    private void askImportSaves() {
        String message = "Pick a zip of a Darkest Dungeon save folder, the folder that holds profile_0, "
                + "profile_1 and so on. The folder can be anywhere inside the zip.\n\n"
                + "From a Windows PC:\n"
                + "•  GOG: run get-saves.bat from the Releases page. It zips Documents\\Darkest into "
                + "darkest-dungeon-saves.zip next to it.\n"
                + "•  Steam: drop the folder Steam\\userdata\\<number>\\262060\\remote onto get-saves.bat.\n"
                + "•  Or zip the save folder yourself.\n\n"
                + "A zip made by Export works too. The game options on this phone are kept."
                + (hasSaves() ? "\n\nThis replaces the saves on this phone. Export them first to keep a copy." : "");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Import saves")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Pick zip", (d, w) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        IMPORT_SAVES))
                .show();
    }

    /** The system file picker, for where the exported saves go. */
    private void pickExportFile() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "darkest-dungeon-saves-" + LocalDate.now() + ".zip"), EXPORT_SAVES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null)
            return;
        if (requestCode == IMPORT_SAVES && data.getData() != null)
            saveTask("Importing saves.", "Saves imported", "Couldn't import saves", () -> unpackSaves(data.getData()));
        if (requestCode == EXPORT_SAVES && data.getData() != null)
            saveTask("Exporting saves.", "Saves exported", "Couldn't export saves", () -> exportSaves(data.getData()));
        if (requestCode != PICK_INSTALLER)
            return;
        List<Uri> files = new ArrayList<>();
        if (data.getClipData() != null)   // several files picked
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                files.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null)
            files.add(data.getData());
        if (!files.isEmpty())
            importGame(files);
    }

    /** The message with a progress bar under it, in place of the launch screen while work runs. */
    private ProgressBar progressScreen(String message) {
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout layout = column(c);
        layout.setBackgroundColor(BACKGROUND);
        layout.addView(text(c, message, 16, TEXT), wrap());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 360), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16);
        layout.addView(bar, lp);
        setContentView(layout);
        return bar;
    }

    /**
     * Runs a save import or export on a background thread behind a progress screen (so Play can't
     * start the game meanwhile), then opens the launch screen with its message, or with why it failed.
     */
    private void saveTask(String busy, String doneTitle, String failedTitle, Callable<String> task) {
        progressScreen(busy).setIndeterminate(true);
        new Thread(() -> {
            try {
                String message = task.call();
                handler.post(() -> showLaunchScreen(doneTitle, message));
            } catch (Exception e) {
                Log.e(TAG, failedTitle, e);
                String why = e.getMessage() != null ? e.getMessage() : e.toString();
                handler.post(() -> showLaunchScreen(failedTitle, why));
            }
        }).start();
    }

    /** Unpacks the picked installers or zips on a background thread, showing the progress. */
    private void importGame(List<Uri> files) {
        ProgressBar bar = progressScreen("Importing. Please keep the app open until this finishes.");
        new Thread(() -> {
            try {
                String note = unpackGame(files, percent -> handler.post(() -> bar.setProgress(percent)));
                handler.post(() -> showLaunchScreen("Already imported", note));
            } catch (Exception e) {
                Log.e(TAG, "import failed", e);
                String why = e.getMessage() != null ? e.getMessage() : e.toString();
                handler.post(() -> showLaunchScreen("Couldn't import", why));
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
        FrameLayout layout = new FrameLayout(this);
        layout.addView(view);
        layout.addView(shiftButton());   // over the game, not moved by panAboveKeyboard
        setContentView(layout);
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
        if (nativeStart(this, socket, gameW, gameH, refreshes) != 0) {
            Log.e(TAG, "cannot start the frame receiver");
            return;
        }
        inputManager = getSystemService(InputManager.class);
        inputManager.registerInputDeviceListener(padListener, handler);
        checkPads();
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
                    // ddview serves one game per process: its socket and threads last until the
                    // process ends, which Android delays after an activity finishes, and Play in
                    // that process again couldn't listen on the socket. So the app process ends
                    // with the game, at once, and the next launch starts afresh.
                    handler.post(() -> {
                        finish();
                        android.os.Process.killProcess(android.os.Process.myPid());
                    });
                } catch (InterruptedException ignored) {
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "cannot start the game", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null)
            inputManager.unregisterInputDeviceListener(padListener);
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
        holdShift(false);   // nothing stays held while the game is away
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
        if (padKey(keyCode, event, true))
            return true;
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
        if (padKey(keyCode, event, false))
            return true;
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

    /**
     * The Shift button: in the trinket inventory, the game sells the trinket clicked while Shift is
     * held. A tap holds Shift, the next releases it. It keeps its own touches (they don't move the
     * cursor or click) and never takes focus from the game view, which the keyboard types into.
     */
    private Button shiftButton() {
        Context c = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        shiftButton = button(c, "⇧ Shift", BUTTON, TEXT);
        shiftButton.setMinWidth(dp(c, 96));
        shiftButton.setAlpha(0.85f);
        shiftButton.setFocusable(false);
        shiftButton.setVisibility(View.GONE);
        shiftButton.setOnClickListener(b -> holdShift(!shiftHeld));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(c, 16);
        shiftButton.setLayoutParams(lp);
        return shiftButton;
    }

    /** Holds Shift down in the game (the button turns amber) or releases it. */
    private void holdShift(boolean held) {
        if (held == shiftHeld)
            return;
        shiftHeld = held;
        nativeKey(scancode(KeyEvent.KEYCODE_SHIFT_LEFT), held);
        shiftButton.setBackgroundTintList(ColorStateList.valueOf(held ? ACCENT : BUTTON));
        shiftButton.setTextColor(held ? ON_ACCENT : TEXT);
    }

    /**
     * Called by ddview (on its render thread) when the trinket inventory opens or closes. Shift is
     * released with it, so it never stays held where Shift-click does something else (in a dungeon,
     * it discards an item).
     */
    private void onShiftScreen(boolean open) {
        handler.post(() -> {
            if (!open)
                holdShift(false);
            shiftButton.setVisibility(open ? View.VISIBLE : View.GONE);
        });
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

    // ---- game controller ----
    // The game gets one controller, connected while any gamepad or joystick is: every such device
    // drives it. Buttons and axes use SDL's numbering (SDL_GameControllerButton, SDL_GameControllerAxis).
    private static final int PAD_DPAD_UP = 11, PAD_DPAD_DOWN = 12, PAD_DPAD_LEFT = 13, PAD_DPAD_RIGHT = 14;
    private static final int PAD_TRIGGER_LEFT = 4, PAD_TRIGGER_RIGHT = 5;
    private InputManager inputManager;
    private boolean padConnected;
    private final int[] padAxes = new int[6];   // the values last sent
    private int hatX, hatY;                     // the d-pad as last sent (-1, 0, 1)

    private final InputManager.InputDeviceListener padListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { checkPads(); }
        @Override public void onInputDeviceRemoved(int id) { checkPads(); }
        @Override public void onInputDeviceChanged(int id) { checkPads(); }
    };

    private static boolean isPad(InputDevice d) {
        if (d == null || d.isVirtual())
            return false;
        int sources = d.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void checkPads() {
        boolean any = false;
        for (int id : InputDevice.getDeviceIds())
            any |= isPad(InputDevice.getDevice(id));
        if (any == padConnected)
            return;
        padConnected = any;
        Arrays.fill(padAxes, 0);   // the game zeroes its state too
        hatX = hatY = 0;
        Log.i(TAG, "game controller " + (any ? "connected" : "disconnected"));
        nativePad(any);
    }

    /** The SDL_GameControllerButton of a controller key, or -1. */
    private static int padButton(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BUTTON_A: return 0;
        case KeyEvent.KEYCODE_BUTTON_B: return 1;
        case KeyEvent.KEYCODE_BUTTON_X: return 2;
        case KeyEvent.KEYCODE_BUTTON_Y: return 3;
        case KeyEvent.KEYCODE_BUTTON_SELECT: return 4;   // Back (View on Xbox controllers)
        case KeyEvent.KEYCODE_BUTTON_MODE: return 5;     // Guide
        case KeyEvent.KEYCODE_BUTTON_START: return 6;
        case KeyEvent.KEYCODE_BUTTON_THUMBL: return 7;
        case KeyEvent.KEYCODE_BUTTON_THUMBR: return 8;
        case KeyEvent.KEYCODE_BUTTON_L1: return 9;
        case KeyEvent.KEYCODE_BUTTON_R1: return 10;
        case KeyEvent.KEYCODE_DPAD_UP: return PAD_DPAD_UP;
        case KeyEvent.KEYCODE_DPAD_DOWN: return PAD_DPAD_DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT: return PAD_DPAD_LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return PAD_DPAD_RIGHT;
        default: return -1;
        }
    }

    /** Sends a controller's key as its button (or trigger); false if the key is not a controller's. */
    private boolean padKey(int keyCode, KeyEvent event, boolean down) {
        InputDevice d = event.getDevice();
        if (!isPad(d))
            return false;
        int button = padButton(keyCode);
        int trigger = keyCode == KeyEvent.KEYCODE_BUTTON_L2 ? PAD_TRIGGER_LEFT
                : keyCode == KeyEvent.KEYCODE_BUTTON_R2 ? PAD_TRIGGER_RIGHT : -1;
        if (button < 0 && trigger < 0)
            return false;
        if (event.getRepeatCount() > 0)
            return true;
        if (button >= 0)
            nativePadButton(button, down);
        else if (d.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null && d.getMotionRange(MotionEvent.AXIS_BRAKE) == null)
            padAxis(trigger, down ? 1 : 0);   // a digital trigger; analog ones come as axes
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (gameW == 0 || !e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.getActionMasked() != MotionEvent.ACTION_MOVE)
            return super.onGenericMotionEvent(e);
        padAxis(0, e.getAxisValue(MotionEvent.AXIS_X));
        padAxis(1, e.getAxisValue(MotionEvent.AXIS_Y));
        padAxis(2, e.getAxisValue(MotionEvent.AXIS_Z));
        padAxis(3, e.getAxisValue(MotionEvent.AXIS_RZ));
        // triggers come as LTRIGGER/RTRIGGER, BRAKE/GAS or both, depending on the controller
        padAxis(PAD_TRIGGER_LEFT, Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)));
        padAxis(PAD_TRIGGER_RIGHT, Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)));
        // a d-pad that reports as a hat; handling it here also stops Android making DPAD keys of it
        hatX = hat(hatX, Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X)), PAD_DPAD_LEFT, PAD_DPAD_RIGHT);
        hatY = hat(hatY, Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y)), PAD_DPAD_UP, PAD_DPAD_DOWN);
        return true;
    }

    /** Sends an axis (-1..1, triggers 0..1) in SDL's range, if it changed. */
    private void padAxis(int axis, float v) {
        int value = Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
        if (value == padAxes[axis])
            return;
        padAxes[axis] = value;
        nativePadAxis(axis, value);
    }

    /** Presses and releases d-pad buttons for a hat axis that moved from was to now (-1, 0, 1). */
    private static int hat(int was, int now, int negative, int positive) {
        if (now == was)
            return now;
        if (was != 0)
            nativePadButton(was < 0 ? negative : positive, false);
        if (now != 0)
            nativePadButton(now < 0 ? negative : positive, true);
        return now;
    }

    // ---- files ----
    private File gameDir() {
        return new File(getExternalFilesDir(null), "game");
    }

    private File gameBinary() {
        return new File(gameDir(), GAME_BINARY);
    }

    /** The game's save folder: ddglue's SDL_GetPrefPath("Red Hook Studios", "Darkest") under DD_HOME. */
    private File savesDir() {
        return new File(getFilesDir(), ".local/share/Red Hook Studios/Darkest");
    }

    private boolean hasSaves() {
        String[] names = savesDir().list();
        return names != null && names.length > 0;
    }

    /** Where unpackSaves keeps the replaced saves until the imported ones are in place. */
    private File replacedSaves() {
        return new File(savesDir().getParentFile(), "Darkest.before-import");
    }

    /**
     * Sorts out a save import the app was stopped in the middle of swapping: with Darkest/ in place
     * the import got through and the replaced saves are deleted; without it they are put back.
     */
    private void recoverSaves() throws IOException {
        File saves = savesDir(), replaced = replacedSaves();
        if (!replaced.exists())
            return;
        if (saves.exists())
            deleteTree(replaced);
        else if (!replaced.renameTo(saves))
            throw new IOException("Couldn't put the saves back: " + replaced + " → " + saves);
    }

    /** Zips the contents of the save folder (global.json, profile_0/… at the zip's root) into the picked file. */
    private String exportSaves(Uri file) throws Exception {
        Path saves = savesDir().toPath();
        int count = 0;
        try (OutputStream out = getContentResolver().openOutputStream(file, "w")) {
            if (out == null)
                throw new IOException("Couldn't write the file picked.");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16));
                 Stream<Path> paths = Files.walk(saves)) {
                for (Path p : (Iterable<Path>) paths.sorted()::iterator) {
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        continue;
                    ZipEntry e = new ZipEntry(saves.relativize(p).toString());
                    e.setTime(Files.getLastModifiedTime(p).toMillis());
                    zip.putNextEntry(e);
                    Files.copy(p, zip);
                    zip.closeEntry();
                    count++;
                }
            }
        } catch (Exception e) {
            try {   // no half-written zip that looks like saves
                DocumentsContract.deleteDocument(getContentResolver(), file);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return count + " files were saved in the zip. To bring the saves back, for example after reinstalling "
                + "the app or on another phone, tap Import and pick it.";
    }

    /**
     * Replaces the saves with the save folder in the picked zip: the shallowest folder in it that holds
     * a profile_<n>/ folder, so a zip of a PC's save folder (in a folder of its own or not) works as
     * well as one from exportSaves. The zip is read twice, first for its names, then to unpack that
     * folder into Darkest.import/. The zip's persist.options.json (from a PC: its resolution and
     * fullscreen) is left out and the phone's own kept. Darkest/ then becomes Darkest.before-import/
     * and Darkest.import/ becomes Darkest/; recoverSaves finishes the swap if the app is stopped in it.
     */
    private String unpackSaves(Uri file) throws IOException {
        File saves = savesDir(), tmp = new File(saves.getParentFile(), "Darkest.import"), replaced = replacedSaves();
        recoverSaves();
        deleteTree(tmp);   // left by an import that didn't finish
        String root = null;
        try (FileChannel ch = open(file)) {
            if (ch.size() > SAVES_MAX_BYTES)
                throw new IOException(String.format(Locale.US,
                        "That file is too big to be saves (%.0f MB; saves are a few MB).", ch.size() / 1e6));
            List<String> names = new ArrayList<>();
            ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16));
            for (ZipEntry e; (e = zip.getNextEntry()) != null; )
                names.add(entryName(e));
            root = saveRoot(names);

            ch.position(0);
            zip = new ZipInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16));
            String base = tmp.getCanonicalPath() + "/";
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = entryName(e);
                if (!name.startsWith(root) || name.length() == root.length() || name.equals(root + SAVE_OPTIONS))
                    continue;
                File out = new File(tmp, name.substring(root.length()));
                if (!out.getCanonicalPath().startsWith(base))
                    throw new IOException("The zip looks damaged (unexpected file name: " + e.getName() + ").");
                if (name.endsWith("/")) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    copy(zip, o);
                }
                if (e.getTime() >= 0)
                    out.setLastModified(e.getTime());
            }
        } catch (ZipException e) {
            throw new IOException(root == null ? NOT_SAVES : DAMAGED, e);
        }
        File options = new File(saves, SAVE_OPTIONS);
        if (options.isFile())
            Files.copy(options.toPath(), new File(tmp, SAVE_OPTIONS).toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (saves.exists() && !saves.renameTo(replaced))
            throw new IOException("Couldn't move the old saves aside: " + saves + " → " + replaced);
        if (!tmp.renameTo(saves)) {
            replaced.renameTo(saves);
            throw new IOException("Couldn't move the imported saves into place: " + tmp + " → " + saves);
        }
        deleteTree(replaced);
        return "Tap Play, and the campaigns are in the game's save list.";
    }

    /** A zip entry's name with / between folders and no leading ./ or /, as Windows tools may write them. */
    private static String entryName(ZipEntry e) {
        String name = e.getName().replace('\\', '/');
        while (name.startsWith("/") || name.startsWith("./"))
            name = name.substring(name.startsWith("/") ? 1 : 2);
        return name;
    }

    /**
     * The save folder among a zip's names, with a / at its end ("" for the zip's root): the shallowest
     * folder holding a profile_<n>/ folder. Two different ones at that depth are refused.
     */
    private static String saveRoot(List<String> names) throws IOException {
        List<String> roots = new ArrayList<>();   // those found at the smallest depth so far
        int depth = Integer.MAX_VALUE;
        for (String name : names) {
            String[] parts = name.split("/");
            for (int i = 0; i < parts.length - 1 && i <= depth; i++)   // folders only
                if (parts[i].matches("profile_\\d+")) {
                    String root = i == 0 ? "" : String.join("/", Arrays.asList(parts).subList(0, i)) + "/";
                    if (i < depth) {
                        roots.clear();
                        depth = i;
                    }
                    if (!roots.contains(root))
                        roots.add(root);
                    break;
                }
        }
        if (roots.isEmpty())
            throw new IOException(NOT_SAVES);
        if (roots.size() > 1)
            throw new IOException("That zip holds more than one save folder (" + names(roots)
                    + "). Please zip only the one to import.");
        return roots.get(0);
    }

    /**
     * Unpacks the picked files into game.import/. With the game among them, its shaders are patched
     * and game.import/ is renamed to game/; with only DLCs, their folders are moved into the installed
     * game. So a half-finished import is never taken for the game or a DLC. GOG's Linux installers
     * (the game's and each DLC's) are a shell script followed by a zip that holds the files in
     * data/noarch/game/; the Steam zip holds the game at its root. Each zip is streamed from where it
     * starts in the file, never copied. A DLC installer whose DLC is imported already, or comes in
     * another picked file, is skipped. Returns a note on the DLCs skipped as imported already, or null.
     */
    private String unpackGame(List<Uri> files, IntConsumer progress) throws Exception {
        File game = gameDir(), tmp = new File(game.getParentFile(), "game.import");
        deleteTree(tmp);   // left by an import that didn't finish
        List<GameZip> zips = new ArrayList<>();
        int games = 0;
        for (Uri file : files)
            try (FileChannel ch = open(file)) {
                GameZip z = new GameZip(ch);
                zips.add(z);
                if (z.game)
                    games++;
                else if (z.dlcs.isEmpty())
                    throw new IOException(NOT_A_GAME_FILE);
            }
        boolean installed = gameBinary().exists();
        if (games > 1)
            throw new IOException("Please pick only one copy of the game.");
        if (installed && games > 0)
            throw new IOException("The game is already imported. To add DLCs, pick only their installers.");
        if (!installed && games == 0)
            throw new IOException("DLCs need the game. Please pick the game's installer along with them.");

        // the DLCs that will be there: those imported already, then the game's, then each DLC
        // installer's unless all of its DLCs are there already
        String[] importedDlcs = installed ? new File(game, "dlc").list() : null;
        List<String> imported = importedDlcs != null ? Arrays.asList(importedDlcs) : new ArrayList<>();
        List<String> there = new ArrayList<>(imported);
        for (GameZip z : zips)
            if (z.game)
                there.addAll(z.dlcs);
        boolean[] skip = new boolean[zips.size()];
        List<String> skipped = new ArrayList<>();   // the names of those imported already
        long need = 0, total = 0;
        for (int f = 0; f < zips.size(); f++) {
            GameZip z = zips.get(f);
            if (!z.game && there.containsAll(z.dlcs)) {
                skip[f] = true;
                for (String dlc : z.dlcs)
                    if (imported.contains(dlc) && !skipped.contains(dlcName(dlc)))
                        skipped.add(dlcName(dlc));
                continue;
            }
            there.addAll(z.dlcs);
            need += z.bytes;
            total += z.zipBytes;
        }
        String note = null;
        if (!skipped.isEmpty())
            note = total == 0
                    ? names(skipped) + (skipped.size() > 1 ? " are" : " is") + " already imported, so there was nothing new to add."
                    : names(skipped) + (skipped.size() > 1 ? " were already imported, so they were" : " was already imported, so it was") + " skipped.";
        if (total == 0)   // every pick was skipped
            return note;
        long free = game.getParentFile().getUsableSpace();
        if (free < need)
            throw new IOException(String.format(Locale.US,
                    "There isn't enough free space: this needs %.1f GB and %.1f GB is free.", need / 1e9, free / 1e9));

        String root = tmp.getCanonicalPath() + "/";
        long done = 0;   // zip bytes of the files already unpacked
        int shown = -1;
        for (int f = 0; f < files.size(); f++) {
            if (skip[f])
                continue;
            try (FileChannel ch = open(files.get(f))) {
                GameZip z = zips.get(f);
                ch.position(z.start);
                ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16));
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (!e.getName().startsWith(z.prefix) || e.getName().equals(z.prefix))
                        continue;
                    File out = new File(tmp, e.getName().substring(z.prefix.length()));
                    if (!out.getCanonicalPath().startsWith(root))
                        throw new IOException("The installer looks damaged (unexpected file name: " + e.getName() + ").");
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
                    int percent = (int) (100 * (done + ch.position() - z.start) / total);
                    if (percent != shown)
                        progress.accept(shown = percent);
                }
                done += z.zipBytes;
            }
        }
        if (installed) {
            moveInto(tmp, game, 1);   // dlc/ is merged, each DLC folder in it replaced
            deleteTree(tmp);
            return note;
        }
        patchShaders(tmp);
        deleteTree(game);   // only an incomplete copy can be here; saves live in getFilesDir()
        if (!tmp.renameTo(game))
            throw new IOException("Couldn't move the imported files into place: " + tmp + " → " + game);
        return note;
    }

    /** "A", "A and B", "A, B and C". */
    private static String names(List<String> names) {
        int last = names.size() - 1;
        return last == 0 ? names.get(0) : String.join(", ", names.subList(0, last)) + " and " + names.get(last);
    }

    private FileChannel open(Uri file) throws IOException {
        return new ParcelFileDescriptor.AutoCloseInputStream(getContentResolver().openFileDescriptor(file, "r"))
                .getChannel();
    }

    /** What a picked file holds, read from its zip's end record and central directory. */
    private static final class GameZip {
        long start;      // where the zip starts in the file
        long zipBytes;   // the zip's own size, from start to the end of the file
        String prefix;   // the game folder inside the zip
        long bytes;      // what the files under prefix unpack to
        boolean game;    // the game binary is under prefix
        List<String> dlcs = new ArrayList<>();   // the DLC folders under prefix/dlc/

        /**
         * The zip starts at 0 in a plain zip, or after what is in front of it. The end record counts
         * the central directory's offset from the zip's own start, and the directory ends at the
         * record. Past 4 GB a zip is zip64: its end record holds 0xffffffff, and the zip64 end record
         * (found through its locator, just before the end record) holds the real values. Only the
         * Steam zip, a plain zip, can be that big.
         */
        GameZip(FileChannel ch) throws IOException {
            long size = ch.size();
            int len = (int) Math.min(size, 22 + 65535);   // the end record plus its longest comment
            ByteBuffer b = read(ch, size - len, len);
            int i = len - 22;
            while (i >= 0 && b.getInt(i) != 0x06054b50)
                i--;
            if (i < 0)
                throw new IOException(NOT_A_GAME_FILE);
            long end = size - len + i;
            long dirSize = b.getInt(i + 12) & 0xffffffffL, dirOffset = b.getInt(i + 16) & 0xffffffffL;
            if (dirOffset == 0xffffffffL) {
                ByteBuffer locator = read(ch, end - 20, 20);
                if (locator.getInt(0) != 0x07064b50)
                    throw new IOException(DAMAGED);
                ByteBuffer end64 = read(ch, locator.getLong(8), 56);
                if (end64.getInt(0) != 0x06064b50)
                    throw new IOException(DAMAGED);
                dirSize = end64.getLong(40);
                dirOffset = end64.getLong(48);
                start = 0;
            } else
                start = end - dirSize - dirOffset;
            if (start < 0 || read(ch, start, 4).getInt(0) != 0x04034b50)
                throw new IOException(NOT_A_GAME_FILE);
            prefix = start > 0 ? INSTALLER_GAME : "";   // something in front of the zip: GOG's installer
            zipBytes = size - start;

            ByteBuffer dir = read(ch, start + dirOffset, (int) dirSize);
            for (i = 0; i + 46 <= dir.limit() && dir.getInt(i) == 0x02014b50; ) {
                int nameLen = dir.getShort(i + 28) & 0xffff, extraLen = dir.getShort(i + 30) & 0xffff;
                String name = new String(dir.array(), i + 46, nameLen, StandardCharsets.ISO_8859_1);
                long unpacked = dir.getInt(i + 24) & 0xffffffffL;
                if (unpacked == 0xffffffffL)   // the real size leads the zip64 extra field (id 1)
                    for (int x = i + 46 + nameLen; x + 12 <= i + 46 + nameLen + extraLen;
                         x += 4 + (dir.getShort(x + 2) & 0xffff))
                        if (dir.getShort(x) == 1) {
                            unpacked = dir.getLong(x + 4);
                            break;
                        }
                if (name.startsWith(prefix)) {
                    bytes += unpacked;
                    game |= name.equals(prefix + GAME_BINARY);
                    String dlc = name.startsWith(prefix + "dlc/") ? name.substring(prefix.length() + 4) : "";
                    if (dlc.indexOf('/') > 0 && !dlcs.contains(dlc = dlc.substring(0, dlc.indexOf('/'))))
                        dlcs.add(dlc);
                }
                i += 46 + nameLen + extraLen + (dir.getShort(i + 32) & 0xffff);
            }
        }
    }

    /** len bytes of the file from pos, little-endian. */
    private static ByteBuffer read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining() && ch.read(b, pos + b.position()) > 0)
            ;
        if (b.hasRemaining())
            throw new IOException(DAMAGED);
        return b;
    }

    /** Moves src's entries into dest, replacing what is there; folders down to `merge` levels are merged instead. */
    private static void moveInto(File src, File dest, int merge) throws IOException {
        dest.mkdirs();
        for (File f : src.listFiles()) {
            File to = new File(dest, f.getName());
            if (merge > 0 && f.isDirectory())
                moveInto(f, to, merge - 1);
            else {
                deleteTree(to);
                if (!f.renameTo(to))
                    throw new IOException("Couldn't move the imported files into place: " + f + " → " + to);
            }
        }
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
            throw new IOException("Couldn't convert the game's shaders: " + out.toString().trim());
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
