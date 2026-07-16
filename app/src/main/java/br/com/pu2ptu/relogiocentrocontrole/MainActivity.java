package br.com.pu2ptu.relogiocentrocontrole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "clock_config";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final String[] DEFAULT_LABELS = {"UTC", "BRT", "NYC", "LON", "JST"};
    private static final String[] DEFAULT_ZONES = {
            "UTC", "America/Sao_Paulo", "America/New_York", "Europe/London", "Asia/Tokyo"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ClockEntry> clocks = new ArrayList<>();
    private ClockPanel panel;
    private volatile long timeOffsetMillis = 0L;

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
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadConfig();
        panel = new ClockPanel(this);
        setContentView(panel);
        enterImmersiveMode();
        handler.post(tick);
        synchronizeTime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
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

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        clocks.clear();
        for (int i = 0; i < 5; i++) {
            clocks.add(new ClockEntry(
                    prefs.getBoolean("enabled_" + i, true),
                    prefs.getString("label_" + i, DEFAULT_LABELS[i]),
                    prefs.getString("zone_" + i, DEFAULT_ZONES[i])));
        }
    }

    private boolean saveConfig(List<ClockEntry> entries) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < entries.size(); i++) {
            ClockEntry entry = entries.get(i);
            editor.putBoolean("enabled_" + i, entry.enabled);
            editor.putString("label_" + i, entry.label);
            editor.putString("zone_" + i, entry.zoneId);
        }
        return editor.commit();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView help = new TextView(this);
        help.setText("Ative até cinco relógios, informe uma sigla de até 3 letras e escolha o fuso horário.");
        help.setPadding(0, 0, 0, dp(12));
        root.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        List<String> zoneIds = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(zoneIds);

        final CheckBox[] enabled = new CheckBox[5];
        final EditText[] labels = new EditText[5];
        final Spinner[] zones = new Spinner[5];

        for (int i = 0; i < 5; i++) {
            ClockEntry entry = clocks.get(i);

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(4), 0, dp(12));

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);

            enabled[i] = new CheckBox(this);
            enabled[i].setChecked(entry.enabled);
            enabled[i].setText("Relógio " + (i + 1));
            header.addView(enabled[i], new LinearLayout.LayoutParams(0, dp(48), 1f));

            labels[i] = new EditText(this);
            labels[i].setSingleLine(true);
            labels[i].setText(entry.label);
            labels[i].setAllCaps(true);
            labels[i].setSelectAllOnFocus(true);
            labels[i].setHint("SIG");
            labels[i].setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            header.addView(labels[i], new LinearLayout.LayoutParams(dp(92), dp(48)));
            block.addView(header);

            zones[i] = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    zoneIds);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            zones[i].setAdapter(adapter);
            int position = zoneIds.indexOf(entry.zoneId);
            zones[i].setSelection(Math.max(position, 0));
            block.addView(zones[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)));

            root.addView(block);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Configurar relógios")
                .setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Sincronizar", null)
                .setPositiveButton("Salvar", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> synchronizeTime());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<ClockEntry> updated = new ArrayList<>();
                boolean anyEnabled = false;

                for (int i = 0; i < 5; i++) {
                    String label = labels[i].getText().toString()
                            .trim()
                            .toUpperCase(Locale.ROOT);
                    if (label.isEmpty()) {
                        label = "C" + (i + 1);
                    }

                    Object selectedZone = zones[i].getSelectedItem();
                    String zoneId = selectedZone == null
                            ? DEFAULT_ZONES[i]
                            : selectedZone.toString();

                    boolean isEnabled = enabled[i].isChecked();
                    anyEnabled |= isEnabled;
                    updated.add(new ClockEntry(isEnabled, label, zoneId));
                }

                if (!anyEnabled) {
                    Toast.makeText(
                            this,
                            "Ative pelo menos um relógio.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (!saveConfig(updated)) {
                    Toast.makeText(
                            this,
                            "Não foi possível gravar as configurações.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                loadConfig();
                if (panel != null) {
                    panel.invalidate();
                    panel.requestLayout();
                }
                Toast.makeText(this, "Configurações salvas.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });

        dialog.setOnDismissListener(ignored -> enterImmersiveMode());
        dialog.show();
    }

    private void synchronizeTime() {
        Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show();
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
                if (result != null) {
                    timeOffsetMillis = result;
                    Toast.makeText(this, "Sincronizado via " + source, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(
                            this,
                            "Sem sincronização; usando hora do aparelho",
                            Toast.LENGTH_LONG).show();
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ClockEntry {
        final boolean enabled;
        final String label;
        final String zoneId;

        ClockEntry(boolean enabled, String label, String zoneId) {
            this.enabled = enabled;
            this.label = label;
            this.zoneId = zoneId;
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
                    formatted = ZonedDateTime.ofInstant(now, ZoneId.of(entry.zoneId))
                            .format(TIME_FORMAT);
                } catch (Exception ex) {
                    formatted = "--:--:--";
                }

                float centerX = labelWidth + (getWidth() - labelWidth) / 2f;
                canvas.drawText(formatted, centerX, timeY, time);
            }
        }
    }
}
