package ru.oppodesk;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

/** Read-only diagnostics: never executes shell commands or changes device settings. */
public final class DiagnosticsActivity extends Activity {
    private TextView output;
    private String report = "";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xFF0D1117);
        TextView title = new TextView(this);
        title.setText("OppoDesk Diagnostics");
        title.setTextColor(-1);
        title.setTextSize(22);
        root.addView(title);
        Button reload = new Button(this);
        reload.setText("Обновить диагностику");
        reload.setOnClickListener(v -> refresh());
        root.addView(reload);
        Button copy = new Button(this);
        copy.setText("Скопировать отчёт");
        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("OppoDesk diagnostics", report));
                Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy);
        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextColor(0xFFE6EDF3);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refresh();
    }
    @Override protected void onResume() {
        super.onResume();
        if (output != null) refresh();
    }
    private void refresh() {
        StringBuilder s = new StringBuilder();
        s.append("OppoDesk 0.9-dev2 | Только чтение\n");
        s.append("Дата: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n\n");
        s.append("УСТРОЙСТВО\n");
        s.append("Производитель: ").append(Build.MANUFACTURER).append("\n");
        s.append("Модель: ").append(Build.MODEL).append("\n");
        s.append("Устройство: ").append(Build.DEVICE).append("\n");
        s.append("Android: ").append(Build.VERSION.RELEASE).append(" API ")
                .append(Build.VERSION.SDK_INT).append("\n");
        s.append("Сборка: ").append(Build.DISPLAY).append("\n\n");
        s.append("ДИСПЛЕИ\n");
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm == null) s.append("DisplayManager недоступен\n");
        else {
            Display[] displays = dm.getDisplays();
            s.append("Количество: ").append(displays.length).append("\n");
            for (Display d : displays) {
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                s.append("ID ").append(d.getDisplayId()).append(": ").append(d.getName()).append("\n");
                s.append(m.widthPixels).append("x").append(m.heightPixels)
                        .append(" DPI=").append(m.densityDpi).append("\n");
                s.append("flags=0x").append(Integer.toHexString(d.getFlags()))
                        .append(" state=").append(d.getState()).append("\n");
                s.append("private=").append((d.getFlags() & Display.FLAG_PRIVATE) != 0)
                        .append(" presentation=").append((d.getFlags() & Display.FLAG_PRESENTATION) != 0)
                        .append("\n");
            }
            Display ext = DeskService.findExternal(dm);
            s.append("Внешний: ").append(ext == null ? "не найден" : ext.getDisplayId()).append("\n");
        }
        s.append("\nСЛУЖБЫ\n");
        DeskService service = DeskService.instance;
        s.append("Accessibility: ").append(service != null).append("\n");
        s.append("Overlay: ").append(service != null && service.hasDisplay()).append("\n");
        if (service != null && !service.lastError.isEmpty())
            s.append("Ошибка службы: ").append(service.lastError).append("\n");
        try {
            s.append("Shizuku запущен: ").append(Sh.alive()).append("\n");
            s.append("Shizuku разрешён: ").append(Sh.ready()).append("\n");
        } catch (Throwable e) {
            s.append("Ошибка проверки Shizuku: ").append(e.getClass().getSimpleName()).append("\n");
        }
        if (!Sh.lastError.isEmpty()) s.append("Shizuku ошибка: ").append(Sh.lastError).append("\n");
        s.append("Последняя shell-команда: ").append(Sh.lastResult).append("\n");
        s.append("\nВОЗМОЖНОСТИ\n");
        PackageManager pm = getPackageManager();
        s.append("Freeform: ").append(pm.hasSystemFeature("android.software.freeform_window_management")).append("\n");
        s.append("Picture-in-Picture: ").append(pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)).append("\n");
        s.append("Режим разработчика: ");
        try {
            s.append(Settings.Global.getInt(getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1);
        } catch (SecurityException e) { s.append("нет доступа"); }
        s.append("\n\nСистемные признаки не гарантируют поддержку окон ColorOS.\n");
        report = s.toString();
        output.setText(report);
    }
}
