package br.com.pu2ptu.relogiocentrocontrole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "clock_config";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final String[] DEFAULT_LABELS = {"UTC", "BRT", "NYC", "LON", "JST"};
    private static final String[] DEFAULT_ZONES = {"UTC", "America/Sao_Paulo", "America/New_York", "Europe/London", "Asia/Tokyo"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ClockEntry> clocks = new ArrayList<>();
    private ClockPanel panel;
    private volatile long timeOffsetMillis = 0L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (panel != null) panel.invalidate();
            handler.postDelayed(this, 100L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadConfig();
        panel = new ClockPanel(this);
        setContentView(panel);
        enterImmersiveMode();
        handler.post(tick);
        synchronizeTime();
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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

    private void saveConfig() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < clocks.size(); i++) {
            ClockEntry entry = clocks.get(i);
            editor.putBoolean("enabled_" + i, entry.enabled);
            editor.putString("label_" + i, entry.label);
            editor.putString("zone_" + i, entry.zoneId);
        }
        editor.apply();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        List<String> zoneIds = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(zoneIds);
        final CheckBox[] enabled = new CheckBox[5];
        final EditText[] labels = new EditText[5];
        final Spinner[] zones = new Spinner[5];

        for (int i = 0; i < 5; i++) {
            ClockEntry entry = clocks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(5), 0, dp(5));

            enabled[i] = new CheckBox(this);
            enabled[i].setChecked(entry.enabled);
            row.addView(enabled[i], new LinearLayout.LayoutParams(dp(52), dp(48)));

            labels[i] = new EditText(this);
            labels[i].setSingleLine(true);
            labels[i].setText(entry.label);
            labels[i].setAllCaps(true);
            labels[i].setMaxEms(3);
            row.addView(labels[i], new LinearLayout.LayoutParams(dp(90), dp(52)));

            zones[i] = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, zoneIds);
            zones[i].setAdapter(adapter);
            int position = zoneIds.indexOf(entry.zoneId);
            zones[i].setSelection(Math.max(position, 0));
            row.addView(zones[i], new LinearLayout.LayoutParams(0, dp(52), 1f));
            root.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle("Configurar relógios")
                .setMessage("Toque longo no painel para abrir esta tela.")
                .setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Sincronizar", (d, w) -> synchronizeTime())
                .setPositiveButton("Salvar", (dialog, which) -> {
                    boolean any = false;
                    for (int i = 0; i < 5; i++) {
                        String label = labels[i].getText().toString().trim().toUpperCase(Locale.ROOT);
                        if (label.isEmpty()) label = "C" + (i + 1);
                        if (label.length() > 3) label = label.substring(0, 3);
                        clocks.set(i, new ClockEntry(enabled[i].isChecked(), label, String.valueOf(zones[i].getSelectedItem())));
                        any |= enabled[i].isChecked();
                    }
                    if (!any) clocks.get(0).enabled = true;
                    saveConfig();
                    panel.invalidate();
                })
                .show();
    }

    private void synchronizeTime() {
        Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Long offset = queryNtp("time.cloudflare.com");
            String method = "NTP";
            if (offset == null) offset = queryNtp("pool.ntp.org");
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
                    Toast.makeText(this, "Sem sincronização; usando hora do aparelho", Toast.LENGTH_LONG).show();
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
            long seconds = ((buffer[40] & 0xffL) << 24) | ((buffer[41] & 0xffL) << 16) | ((buffer[42] & 0xffL) << 8) | (buffer[43] & 0xffL);
            long fraction = ((buffer[44] & 0xffL) << 24) | ((buffer[45] & 0xffL) << 16) | ((buffer[46] & 0xffL) << 8) | (buffer[47] & 0xffL);
            long serverMillis = (seconds - 2208988800L) * 1000L + ((fraction * 1000L) >>> 32);
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
            try (InputStream stream = connection.getInputStream()) { stream.read(); }
            long t4 = System.currentTimeMillis();
            long serverMillis = connection.getDate() + 500L;
            return serverMillis > 0 ? serverMillis - ((t1 + t4) / 2L) : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ClockEntry {
        boolean enabled;
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
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public void onLongPress(MotionEvent e) { showSettings(); }
                @Override public boolean onDoubleTap(MotionEvent e) { synchronizeTime(); return true; }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<ClockEntry> active = new ArrayList<>();
            for (ClockEntry entry : clocks) if (entry.enabled) active.add(entry);
            if (active.isEmpty()) return;

            float margin = dp(8);
            float gap = dp(6);
            float totalHeight = getHeight() - margin * 2f - gap * (active.size() - 1);
            float rowHeight = totalHeight / active.size();
            float labelWidth = Math.max(dp(105), getWidth() * 0.12f);
            long nowMillis = System.currentTimeMillis() + timeOffsetMillis;
            Instant now = Instant.ofEpochMilli(nowMillis);

            for (int i = 0; i < active.size(); i++) {
                ClockEntry entry = active.get(i);
                float top = margin + i * (rowHeight + gap);
                float bottom = top + rowHeight;
                canvas.drawRect(margin, top, getWidth() - margin, bottom, border);
                canvas.drawLine(labelWidth, top + dp(8), labelWidth, bottom - dp(8), border);

                float codeSize = Math.min(rowHeight * 0.34f, labelWidth * 0.32f);
                float timeSize = Math.min(rowHeight * 0.62f, (getWidth() - labelWidth) / 8f * 1.55f);
                code.setTextSize(Math.max(dp(18), codeSize));
                time.setTextSize(Math.max(dp(28), timeSize));

                Paint.FontMetrics cf = code.getFontMetrics();
                Paint.FontMetrics tf = time.getFontMetrics();
                float codeY = top + rowHeight / 2f - (cf.ascent + cf.descent) / 2f;
                float timeY = top + rowHeight / 2f - (tf.ascent + tf.descent) / 2f;
                canvas.drawText(entry.label, dp(22), codeY, code);

                String formatted;
                try {
                    formatted = ZonedDateTime.ofInstant(now, ZoneId.of(entry.zoneId)).format(TIME_FORMAT);
                } catch (Exception ex) {
                    formatted = "--:--:--";
                }
                canvas.drawText(formatted, labelWidth + (getWidth() - labelWidth) / 2f, timeY, time);
            }
        }
    }
}
