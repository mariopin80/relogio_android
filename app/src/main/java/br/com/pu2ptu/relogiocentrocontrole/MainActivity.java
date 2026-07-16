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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "clock_config";
    private static final String PREF_LANGUAGE = "language";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String PREF_AUTO_SYNC = "auto_sync";

    private static final int CLOCK_COUNT = 8;
    private static final int MIN_OFFSET_MINUTES = -12 * 60;
    private static final int MAX_OFFSET_MINUTES = 14 * 60;
    private static final int OFFSET_STEP_MINUTES = 15;

    private static final DateTimeFormatter TIME_FORMAT =
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ClockEntry> clocks = new ArrayList<>();
    private ClockPanel panel;
    private volatile long timeOffsetMillis = 0L;
    private volatile boolean syncInProgress = false;
    private String languageCode = "en";
    private boolean keepScreenOn = true;
    private boolean autoSync = true;

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

    private boolean saveConfig(
            List<ClockEntry> entries,
            String selectedLanguage,
            boolean selectedKeepScreenOn,
            boolean selectedAutoSync) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(PREF_LANGUAGE, normalizeLanguageCode(selectedLanguage));
        editor.putBoolean(PREF_KEEP_SCREEN_ON, selectedKeepScreenOn);
        editor.putBoolean(PREF_AUTO_SYNC, selectedAutoSync);

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

        TextView languageLabel = new TextView(this);
        languageLabel.setText(R.string.language_label);
        languageLabel.setPadding(0, dp(6), 0, dp(4));
        root.addView(languageLabel);

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

        addSectionTitle(root, getString(R.string.clocks_section));

        TextView help = new TextView(this);
        help.setText(R.string.settings_help);
        help.setPadding(0, dp(6), 0, dp(12));
        root.addView(help);

        List<String> offsetLabels = buildOffsetLabels();
        final CheckBox[] enabled = new CheckBox[CLOCK_COUNT];
        final EditText[] labels = new EditText[CLOCK_COUNT];
        final Spinner[] offsets = new Spinner[CLOCK_COUNT];

        for (int i = 0; i < CLOCK_COUNT; i++) {
            ClockEntry entry = clocks.get(i);

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(4), 0, dp(12));

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);

            enabled[i] = new CheckBox(this);
            enabled[i].setChecked(entry.enabled);
            enabled[i].setText(getString(R.string.clock_number, i + 1));
            header.addView(enabled[i], new LinearLayout.LayoutParams(0, dp(48), 1f));

            labels[i] = new EditText(this);
            labels[i].setSingleLine(true);
            labels[i].setText(entry.label);
            labels[i].setAllCaps(true);
            labels[i].setSelectAllOnFocus(true);
            labels[i].setHint(R.string.label_hint);
            labels[i].setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            header.addView(labels[i], new LinearLayout.LayoutParams(dp(92), dp(48)));
            block.addView(header);

            TextView offsetLabel = new TextView(this);
            offsetLabel.setText(R.string.utc_offset);
            offsetLabel.setPadding(dp(4), 0, 0, 0);
            block.addView(offsetLabel);

            offsets[i] = new Spinner(this);
            ArrayAdapter<String> offsetAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    offsetLabels);
            offsetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            offsets[i].setAdapter(offsetAdapter);
            offsets[i].setSelection(positionForOffset(entry.offsetMinutes));
            block.addView(offsets[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)));

            root.addView(block);
        }

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
                List<ClockEntry> updated = new ArrayList<>();
                boolean anyEnabled = false;

                for (int i = 0; i < CLOCK_COUNT; i++) {
                    String label = labels[i].getText().toString()
                            .trim()
                            .toUpperCase(Locale.ROOT);
                    if (label.isEmpty()) {
                        label = "C" + (i + 1);
                    }

                    int offsetMinutes = offsetForPosition(offsets[i].getSelectedItemPosition());
                    boolean isEnabled = enabled[i].isChecked();
                    anyEnabled |= isEnabled;
                    updated.add(new ClockEntry(isEnabled, label, offsetMinutes));
                }

                if (!anyEnabled) {
                    Toast.makeText(this, R.string.enable_one_clock, Toast.LENGTH_LONG).show();
                    return;
                }

                int languageIndex = languageSpinner.getSelectedItemPosition();
                String selectedLanguage = languageIndex >= 0 && languageIndex < LANGUAGE_CODES.length
                        ? LANGUAGE_CODES[languageIndex]
                        : "en";

                if (!saveConfig(
                        updated,
                        selectedLanguage,
                        keepScreenOnCheck.isChecked(),
                        autoSyncCheck.isChecked())) {
                    Toast.makeText(this, R.string.save_error, Toast.LENGTH_LONG).show();
                    return;
                }

                loadConfig();
                applyScreenOnPreference();

                boolean languageChanged = !previousLanguage.equals(languageCode);
                boolean autoSyncEnabledNow = !previousAutoSync && autoSync;
                boolean autoSyncDisabledNow = previousAutoSync && !autoSync;
                if (autoSyncDisabledNow) {
                    timeOffsetMillis = 0L;
                }

                dialog.dismiss();

                if (languageChanged) {
                    recreate();
                    return;
                }

                if (panel != null) {
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

    private void addSectionTitle(LinearLayout root, String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(18f);
        text.setTextColor(Color.rgb(255, 50, 31));
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setPadding(0, dp(4), 0, dp(4));
        root.addView(text);
    }

    private void synchronizeTime() {
        if (syncInProgress) {
            Toast.makeText(this, R.string.synchronizing, Toast.LENGTH_SHORT).show();
            return;
        }

        syncInProgress = true;
        Toast.makeText(this, R.string.synchronizing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Long offset = queryNtp("time.cloudflare.com");
            String method = "NTP";
            if (offset == null) {
                offset = queryNtp("pool.ntp.org");
            }
            if (offset == null) {
                offset = queryHttps("https://www.google.com/generate_204");
                method = "HTTPS";
            }
            final Long result = offset;
            final String source = method;
            handler.post(() -> {
                syncInProgress = false;
                if (result != null) {
                    timeOffsetMillis = result;
                    Toast.makeText(
                            this,
                            getString(R.string.sync_success, source),
                            Toast.LENGTH_SHORT).show();
                } else {
                    timeOffsetMillis = 0L;
                    Toast.makeText(this, R.string.sync_failure, Toast.LENGTH_LONG).show();
                }
            });
        }, "time-sync").start();
    }

    private static Long queryNtp(String host) {
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
            return serverMillis - ((t1 + t4) / 2L);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long queryHttps(String address) {
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
            return serverMillis > 0 ? serverMillis - ((t1 + t4) / 2L) : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private final class ClockPanel extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint code = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector gestures;

        ClockPanel(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(15, 15, 15));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            border.setColor(Color.rgb(88, 88, 88));
            code.setColor(Color.rgb(255, 50, 31));
            code.setTypeface(android.graphics.Typeface.MONOSPACE);
            code.setFakeBoldText(true);
            time.setColor(Color.rgb(255, 50, 31));
            time.setTypeface(android.graphics.Typeface.MONOSPACE);
            time.setFakeBoldText(true);
            time.setTextAlign(Paint.Align.CENTER);

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

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return gestures.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            List<ClockEntry> active = new ArrayList<>();
            for (ClockEntry entry : clocks) {
                if (entry.enabled) {
                    active.add(entry);
                }
            }
            if (active.isEmpty()) {
                return;
            }

            float margin = dp(8);
            float gap = dp(6);
            float totalHeight = getHeight() - margin * 2f - gap * (active.size() - 1);
            float rowHeight = totalHeight / active.size();
            float labelWidth = Math.max(dp(92), getWidth() * 0.17f);
            labelWidth = Math.min(labelWidth, getWidth() * 0.30f);

            long nowMillis = System.currentTimeMillis() + timeOffsetMillis;
            Instant now = Instant.ofEpochMilli(nowMillis);

            for (int i = 0; i < active.size(); i++) {
                ClockEntry entry = active.get(i);
                float top = margin + i * (rowHeight + gap);
                float bottom = top + rowHeight;

                canvas.drawRect(margin, top, getWidth() - margin, bottom, border);
                canvas.drawLine(
                        labelWidth,
                        top + dp(8),
                        labelWidth,
                        bottom - dp(8),
                        border);

                float codeSize = Math.min(rowHeight * 0.34f, labelWidth * 0.30f);
                float timeAreaWidth = Math.max(dp(120), getWidth() - labelWidth - margin);
                float timeSize = Math.min(rowHeight * 0.62f, timeAreaWidth / 8f * 1.50f);
                code.setTextSize(Math.max(dp(16), codeSize));
                time.setTextSize(Math.max(dp(22), timeSize));

                Paint.FontMetrics codeMetrics = code.getFontMetrics();
                Paint.FontMetrics timeMetrics = time.getFontMetrics();
                float codeY = top + rowHeight / 2f
                        - (codeMetrics.ascent + codeMetrics.descent) / 2f;
                float timeY = top + rowHeight / 2f
                        - (timeMetrics.ascent + timeMetrics.descent) / 2f;

                canvas.drawText(entry.label, dp(18), codeY, code);

                String formatted;
                try {
                    ZoneOffset offset = ZoneOffset.ofTotalSeconds(entry.offsetMinutes * 60);
                    formatted = now.atOffset(offset).format(TIME_FORMAT);
                } catch (Exception ex) {
                    formatted = "--:--:--";
                }

                float centerX = labelWidth + (getWidth() - labelWidth) / 2f;
                canvas.drawText(formatted, centerX, timeY, time);
            }
        }
    }
}
