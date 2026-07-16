package br.com.pu2ptu.relogiocentrocontrole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "clock_config";
    private static final String PREF_LANGUAGE = "language";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String PREF_AUTO_SYNC = "auto_sync";
    private static final String PREF_LAYOUT_MODE = "layout_mode";
    private static final String PREF_TEXT_SIZE = "text_size";
    private static final String PREF_SPACING = "spacing";
    private static final String PREF_SHOW_DATE = "show_date";
    private static final String PREF_SHOW_DOY = "show_doy";
    private static final String PREF_SHOW_WEEKDAY = "show_weekday";
    private static final String PREF_COLOR_THEME = "color_theme";
    private static final String PREF_BURN_IN = "burn_in_protection";

    private static final int CLOCK_COUNT = 8;
    private static final int MIN_OFFSET_MINUTES = -12 * 60;
    private static final int MAX_OFFSET_MINUTES = 14 * 60;
    private static final int OFFSET_STEP_MINUTES = 15;

    private static final int LAYOUT_AUTOMATIC = 0;
    private static final int LAYOUT_ONE_COLUMN = 1;
    private static final int LAYOUT_TWO_COLUMNS = 2;

    private static final int THEME_MISSION_RED = 0;
    private static final int THEME_TERMINAL_GREEN = 1;
    private static final int THEME_AMBER = 2;
    private static final int THEME_MONOCHROME = 3;
    private static final int THEME_NIGHT_RED = 4;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US);
    private static final DateTimeFormatter UTC_STATUS_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);

    private static final String[] LANGUAGE_CODES = {"en", "pt", "es", "it", "fr"};
    private static final String[] LANGUAGE_NAMES = {
            "English", "Português", "Español", "Italiano", "Français"
    };

    private static final String[] DEFAULT_LABELS = {
            "UTC", "BRT", "EST", "GMT", "JST", "C6", "C7", "C8"
    };
    private static final int[] DEFAULT_OFFSETS = {
            0, -180, -300, 0, 540, 0, 0, 0
    };
    private static final boolean[] DEFAULT_ENABLED = {
            true, true, true, true, true, false, false, false
    };

    private static final int[] BURN_IN_X = {0, -1, 1, -2, 2};
    private static final int[] BURN_IN_Y = {0, 2, -2, 1, -1};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ClockEntry> clocks = new ArrayList<>();
    private ClockPanel panel;

    private volatile long timeOffsetMillis = 0L;
    private volatile boolean syncInProgress = false;
    private volatile long syncRequestId = 0L;
    private SyncState syncState = SyncState.DEVICE_TIME;
    private String syncSource = "";
    private long lastSyncUtcMillis = 0L;
    private long lastSyncOffsetMillis = 0L;
    private long lastSyncRoundTripMillis = 0L;

    private String languageCode = "en";
    private boolean keepScreenOn = true;
    private boolean autoSync = true;
    private int layoutMode = LAYOUT_AUTOMATIC;
    private int textSizeSetting = 1;
    private int spacingSetting = 1;
    private boolean showDate = true;
    private boolean showDoy = true;
    private boolean showWeekday = false;
    private int colorTheme = THEME_MISSION_RED;
    private boolean burnInProtection = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (panel != null) {
                panel.invalidate();
            }
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        SharedPreferences prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = normalizeLanguageCode(prefs.getString(PREF_LANGUAGE, "en"));
        Locale locale = Locale.forLanguageTag(code);
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadConfig();
        applyScreenOnPreference();
        panel = new ClockPanel(this);
        setContentView(panel);
        enterImmersiveMode();
        handler.post(tick);

        syncState = SyncState.DEVICE_TIME;
        if (autoSync) {
            synchronizeTime();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        applyScreenOnPreference();
    }

    @Override
    protected void onDestroy() {
        syncRequestId++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void applyScreenOnPreference() {
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        languageCode = normalizeLanguageCode(prefs.getString(PREF_LANGUAGE, "en"));
        keepScreenOn = prefs.getBoolean(PREF_KEEP_SCREEN_ON, true);
        autoSync = prefs.getBoolean(PREF_AUTO_SYNC, true);
        layoutMode = clampSetting(prefs.getInt(PREF_LAYOUT_MODE, LAYOUT_AUTOMATIC), 0, 2);
        textSizeSetting = clampSetting(prefs.getInt(PREF_TEXT_SIZE, 1), 0, 2);
        spacingSetting = clampSetting(prefs.getInt(PREF_SPACING, 1), 0, 2);
        showDate = prefs.getBoolean(PREF_SHOW_DATE, true);
        showDoy = prefs.getBoolean(PREF_SHOW_DOY, true);
        showWeekday = prefs.getBoolean(PREF_SHOW_WEEKDAY, false);
        colorTheme = clampSetting(prefs.getInt(PREF_COLOR_THEME, THEME_MISSION_RED), 0, 4);
        burnInProtection = prefs.getBoolean(PREF_BURN_IN, false);

        clocks.clear();
        SharedPreferences.Editor migration = null;
        Instant migrationInstant = Instant.now();

        for (int i = 0; i < CLOCK_COUNT; i++) {
            int offsetMinutes;
            String offsetKey = "offset_" + i;

            if (prefs.contains(offsetKey)) {
                offsetMinutes = normalizeOffsetMinutes(
                        prefs.getInt(offsetKey, DEFAULT_OFFSETS[i]));
            } else {
                String legacyZone = prefs.getString("zone_" + i, null);
                offsetMinutes = legacyZone == null
                        ? DEFAULT_OFFSETS[i]
                        : offsetFromLegacyZone(legacyZone, migrationInstant, DEFAULT_OFFSETS[i]);

                if (migration == null) {
                    migration = prefs.edit();
                }
                migration.putInt(offsetKey, offsetMinutes);
            }

            clocks.add(new ClockEntry(
                    prefs.getBoolean("enabled_" + i, DEFAULT_ENABLED[i]),
                    prefs.getString("label_" + i, DEFAULT_LABELS[i]),
                    offsetMinutes));
        }

        if (migration != null) {
            migration.apply();
        }
    }

    private boolean saveConfig(List<ClockEntry> entries, AppSettings settings) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(PREF_LANGUAGE, normalizeLanguageCode(settings.language));
        editor.putBoolean(PREF_KEEP_SCREEN_ON, settings.keepScreenOn);
        editor.putBoolean(PREF_AUTO_SYNC, settings.autoSync);
        editor.putInt(PREF_LAYOUT_MODE, clampSetting(settings.layoutMode, 0, 2));
        editor.putInt(PREF_TEXT_SIZE, clampSetting(settings.textSize, 0, 2));
        editor.putInt(PREF_SPACING, clampSetting(settings.spacing, 0, 2));
        editor.putBoolean(PREF_SHOW_DATE, settings.showDate);
        editor.putBoolean(PREF_SHOW_DOY, settings.showDoy);
        editor.putBoolean(PREF_SHOW_WEEKDAY, settings.showWeekday);
        editor.putInt(PREF_COLOR_THEME, clampSetting(settings.colorTheme, 0, 4));
        editor.putBoolean(PREF_BURN_IN, settings.burnInProtection);

        for (int i = 0; i < entries.size(); i++) {
            ClockEntry entry = entries.get(i);
            editor.putBoolean("enabled_" + i, entry.enabled);
            editor.putString("label_" + i, entry.label);
            editor.putInt("offset_" + i, entry.offsetMinutes);
            editor.remove("zone_" + i);
        }
        return editor.commit();
    }

    private void showSettings() {
        final String previousLanguage = languageCode;
        final boolean previousAutoSync = autoSync;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        addSectionTitle(root, getString(R.string.general_section));

        TextView languageLabel = addFieldLabel(root, R.string.language_label);
        languageLabel.setPadding(0, dp(6), 0, dp(4));

        final Spinner languageSpinner = new Spinner(this);
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                LANGUAGE_NAMES);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        languageSpinner.setSelection(languagePosition(languageCode));
        root.addView(languageSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)));

        final CheckBox keepScreenOnCheck = new CheckBox(this);
        keepScreenOnCheck.setText(R.string.keep_screen_on);
        keepScreenOnCheck.setChecked(keepScreenOn);
        root.addView(keepScreenOnCheck);

        final CheckBox autoSyncCheck = new CheckBox(this);
        autoSyncCheck.setText(R.string.automatic_sync);
        autoSyncCheck.setChecked(autoSync);
        root.addView(autoSyncCheck);

        TextView syncHelp = new TextView(this);
        syncHelp.setText(R.string.automatic_sync_help);
        syncHelp.setPadding(dp(4), 0, 0, dp(12));
        root.addView(syncHelp);

        addSectionTitle(root, getString(R.string.display_section));

        final Spinner layoutSpinner = addArraySpinner(
                root, R.string.layout_mode, R.array.layout_modes, layoutMode);
        final Spinner textSizeSpinner = addArraySpinner(
                root, R.string.text_size, R.array.text_sizes, textSizeSetting);
        final Spinner spacingSpinner = addArraySpinner(
                root, R.string.spacing, R.array.spacing_options, spacingSetting);

        addSectionTitle(root, getString(R.string.date_section));

        final CheckBox showDateCheck = new CheckBox(this);
        showDateCheck.setText(R.string.show_date);
        showDateCheck.setChecked(showDate);
        root.addView(showDateCheck);

        final CheckBox showDoyCheck = new CheckBox(this);
        showDoyCheck.setText(R.string.show_doy);
        showDoyCheck.setChecked(showDoy);
        root.addView(showDoyCheck);

        final CheckBox showWeekdayCheck = new CheckBox(this);
        showWeekdayCheck.setText(R.string.show_weekday);
        showWeekdayCheck.setChecked(showWeekday);
        root.addView(showWeekdayCheck);

        addSectionTitle(root, getString(R.string.appearance_section));

        final Spinner themeSpinner = addArraySpinner(
                root, R.string.color_theme, R.array.color_themes, colorTheme);

        final CheckBox burnInCheck = new CheckBox(this);
        burnInCheck.setText(R.string.burn_in_protection);
        burnInCheck.setChecked(burnInProtection);
        root.addView(burnInCheck);

        TextView burnInHelp = new TextView(this);
        burnInHelp.setText(R.string.burn_in_help);
        burnInHelp.setPadding(dp(4), 0, 0, dp(12));
        root.addView(burnInHelp);

        addSectionTitle(root, getString(R.string.clocks_section));

        TextView help = new TextView(this);
        help.setText(R.string.settings_help);
        help.setPadding(0, dp(6), 0, dp(4));
        root.addView(help);

        TextView reorderHelp = new TextView(this);
        reorderHelp.setText(R.string.reorder_help);
        reorderHelp.setPadding(0, 0, 0, dp(12));
        root.addView(reorderHelp);

        List<String> offsetLabels = buildOffsetLabels();
        List<ClockDraft> drafts = new ArrayList<>();
        for (ClockEntry entry : clocks) {
            drafts.add(new ClockDraft(entry.enabled, entry.label, entry.offsetMinutes));
        }

        LinearLayout clocksContainer = new LinearLayout(this);
        clocksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(clocksContainer);

        List<ClockEditor> editors = new ArrayList<>();
        renderClockEditors(clocksContainer, drafts, editors, offsetLabels);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.sync_now, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> synchronizeTime());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                captureClockEditors(editors);
                List<ClockEntry> updated = new ArrayList<>();
                boolean anyEnabled = false;

                for (int i = 0; i < drafts.size(); i++) {
                    ClockDraft draft = drafts.get(i);
                    String label = draft.label.trim().toUpperCase(Locale.ROOT);
                    if (label.isEmpty()) {
                        label = "C" + (i + 1);
                    }
                    anyEnabled |= draft.enabled;
                    updated.add(new ClockEntry(draft.enabled, label, draft.offsetMinutes));
                }

                if (!anyEnabled) {
                    Toast.makeText(this, R.string.enable_one_clock, Toast.LENGTH_LONG).show();
                    return;
                }

                int languageIndex = languageSpinner.getSelectedItemPosition();
                String selectedLanguage = languageIndex >= 0 && languageIndex < LANGUAGE_CODES.length
                        ? LANGUAGE_CODES[languageIndex]
                        : "en";

                AppSettings settings = new AppSettings(
                        selectedLanguage,
                        keepScreenOnCheck.isChecked(),
                        autoSyncCheck.isChecked(),
                        layoutSpinner.getSelectedItemPosition(),
                        textSizeSpinner.getSelectedItemPosition(),
                        spacingSpinner.getSelectedItemPosition(),
                        showDateCheck.isChecked(),
                        showDoyCheck.isChecked(),
                        showWeekdayCheck.isChecked(),
                        themeSpinner.getSelectedItemPosition(),
                        burnInCheck.isChecked());

                if (!saveConfig(updated, settings)) {
                    Toast.makeText(this, R.string.save_error, Toast.LENGTH_LONG).show();
                    return;
                }

                loadConfig();
                applyScreenOnPreference();

                boolean languageChanged = !previousLanguage.equals(languageCode);
                boolean autoSyncEnabledNow = !previousAutoSync && autoSync;
                boolean autoSyncDisabledNow = previousAutoSync && !autoSync;

                if (autoSyncDisabledNow) {
                    useDeviceTime();
                }

                dialog.dismiss();

                if (languageChanged) {
                    recreate();
                    return;
                }

                if (panel != null) {
                    panel.applyTheme();
                    panel.invalidate();
                    panel.requestLayout();
                }
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();

                if (autoSyncEnabledNow) {
                    synchronizeTime();
                }
            });
        });

        dialog.setOnDismissListener(ignored -> enterImmersiveMode());
        dialog.show();
    }

    private void renderClockEditors(
            LinearLayout container,
            List<ClockDraft> drafts,
            List<ClockEditor> editors,
            List<String> offsetLabels) {
        container.removeAllViews();
        editors.clear();

        for (int i = 0; i < drafts.size(); i++) {
            final int index = i;
            ClockDraft draft = drafts.get(i);

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(4), 0, dp(10));

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);

            CheckBox enabled = new CheckBox(this);
            enabled.setChecked(draft.enabled);
            enabled.setText(getString(R.string.clock_number, i + 1));
            header.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1f));

            EditText label = new EditText(this);
            label.setSingleLine(true);
            label.setText(draft.label);
            label.setAllCaps(true);
            label.setSelectAllOnFocus(true);
            label.setHint(R.string.label_hint);
            label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            header.addView(label, new LinearLayout.LayoutParams(dp(76), dp(48)));

            Button up = new Button(this);
            up.setText("▲");
            up.setContentDescription(getString(R.string.move_up));
            up.setEnabled(i > 0);
            up.setOnClickListener(v -> {
                captureClockEditors(editors);
                Collections.swap(drafts, index, index - 1);
                renderClockEditors(container, drafts, editors, offsetLabels);
            });
            header.addView(up, new LinearLayout.LayoutParams(dp(48), dp(48)));

            Button down = new Button(this);
            down.setText("▼");
            down.setContentDescription(getString(R.string.move_down));
            down.setEnabled(i < drafts.size() - 1);
            down.setOnClickListener(v -> {
                captureClockEditors(editors);
                Collections.swap(drafts, index, index + 1);
                renderClockEditors(container, drafts, editors, offsetLabels);
            });
            header.addView(down, new LinearLayout.LayoutParams(dp(48), dp(48)));

            block.addView(header);

            TextView offsetLabel = new TextView(this);
            offsetLabel.setText(R.string.utc_offset);
            offsetLabel.setPadding(dp(4), 0, 0, 0);
            block.addView(offsetLabel);

            Spinner offset = new Spinner(this);
            ArrayAdapter<String> offsetAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    offsetLabels);
            offsetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            offset.setAdapter(offsetAdapter);
            offset.setSelection(positionForOffset(draft.offsetMinutes));
            block.addView(offset, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)));

            editors.add(new ClockEditor(draft, enabled, label, offset));
            container.addView(block);

            if (i < drafts.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(currentTheme().border);
                container.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)));
            }
        }
    }

    private void captureClockEditors(List<ClockEditor> editors) {
        for (ClockEditor editor : editors) {
            editor.draft.enabled = editor.enabled.isChecked();
            editor.draft.label = editor.label.getText().toString()
                    .trim()
                    .toUpperCase(Locale.ROOT);
            editor.draft.offsetMinutes = offsetForPosition(
                    editor.offset.getSelectedItemPosition());
        }
    }

    private TextView addFieldLabel(LinearLayout root, int textResource) {
        TextView label = new TextView(this);
        label.setText(textResource);
        label.setPadding(0, dp(8), 0, dp(2));
        root.addView(label);
        return label;
    }

    private Spinner addArraySpinner(
            LinearLayout root,
            int labelResource,
            int arrayResource,
            int selectedPosition) {
        addFieldLabel(root, labelResource);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                arrayResource,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(adapter.getCount() - 1, selectedPosition)));
        root.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)));
        return spinner;
    }

    private void addSectionTitle(LinearLayout root, String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(18f);
        text.setTextColor(currentTheme().accent);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setPadding(0, dp(8), 0, dp(4));
        root.addView(text);
    }

    private void synchronizeTime() {
        if (syncInProgress) {
            Toast.makeText(this, R.string.synchronizing, Toast.LENGTH_SHORT).show();
            return;
        }

        syncInProgress = true;
        final long requestId = ++syncRequestId;
        syncState = SyncState.SYNCHRONIZING;
        if (panel != null) {
            panel.invalidate();
        }
        Toast.makeText(this, R.string.synchronizing, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            SyncMeasurement measurement = queryNtp("time.cloudflare.com");
            String method = "NTP";
            if (measurement == null) {
                measurement = queryNtp("pool.ntp.org");
            }
            if (measurement == null) {
                measurement = queryHttps("https://www.google.com/generate_204");
                method = "HTTPS";
            }

            final SyncMeasurement result = measurement;
            final String source = method;
            handler.post(() -> {
                if (requestId != syncRequestId) {
                    return;
                }
                syncInProgress = false;
                if (result != null) {
                    timeOffsetMillis = result.offsetMillis;
                    lastSyncOffsetMillis = result.offsetMillis;
                    lastSyncRoundTripMillis = result.roundTripMillis;
                    lastSyncUtcMillis = System.currentTimeMillis() + result.offsetMillis;
                    syncSource = source;
                    syncState = SyncState.SYNCHRONIZED;
                    Toast.makeText(
                            this,
                            getString(R.string.sync_success, source),
                            Toast.LENGTH_SHORT).show();
                } else {
                    useDeviceTime();
                    syncState = SyncState.FAILED;
                    Toast.makeText(this, R.string.sync_failure, Toast.LENGTH_LONG).show();
                }
                if (panel != null) {
                    panel.invalidate();
                }
            });
        }, "time-sync").start();
    }

    private void useDeviceTime() {
        syncRequestId++;
        syncInProgress = false;
        timeOffsetMillis = 0L;
        lastSyncOffsetMillis = 0L;
        lastSyncRoundTripMillis = 0L;
        lastSyncUtcMillis = 0L;
        syncSource = "";
        syncState = SyncState.DEVICE_TIME;
    }

    private static SyncMeasurement queryNtp(String host) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2500);
            byte[] buffer = new byte[48];
            buffer[0] = 0x1B;
            InetAddress address = InetAddress.getByName(host);
            long t1 = System.currentTimeMillis();
            socket.send(new DatagramPacket(buffer, buffer.length, address, 123));
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            long t4 = System.currentTimeMillis();
            long seconds = ((buffer[40] & 0xffL) << 24)
                    | ((buffer[41] & 0xffL) << 16)
                    | ((buffer[42] & 0xffL) << 8)
                    | (buffer[43] & 0xffL);
            long fraction = ((buffer[44] & 0xffL) << 24)
                    | ((buffer[45] & 0xffL) << 16)
                    | ((buffer[46] & 0xffL) << 8)
                    | (buffer[47] & 0xffL);
            long serverMillis = (seconds - 2208988800L) * 1000L
                    + ((fraction * 1000L) >>> 32);
            long offset = serverMillis - ((t1 + t4) / 2L);
            return new SyncMeasurement(offset, Math.max(0L, t4 - t1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SyncMeasurement queryHttps(String address) {
        HttpURLConnection connection = null;
        try {
            long t1 = System.currentTimeMillis();
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.connect();
            try (InputStream stream = connection.getInputStream()) {
                stream.read();
            }
            long t4 = System.currentTimeMillis();
            long serverMillis = connection.getDate() + 500L;
            if (serverMillis <= 0) {
                return null;
            }
            long offset = serverMillis - ((t1 + t4) / 2L);
            return new SyncMeasurement(offset, Math.max(0L, t4 - t1));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getSyncStatusText() {
        switch (syncState) {
            case SYNCHRONIZING:
                return getString(R.string.sync_status_synchronizing);
            case SYNCHRONIZED:
                String offset = String.format(Locale.ROOT, "%+d", lastSyncOffsetMillis);
                String lastSync = Instant.ofEpochMilli(lastSyncUtcMillis)
                        .atOffset(ZoneOffset.UTC)
                        .format(UTC_STATUS_TIME_FORMAT);
                return getString(
                        R.string.sync_status_synchronized,
                        syncSource,
                        offset,
                        lastSyncRoundTripMillis,
                        lastSync);
            case FAILED:
                return getString(R.string.sync_status_failed);
            case DEVICE_TIME:
            default:
                return getString(R.string.sync_status_device_time);
        }
    }

    private int getSyncStatusColor() {
        switch (syncState) {
            case SYNCHRONIZED:
                return Color.rgb(76, 200, 100);
            case SYNCHRONIZING:
            case DEVICE_TIME:
                return Color.rgb(255, 179, 0);
            case FAILED:
            default:
                return Color.rgb(239, 68, 68);
        }
    }

    private String buildDateDetail(OffsetDateTime dateTime) {
        List<String> parts = new ArrayList<>();
        Locale locale = getResources().getConfiguration().getLocales().get(0);

        if (showWeekday) {
            String weekday = dateTime.format(DateTimeFormatter.ofPattern("EEE", locale));
            parts.add(weekday.toUpperCase(locale));
        }
        if (showDate) {
            parts.add(dateTime.format(DATE_FORMAT));
        }
        if (showDoy) {
            parts.add(String.format(Locale.ROOT, "DOY %03d", dateTime.getDayOfYear()));
        }
        return String.join("  ·  ", parts);
    }

    private ThemeColors currentTheme() {
        switch (colorTheme) {
            case THEME_TERMINAL_GREEN:
                return new ThemeColors(
                        Color.rgb(3, 12, 6),
                        Color.rgb(72, 255, 105),
                        Color.rgb(47, 180, 72),
                        Color.rgb(39, 90, 53),
                        Color.rgb(139, 201, 151));
            case THEME_AMBER:
                return new ThemeColors(
                        Color.rgb(13, 9, 1),
                        Color.rgb(255, 176, 0),
                        Color.rgb(211, 137, 0),
                        Color.rgb(105, 75, 23),
                        Color.rgb(215, 178, 103));
            case THEME_MONOCHROME:
                return new ThemeColors(
                        Color.rgb(8, 10, 13),
                        Color.rgb(242, 244, 247),
                        Color.rgb(184, 190, 200),
                        Color.rgb(78, 84, 94),
                        Color.rgb(185, 190, 199));
            case THEME_NIGHT_RED:
                return new ThemeColors(
                        Color.rgb(0, 0, 0),
                        Color.rgb(145, 31, 24),
                        Color.rgb(101, 27, 22),
                        Color.rgb(46, 25, 24),
                        Color.rgb(111, 55, 51));
            case THEME_MISSION_RED:
            default:
                return new ThemeColors(
                        Color.rgb(15, 15, 15),
                        Color.rgb(255, 50, 31),
                        Color.rgb(206, 63, 48),
                        Color.rgb(88, 88, 88),
                        Color.rgb(180, 180, 180));
        }
    }

    private static String normalizeLanguageCode(String code) {
        if (code != null) {
            for (String supported : LANGUAGE_CODES) {
                if (supported.equalsIgnoreCase(code)) {
                    return supported;
                }
            }
        }
        return "en";
    }

    private static int languagePosition(String code) {
        String normalized = normalizeLanguageCode(code);
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(normalized)) {
                return i;
            }
        }
        return 0;
    }

    private static int offsetFromLegacyZone(String zoneId, Instant instant, int fallback) {
        try {
            int minutes = ZoneId.of(zoneId).getRules().getOffset(instant).getTotalSeconds() / 60;
            return normalizeOffsetMinutes(minutes);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clampSetting(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int normalizeOffsetMinutes(int minutes) {
        int clamped = Math.max(MIN_OFFSET_MINUTES, Math.min(MAX_OFFSET_MINUTES, minutes));
        return Math.round(clamped / (float) OFFSET_STEP_MINUTES) * OFFSET_STEP_MINUTES;
    }

    private static List<String> buildOffsetLabels() {
        List<String> labels = new ArrayList<>();
        for (int minutes = MIN_OFFSET_MINUTES;
             minutes <= MAX_OFFSET_MINUTES;
             minutes += OFFSET_STEP_MINUTES) {
            labels.add(formatOffset(minutes));
        }
        return labels;
    }

    private static String formatOffset(int totalMinutes) {
        int absolute = Math.abs(totalMinutes);
        return String.format(
                Locale.ROOT,
                "UTC%s%02d:%02d",
                totalMinutes < 0 ? "-" : "+",
                absolute / 60,
                absolute % 60);
    }

    private static int positionForOffset(int minutes) {
        return (normalizeOffsetMinutes(minutes) - MIN_OFFSET_MINUTES) / OFFSET_STEP_MINUTES;
    }

    private static int offsetForPosition(int position) {
        int maxPosition = (MAX_OFFSET_MINUTES - MIN_OFFSET_MINUTES) / OFFSET_STEP_MINUTES;
        int safePosition = Math.max(0, Math.min(maxPosition, position));
        return MIN_OFFSET_MINUTES + safePosition * OFFSET_STEP_MINUTES;
    }

    private float textScale() {
        switch (textSizeSetting) {
            case 0:
                return 0.86f;
            case 2:
                return 1.14f;
            case 1:
            default:
                return 1.0f;
        }
    }

    private float spacingScale() {
        switch (spacingSetting) {
            case 0:
                return 0.68f;
            case 2:
                return 1.32f;
            case 1:
            default:
                return 1.0f;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum SyncState {
        DEVICE_TIME,
        SYNCHRONIZING,
        SYNCHRONIZED,
        FAILED
    }

    private static final class SyncMeasurement {
        final long offsetMillis;
        final long roundTripMillis;

        SyncMeasurement(long offsetMillis, long roundTripMillis) {
            this.offsetMillis = offsetMillis;
            this.roundTripMillis = roundTripMillis;
        }
    }

    private static final class ThemeColors {
        final int background;
        final int accent;
        final int detail;
        final int border;
        final int statusText;

        ThemeColors(int background, int accent, int detail, int border, int statusText) {
            this.background = background;
            this.accent = accent;
            this.detail = detail;
            this.border = border;
            this.statusText = statusText;
        }
    }

    private static final class AppSettings {
        final String language;
        final boolean keepScreenOn;
        final boolean autoSync;
        final int layoutMode;
        final int textSize;
        final int spacing;
        final boolean showDate;
        final boolean showDoy;
        final boolean showWeekday;
        final int colorTheme;
        final boolean burnInProtection;

        AppSettings(
                String language,
                boolean keepScreenOn,
                boolean autoSync,
                int layoutMode,
                int textSize,
                int spacing,
                boolean showDate,
                boolean showDoy,
                boolean showWeekday,
                int colorTheme,
                boolean burnInProtection) {
            this.language = language;
            this.keepScreenOn = keepScreenOn;
            this.autoSync = autoSync;
            this.layoutMode = layoutMode;
            this.textSize = textSize;
            this.spacing = spacing;
            this.showDate = showDate;
            this.showDoy = showDoy;
            this.showWeekday = showWeekday;
            this.colorTheme = colorTheme;
            this.burnInProtection = burnInProtection;
        }
    }

    private static final class ClockEntry {
        final boolean enabled;
        final String label;
        final int offsetMinutes;

        ClockEntry(boolean enabled, String label, int offsetMinutes) {
            this.enabled = enabled;
            this.label = label;
            this.offsetMinutes = normalizeOffsetMinutes(offsetMinutes);
        }
    }

    private static final class ClockDraft {
        boolean enabled;
        String label;
        int offsetMinutes;

        ClockDraft(boolean enabled, String label, int offsetMinutes) {
            this.enabled = enabled;
            this.label = label;
            this.offsetMinutes = normalizeOffsetMinutes(offsetMinutes);
        }
    }

    private static final class ClockEditor {
        final ClockDraft draft;
        final CheckBox enabled;
        final EditText label;
        final Spinner offset;

        ClockEditor(ClockDraft draft, CheckBox enabled, EditText label, Spinner offset) {
            this.draft = draft;
            this.enabled = enabled;
            this.label = label;
            this.offset = offset;
        }
    }

    private final class ClockPanel extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint code = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint statusDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector gestures;

        ClockPanel(Context context) {
            super(context);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            code.setTypeface(android.graphics.Typeface.MONOSPACE);
            code.setFakeBoldText(true);
            time.setTypeface(android.graphics.Typeface.MONOSPACE);
            time.setFakeBoldText(true);
            time.setTextAlign(Paint.Align.CENTER);
            detail.setTypeface(android.graphics.Typeface.MONOSPACE);
            detail.setTextAlign(Paint.Align.CENTER);
            status.setTypeface(android.graphics.Typeface.MONOSPACE);
            status.setTextAlign(Paint.Align.LEFT);
            statusDot.setStyle(Paint.Style.FILL);
            applyTheme();

            gestures = new GestureDetector(
                    context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent event) {
                            showSettings();
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent event) {
                            synchronizeTime();
                            return true;
                        }
                    });
        }

        void applyTheme() {
            ThemeColors theme = currentTheme();
            setBackgroundColor(theme.background);
            border.setColor(theme.border);
            code.setColor(theme.accent);
            time.setColor(theme.accent);
            detail.setColor(theme.detail);
            status.setColor(theme.statusText);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return gestures.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int saveCount = canvas.save();
            if (burnInProtection) {
                long minute = System.currentTimeMillis() / 60000L;
                int phase = (int) (minute % BURN_IN_X.length);
                canvas.translate(dp(BURN_IN_X[phase]), dp(BURN_IN_Y[phase]));
            }

            List<ClockEntry> active = new ArrayList<>();
            for (ClockEntry entry : clocks) {
                if (entry.enabled) {
                    active.add(entry);
                }
            }

            float spacing = spacingScale();
            float margin = Math.max(dp(5), dp(8) * spacing);
            float horizontalGap = Math.max(dp(4), dp(7) * spacing);
            float verticalGap = Math.max(dp(3), dp(6) * spacing);
            float statusHeight = dp(25);
            float contentHeight = getHeight() - margin * 2f - statusHeight;

            if (!active.isEmpty() && contentHeight > dp(40)) {
                int columns = resolveColumns(active.size(), contentHeight);
                int rows = (active.size() + columns - 1) / columns;
                float totalHorizontalGap = horizontalGap * (columns - 1);
                float totalVerticalGap = verticalGap * (rows - 1);
                float cellWidth = (getWidth() - margin * 2f - totalHorizontalGap) / columns;
                float cellHeight = (contentHeight - totalVerticalGap) / rows;

                long nowMillis = System.currentTimeMillis() + timeOffsetMillis;
                Instant now = Instant.ofEpochMilli(nowMillis);

                for (int i = 0; i < active.size(); i++) {
                    int row = i / columns;
                    int column = i % columns;
                    float left = margin + column * (cellWidth + horizontalGap);
                    float top = margin + row * (cellHeight + verticalGap);
                    drawClockCell(
                            canvas,
                            active.get(i),
                            now,
                            left,
                            top,
                            left + cellWidth,
                            top + cellHeight);
                }
            }

            drawSyncStatus(canvas, margin, getHeight() - margin - statusHeight / 2f);
            canvas.restoreToCount(saveCount);
        }

        private int resolveColumns(int activeCount, float contentHeight) {
            if (layoutMode == LAYOUT_ONE_COLUMN) {
                return 1;
            }
            if (layoutMode == LAYOUT_TWO_COLUMNS) {
                return 2;
            }

            float density = getResources().getDisplayMetrics().density;
            float widthDp = getWidth() / density;
            boolean landscape = getWidth() > contentHeight * 1.20f;
            boolean wideDevice = widthDp >= 600f;
            boolean enoughCellWidth = widthDp / 2f >= 210f;
            return activeCount > 4 && enoughCellWidth && (landscape || wideDevice) ? 2 : 1;
        }

        private void drawClockCell(
                Canvas canvas,
                ClockEntry entry,
                Instant now,
                float left,
                float top,
                float right,
                float bottom) {
            float width = right - left;
            float height = bottom - top;
            float innerPadding = Math.max(dp(5), height * 0.08f);
            float labelWidth = Math.max(dp(64), width * 0.20f);
            labelWidth = Math.min(labelWidth, width * 0.31f);
            float dividerX = left + labelWidth;

            canvas.drawRect(left, top, right, bottom, border);
            canvas.drawLine(
                    dividerX,
                    top + innerPadding,
                    dividerX,
                    bottom - innerPadding,
                    border);

            boolean hasDetail = showDate || showDoy || showWeekday;
            float scale = textScale();
            float timeAreaWidth = Math.max(dp(100), right - dividerX - dp(4));
            float codeSize = Math.min(height * 0.34f * scale, labelWidth * 0.29f);
            float timeHeightFactor = hasDetail ? 0.47f : 0.62f;
            float timeSize = Math.min(
                    height * timeHeightFactor * scale,
                    timeAreaWidth / 8f * 1.47f);
            float detailSize = Math.min(
                    height * 0.15f * scale,
                    timeAreaWidth / 24f * 1.45f);

            code.setTextSize(Math.max(dp(12), codeSize));
            time.setTextSize(Math.max(dp(17), timeSize));
            detail.setTextSize(Math.max(dp(8), detailSize));

            Paint.FontMetrics codeMetrics = code.getFontMetrics();
            float codeY = top + height / 2f
                    - (codeMetrics.ascent + codeMetrics.descent) / 2f;
            canvas.drawText(entry.label, left + dp(10), codeY, code);

            ZoneOffset offset = ZoneOffset.ofTotalSeconds(entry.offsetMinutes * 60);
            OffsetDateTime localTime = now.atOffset(offset);
            String formattedTime = localTime.format(TIME_FORMAT);
            float centerX = dividerX + (right - dividerX) / 2f;

            if (!hasDetail) {
                Paint.FontMetrics timeMetrics = time.getFontMetrics();
                float timeY = top + height / 2f
                        - (timeMetrics.ascent + timeMetrics.descent) / 2f;
                canvas.drawText(formattedTime, centerX, timeY, time);
                return;
            }

            String dateDetail = buildDateDetail(localTime);
            Paint.FontMetrics timeMetrics = time.getFontMetrics();
            Paint.FontMetrics detailMetrics = detail.getFontMetrics();
            float timeTextHeight = timeMetrics.descent - timeMetrics.ascent;
            float detailTextHeight = detailMetrics.descent - detailMetrics.ascent;
            float lineGap = Math.max(dp(1), height * 0.025f);
            float groupHeight = timeTextHeight + lineGap + detailTextHeight;
            float groupTop = top + (height - groupHeight) / 2f;
            float timeY = groupTop - timeMetrics.ascent;
            float detailY = groupTop + timeTextHeight + lineGap - detailMetrics.ascent;

            canvas.drawText(formattedTime, centerX, timeY, time);
            canvas.drawText(dateDetail, centerX, detailY, detail);
        }

        private void drawSyncStatus(Canvas canvas, float margin, float centerY) {
            String text = getSyncStatusText();
            float dotRadius = dp(4);
            float dotX = margin + dotRadius;
            statusDot.setColor(getSyncStatusColor());
            canvas.drawCircle(dotX, centerY, dotRadius, statusDot);

            float textX = dotX + dotRadius + dp(7);
            float availableWidth = getWidth() - margin - textX;
            float textSize = dp(11);
            status.setTextSize(textSize);
            while (status.measureText(text) > availableWidth && textSize > dp(8)) {
                textSize -= 0.5f;
                status.setTextSize(textSize);
            }

            Paint.FontMetrics metrics = status.getFontMetrics();
            float textY = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(text, textX, textY, status);
        }
    }
}
