package ru.oppodesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Экран на телефоне: состояние подключения, настройки и тачпад. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button enable;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextIsSelectable(true);
        root.addView(status);

        enable = new Button(this);
        enable.setText("Включить службу OppoDesk");
        enable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable);

        Button start = new Button(this);
        start.setText("Открыть рабочий стол на мониторе");
        start.setOnClickListener(v -> startDesktop());
        root.addView(start);

        Button diagnostics = new Button(this);
        diagnostics.setText("Диагностика ColorOS и монитора");
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        root.addView(diagnostics);

        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Мелко", "Средне", "Крупно"};
        final int[] iconSizes = {40, 64, 96};
        for (int k = 0; k < names.length; k++) {
            final int iconSize = iconSizes[k];
            Button sb = new Button(this);
            sb.setText(names[k]);
            sb.setOnClickListener(v -> {
                getSharedPreferences(Apps.PREFS, MODE_PRIVATE).edit()
                        .putInt(Apps.KEY_ICON_SIZE, iconSize).apply();
                DesktopActivity.refreshIcons();
            });
            scale.addView(sb, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(scale);

        Button choose = new Button(this);
        choose.setText("Выбрать приложения для монитора");
        choose.setOnClickListener(v -> chooseApps());
        root.addView(choose);

        TextView hint = new TextView(this);
        hint.setText("Кнопки выше — размер значков рабочего стола на мониторе. Тачпад: касание — клик, удержание или касание двумя пальцами — "
                + "долгое нажатие, два пальца — прокрутка. Панель задач появляется, "
                + "когда курсор упирается в нижний край монитора");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setPadding(0, dp(8), 0, dp(8));
        root.addView(hint);

        root.addView(new Touchpad(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        back.setText("◀ Назад");
        back.setOnClickListener(v -> { DeskService s = DeskService.instance; if (s != null) s.back(); });
        Button home = new Button(this);
        home.setText("▦ Пуск");
        home.setOnClickListener(v -> {
            DeskService s = DeskService.instance;
            if (s != null) s.toggleStartMenu("phone");
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(back, half);
        row.addView(home, half);
        root.addView(row);
        // Чёрная заглушка поверх всего: «гасит» экран телефона, не усыпляя сам телефон
        // (если телефон уснёт, погаснет и монитор).
        black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        black.setVisibility(View.GONE);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root);
        frame.addView(black, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    /** Список всех приложений с галочками: отмеченные будут видны на рабочем столе. */
    private void chooseApps() {
        final List<Apps.Entry> all = Apps.load(this);
        final Set<String> chosen = Apps.selected(this);
        final String[] names = new String[all.size()];
        final boolean[] checked = new boolean[all.size()];
        for (int k = 0; k < names.length; k++) {
            names[k] = all.get(k).label;
            checked[k] = chosen.contains(Apps.key(all.get(k)));
        }
        new AlertDialog.Builder(this)
                .setTitle("Приложения на мониторе")
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Set<String> keys = new HashSet<>();
                    for (int k = 0; k < checked.length; k++) {
                        if (checked[k]) keys.add(Apps.key(all.get(k)));
                    }
                    Apps.saveSelected(this, keys);
                    DesktopActivity.refresh();
                })
                .setNeutralButton("Показывать все", (d, w) -> {
                    Apps.saveSelected(this, new HashSet<String>());
                    DesktopActivity.refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static final long DIM_AFTER_MS = 3 * 60 * 1000;
    private View black;
    private boolean dimmed, keepOn;
    private final Runnable dim = () -> setDimmed(true);

    /** Любое касание будит экран и заново отсчитывает три минуты. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        handler.removeCallbacks(dim);
        if (keepOn) handler.postDelayed(dim, DIM_AFTER_MS);
        if (dimmed) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) setDimmed(false);
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    private void setDimmed(boolean on) {
        if (on && !keepOn) return;
        dimmed = on;
        black.setVisibility(on ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (on) c.hide(WindowInsets.Type.systemBars());
            else c.show(WindowInsets.Type.systemBars());
        }
    }

    /** Экран держим включённым только пока подключён монитор. */
    private void setKeepOn(boolean on) {
        if (on == keepOn) return;
        keepOn = on;
        handler.removeCallbacks(dim);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.postDelayed(dim, DIM_AFTER_MS);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setDimmed(false);
        }
    }

    private String error = "";

    private void refresh() {
        DeskService s = DeskService.instance;
        Display ext = DeskService.findExternal(getSystemService(DisplayManager.class));
        setKeepOn(ext != null);
        StringBuilder sb = new StringBuilder();
        sb.append(ext != null ? "● Монитор подключён: " + ext.getName()
                : "○ Монитор не найден — подключите кабель");
        sb.append('\n');
        if (s == null) sb.append("○ Служба выключена — без неё нет курсора и панели задач");
        else if (s.hasDisplay()) sb.append("● Служба работает, экран ").append(s.displayInfo());
        else sb.append("● Служба включена, ждёт монитор");
        sb.append('\n');
        if (Sh.ready()) sb.append("● Shizuku готов");
        else if (Sh.alive()) sb.append("○ Shizuku запущен, но не дал разрешение — нажмите кнопку ниже");
        else sb.append("○ Shizuku не запущен — откройте его и нажмите «Запуск»");
        if (ext != null) sb.append("\nЭкран №").append(ext.getDisplayId());
        if (!Sh.lastError.isEmpty()) sb.append('\n').append(Sh.lastError);
        if (s != null && !s.lastError.isEmpty()) sb.append('\n').append(s.lastError);
        if (!error.isEmpty()) sb.append('\n').append(error);
        status.setText(sb);
        enable.setVisibility(s == null ? View.VISIBLE : View.GONE);
    }

    private void startDesktop() {
        error = "";
        if (Sh.alive() && !Sh.ready()) {
            Sh.requestPermission();
            return;
        }
        DeskService s = DeskService.instance;
        if (s == null) {
            error = "Сначала включите службу OppoDesk";
        } else {
            s.attach("phone.desktop");
            if (s.hasDisplay()) {
                s.applyScale();
                s.showDesktop("phone.desktop");
            }
        }
        refresh();
    }

    /** Поверхность тачпада: переводит касания в команды службе. */
    private final class Touchpad extends View {
        private static final float SPEED = 2.2f;
        private final int slop;
        private float lastX, lastY, travelled;
        private int maxPointers;
        private long downAt;

        Touchpad(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF161B22);
            bg.setStroke(dp(1), 0xFF30363D);
            bg.setCornerRadius(dp(16));
            setBackground(bg);
        }

        private float cx(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
            return s / e.getPointerCount();
        }

        private float cy(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            DeskService s = DeskService.instance;
            if (s == null) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    travelled = 0;
                    maxPointers = 1;
                    downAt = e.getEventTime();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPointers = Math.max(maxPointers, e.getPointerCount());
                    lastX = cx(e);
                    lastY = cy(e);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float nx = cx(e), ny = cy(e);
                    float dx = nx - lastX, dy = ny - lastY;
                    lastX = nx;
                    lastY = ny;
                    travelled += Math.abs(dx) + Math.abs(dy);
                    if (e.getPointerCount() >= 2) s.scroll(dx * SPEED, dy * SPEED);
                    else if (maxPointers == 1) s.moveCursor(dx * SPEED, dy * SPEED);
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    // Пересчитываем центр без поднятого пальца, чтобы курсор не прыгал.
                    int up = e.getActionIndex(), n = 0;
                    float sx = 0, sy = 0;
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        if (i == up) continue;
                        sx += e.getX(i);
                        sy += e.getY(i);
                        n++;
                    }
                    if (n > 0) { lastX = sx / n; lastY = sy / n; }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    if (travelled < slop * 2) {
                        long held = e.getEventTime() - downAt;
                        if (maxPointers >= 2 || held >= 450) s.longClick();
                        else s.click();
                    }
                    break;
            }
            return true;
        }
    }
}
