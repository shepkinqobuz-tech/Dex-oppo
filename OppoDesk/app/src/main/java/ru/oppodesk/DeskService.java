package ru.oppodesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Служба специальных возможностей: рисует на внешнем экране панель задач и курсор,
 * превращает жесты с тачпада телефона в нажатия на внешнем экране.
 */
public class DeskService extends AccessibilityService implements DisplayManager.DisplayListener {
    static DeskService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager dm;
    private WindowManager wm;
    private Context winCtx;
    private int displayId = Display.INVALID_DISPLAY;
    private int width, height, barHeight;
    private float x, y, density = 1f;
    private int dpi;
    private CursorView cursor;
    private LinearLayout bar, recentBox, startMenu;
    private float scrollX, scrollY;
    private boolean gestureBusy;

    @Override
    protected void onServiceConnected() {
        instance = this;
        Apps.restoreRecent(this);
        dm = getSystemService(DisplayManager.class);
        dm.registerDisplayListener(this, handler);
        attach("service.connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        detach("service.unbind");
        if (dm != null) dm.unregisterDisplayListener(this);
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDisplayAdded(int id) {
        TaskTrace.event("display.added id=" + id, displayId);
        attach("display.added");
    }
    @Override public void onDisplayChanged(int id) {
        if (!hasDisplay()) {
            attach("display.changed id=" + id);
            return;
        }
        if (id != displayId) return;
        Display d = dm.getDisplay(id);
        if (d == null) return;
        TaskTrace.event("display.changed id=" + id + " state=" + d.getState(), displayId);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        if (m.densityDpi != dpi || m.widthPixels != width || m.heightPixels != height) {
            // Размер или плотность экрана изменились — панель и курсор надо построить заново.
            String change = "display.changed " + width + "x" + height + "@" + dpi
                    + " -> " + m.widthPixels + "x" + m.heightPixels + "@" + m.densityDpi;
            detach(change);
            attach(change);
        }
    }
    @Override public void onDisplayRemoved(int id) {
        TaskTrace.event("display.removed id=" + id, displayId);
        if (id == displayId) detach("display.removed");
    }

    boolean hasDisplay() { return displayId != Display.INVALID_DISPLAY; }
    int displayId() { return displayId; }
    String displayInfo() { return width + "×" + height; }

    /** Область экрана над панелью задач. */
    Rect workArea() { return new Rect(0, 0, width, height - barHeight); }

    static Display findExternal(DisplayManager dm) {
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            if (d.getState() == Display.STATE_OFF) continue;
            return d;
        }
        return null;
    }

    private int dp(float v) { return Math.round(v * density); }

    /** Текст последней ошибки — показывается на экране телефона для диагностики. */
    String lastError = "";

    private Context candidate(Display d, int n) {
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        switch (n) {
            case 0: return createDisplayContext(d);
            case 1: return createWindowContext(d, type, null);
            default: return createDisplayContext(d).createWindowContext(type, null);
        }
    }

    void attach(String source) {
        if (hasDisplay()) return;
        Display d = findExternal(dm);
        if (d == null) return;
        TaskTrace.event("attach.begin " + source, d.getDisplayId());
        StringBuilder errors = new StringBuilder();
        for (int n = 0; n < 3; n++) {
            try {
                winCtx = candidate(d, n);
                wm = (WindowManager) winCtx.getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                density = m.density;
                dpi = m.densityDpi;
                width = m.widthPixels;
                height = m.heightPixels;
                barHeight = dp(48);
                x = width / 2f;
                y = height / 2f;
                addBar();
                addCursor();
                displayId = d.getDisplayId();
                TaskTrace.event("attach.ready " + source + " " + width + "x" + height + "@" + dpi,
                        displayId);
                lastError = "";
                applyScale();
                showDesktop("attach " + source);
                return;
            } catch (Throwable e) {
                errors.append("\n[").append(n).append("] ").append(e);
                cleanup();
            }
        }
        lastError = "Не удалось вывести панель на монитор:" + errors;
        TaskTrace.event("attach.failed " + source, d.getDisplayId());
    }

    private void cleanup() {
        closeStartMenu("detach");
        if (wm != null) {
            try { if (bar != null) wm.removeView(bar); } catch (Exception ignored) {}
            try { if (cursor != null) wm.removeView(cursor); } catch (Exception ignored) {}
        }
        bar = null;
        recentBox = null;
        cursor = null;
        wm = null;
    }

    private void detach(String source) {
        TaskTrace.event("detach " + source, displayId);
        cleanup();
        displayId = Display.INVALID_DISPLAY;
    }

    // ---------- панель задач ----------

    private TextView barButton(String text, View.OnClickListener l) {
        TextView t = new TextView(winCtx);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setOnClickListener(l);
        return t;
    }

    private void addBar() {
        bar = new LinearLayout(winCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xF01B1F27);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        bar.addView(barButton("▦  Пуск", v -> toggleStartMenu("monitor")), full);
        bar.addView(barButton("◀", v -> back()), full);

        recentBox = new LinearLayout(winCtx);
        recentBox.setOrientation(LinearLayout.HORIZONTAL);
        recentBox.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView recentScroll = new HorizontalScrollView(winCtx);
        recentScroll.setHorizontalScrollBarEnabled(false);
        recentScroll.setFillViewport(false);
        recentScroll.addView(recentBox);
        bar.addView(recentScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView clear = barButton("Очистить", v -> Apps.clearRecent(this));
        clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bar.addView(clear);

        TextClock clock = new TextClock(winCtx);
        clock.setFormat24Hour("HH:mm   dd.MM");
        clock.setFormat12Hour("HH:mm   dd.MM");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        clock.setPadding(dp(16), 0, dp(20), 0);
        bar.addView(clock);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, barHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(bar, lp);
        refreshRecent();
    }

    void refreshRecent() {
        if (recentBox == null) return;
        recentBox.removeAllViews();
        int size = dp(30), pad = dp(5);
        for (final Apps.Entry e : Apps.recent) {
            LinearLayout shortcut = new LinearLayout(winCtx);
            shortcut.setOrientation(LinearLayout.VERTICAL);
            shortcut.setGravity(Gravity.CENTER);
            shortcut.setPadding(dp(5), dp(2), dp(5), dp(2));
            ImageView iv = new ImageView(winCtx);
            iv.setImageDrawable(e.icon);
            iv.setContentDescription("Переключиться: " + e.label);
            shortcut.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView name = new TextView(winCtx);
            name.setText(e.label);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(100));
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            name.setGravity(Gravity.CENTER);
            shortcut.addView(name);
            shortcut.setOnClickListener(v -> launch(e));
            recentBox.addView(shortcut, new LinearLayout.LayoutParams(
                    dp(110), LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    /** Переключает только существующую задачу на мониторе. */
    void launch(Apps.Entry e) {
        if (!hasDisplay()) {
            lastError = "Внешний монитор не подключён";
            return;
        }
        lastError = "";
        // Focus existing external tasks only; desktop launches remain unchanged.
        if (Sh.ready()) {
            Apps.focusExisting(e, displayId);
        } else {
            lastError = "Shizuku не готов: переключение на монитор недоступно";
        }
    }

    private boolean closeStartMenu(String source) {
        if (startMenu == null) return false;
        boolean removed = false;
        if (wm != null) {
            try {
                wm.removeView(startMenu);
                removed = true;
            } catch (Exception ex) {
                TaskTrace.event("start.remove failed " + ex.getClass().getSimpleName(), displayId);
            }
        }
        startMenu = null;
        TaskTrace.event("start.close " + source + " removed=" + removed, displayId);
        return removed;
    }

    private void menuCheckpoint(String label) {
        TaskTrace.Session trace = TaskTrace.active();
        if (trace == null) return;
        int target = displayId;
        TaskTrace.event(label, target);
        handler.postDelayed(() -> trace.after(label, target), 350);
    }

    void toggleStartMenu(String source) {
        TaskTrace.event("start.toggle " + source, displayId);
        if (!hasDisplay() || wm == null) return;
        if (startMenu != null) {
            if (closeStartMenu(source)) menuCheckpoint("после закрытия Пуска " + source);
            return;
        }
        try {
            LinearLayout menu = new LinearLayout(winCtx);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setPadding(dp(12), dp(8), dp(12), dp(8));
            menu.setBackgroundColor(0xFF202630);
            menu.addView(barButton("Приложения   ·   Закрыть ×", v -> {
                if (closeStartMenu("button")) menuCheckpoint("после закрытия Пуска button");
            }),
                    new LinearLayout.LayoutParams(-1, dp(44)));
            android.widget.ScrollView scroll = new android.widget.ScrollView(winCtx);
            LinearLayout list = new LinearLayout(winCtx);
            list.setOrientation(LinearLayout.VERTICAL);
            java.util.Set<String> selected = Apps.selected(this);
            for (Apps.Entry e : Apps.load(this)) {
                if (!selected.isEmpty() && !selected.contains(Apps.key(e))) continue;
                TextView item = barButton(e.label, v -> {
                    TaskTrace.event("start.select " + Apps.key(e), displayId);
                    closeStartMenu("selection");
                    if (hasDisplay() && Sh.ready()) {
                        Apps.launch(e, displayId, "start " + source);
                    } else {
                        lastError = "Shizuku не готов или монитор отключён";
                        TaskTrace.event("start.launch skipped " + lastError, displayId);
                    }
                });
                item.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                item.setCompoundDrawablesWithIntrinsicBounds(e.icon, null, null, null);
                item.setCompoundDrawablePadding(dp(10));
                list.addView(item, new LinearLayout.LayoutParams(-1, dp(56)));
            }
            scroll.addView(list);
            menu.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.min(dp(340), width), Math.min(dp(460), height - barHeight),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            lp.y = barHeight;
            wm.addView(menu, lp);
            startMenu = menu;
            TaskTrace.event("start.overlay added", displayId);
            if (bar != null) bar.setVisibility(View.VISIBLE);
            // Keep the cursor above the newly added menu.
            if (cursor != null) { wm.removeView(cursor); cursor = null; addCursor(); }
            menuCheckpoint("после открытия Пуска " + source);
        } catch (Exception ex) {
            closeStartMenu("error");
            lastError = "Ошибка меню Пуск: " + ex;
            TaskTrace.event("start.failed " + ex.getClass().getSimpleName(), displayId);
            menuCheckpoint("после ошибки Пуска " + source);
        }
    }

    /** Рабочий стол открывается на телефоне и сам переносит себя на монитор. */
    void showDesktop(String source) {
        if (!hasDisplay()) return;
        TaskTrace.event("desktop.show " + source, displayId);
        Intent i = new Intent(this, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            lastError = "Не удалось открыть рабочий стол:\n" + e;
        }
    }

    /** Запоминает и применяет размер интерфейса на мониторе (плотность в dpi). */
    void setScale(int value) {
        getSharedPreferences(Apps.PREFS, MODE_PRIVATE).edit().putInt(Apps.KEY_DPI, value).apply();
        applyScale();
    }

    /**
     * Стандартная плотность внешнего экрана очень мала (128 dpi), из-за чего
     * приложения считают его гигантским планшетом. Задаём свою.
     */
    void applyScale() {
        if (!hasDisplay() || !Sh.ready()) return;
        int value = getSharedPreferences(Apps.PREFS, MODE_PRIVATE)
                .getInt(Apps.KEY_DPI, Apps.DEFAULT_DPI);
        if (value != dpi) Sh.run("attach.density", displayId,
                "wm density " + value + " -d " + displayId);
    }

    void back() {
        if (hasDisplay() && Sh.ready()) Sh.run("input -d " + displayId + " keyevent 4");
        else performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // ---------- курсор и жесты ----------

    private void addCursor() {
        cursor = new CursorView(winCtx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(cursor, lp);
    }

    void moveCursor(float dx, float dy) {
        if (!hasDisplay()) return;
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        if (cursor != null) cursor.invalidate();
        if (bar != null) {
            // Панель задач прячется, чтобы не закрывать низ приложений,
            // и появляется, когда курсор упирается в нижний край экрана.
            if (y >= height - 3) bar.setVisibility(View.VISIBLE);
            else if (startMenu == null && y < height - barHeight - dp(8)) bar.setVisibility(View.GONE);
        }
    }

    void click() { press(50); }
    void longClick() { press(700); }

    private void press(long ms) {
        if (!hasDisplay()) return;
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(p, ms);
    }

    /** Прокрутка двумя пальцами: копим смещение и отправляем короткими свайпами. */
    void scroll(float dx, float dy) {
        if (!hasDisplay()) return;
        scrollX += dx;
        scrollY += dy;
        if (gestureBusy || Math.hypot(scrollX, scrollY) < 24) return;
        float tx = Math.max(1, Math.min(width - 2, x + scrollX));
        float ty = Math.max(1, Math.min(height - barHeight - 2, y + scrollY));
        scrollX = 0;
        scrollY = 0;
        Path p = new Path();
        p.moveTo(x, Math.min(y, height - barHeight - 2));
        p.lineTo(tx, ty);
        dispatch(p, 70);
    }

    private void dispatch(Path p, long ms) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.setDisplayId(displayId);
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, ms));
        gestureBusy = true;
        boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { gestureBusy = false; }
            @Override public void onCancelled(GestureDescription g) { gestureBusy = false; }
        }, handler);
        if (!ok) gestureBusy = false;
    }

    private final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(Context c) {
            super(c);
            float s = dp(1);
            fill.setColor(Color.WHITE);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * s);
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 18 * s);
            arrow.lineTo(4.5f * s, 14 * s);
            arrow.lineTo(8 * s, 21 * s);
            arrow.lineTo(10.5f * s, 20 * s);
            arrow.lineTo(7.5f * s, 13 * s);
            arrow.lineTo(13 * s, 13 * s);
            arrow.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.translate(x, y);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
        }
    }
}
