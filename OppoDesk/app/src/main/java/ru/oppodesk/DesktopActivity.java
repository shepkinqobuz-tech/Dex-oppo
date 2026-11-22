package ru.oppodesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Рабочий стол на внешнем экране: обои, поиск и сетка приложений. */
public class DesktopActivity extends Activity {
    private final List<Apps.Entry> all = new ArrayList<>();
    private final List<Apps.Entry> shown = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static DesktopActivity current;
    private boolean built;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { });
        }
        if (getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY) {
            // Пока окно на телефоне, показываем пустой фон: интерфейс строится
            // уже на мониторе, с его размерами и плотностью.
            View blank = new View(this);
            blank.setBackgroundColor(0xFF0D1117);
            setContentView(blank);
        } else {
            buildUi();
        }
    }

    /** «Назад» на рабочем столе ничего не делает: закрывать его нельзя, иначе монитор пустеет. */
    @Override
    public void onBackPressed() {
    }

    private void buildUi() {
        built = true;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0F2027, 0xFF203A43, 0xFF2C5364}));
        root.setPadding(dp(32), dp(24), dp(32), dp(56));

        EditText search = new EditText(this);
        search.setHint("Поиск по всем приложениям");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x99FFFFFF);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(360),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = dp(16);
        root.addView(search, sp);

        GridView grid = new GridView(this);
        grid.setColumnWidth(dp(104));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(12));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> open(shown.get(pos)));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Display ext = DeskService.findExternal(getSystemService(DisplayManager.class));
        if (ext == null) {
            finish();
            return;
        }
        if (getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY) {
            if (!Sh.ready()) {
                Toast.makeText(this, "Сначала запустите Shizuku и дайте OppoDesk разрешение",
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            Sh.run("am display move-stack " + getTaskId() + " " + ext.getDisplayId());
            return;
        }
        if (!built) buildUi();
        current = this;
        all.clear();
        all.addAll(Apps.load(this));
        filter(query);
    }

    private void filter(String q) {
        query = q;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        Set<String> chosen = Apps.selected(this);
        shown.clear();
        for (Apps.Entry e : all) {
            if (TextUtils.isEmpty(needle)) {
                // Без поискового запроса показываем только выбранные приложения.
                if (chosen.isEmpty() || chosen.contains(Apps.key(e))) shown.add(e);
            } else if (e.label.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** Вызывается с телефона после изменения списка выбранных приложений. */
    static void refresh() {
        final DesktopActivity a = current;
        if (a != null) a.runOnUiThread(() -> { if (a.built) a.filter(a.query); });
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private void open(Apps.Entry e) {
        DeskService s = DeskService.instance;
        if (s == null || !s.hasDisplay()) {
            Toast.makeText(this, "Служба OppoDesk не включена", Toast.LENGTH_SHORT).show();
            return;
        }
        // Запуск из окна, которое уже на мониторе: новое приложение обычно
        // открывается на том же экране. Если прошивка всё же отправит его
        // на телефон, окно будет перенесено командой оболочки.
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(e.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // Prefer the public Android display-targeting API over shell task moves.
            // ColorOS may ignore this hint; the existing Shizuku fallback remains.
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(s.displayId());
            startActivity(launch, options.toBundle());
            if (Sh.ready()) Apps.ensureOnDisplay(e, s.displayId());
        } catch (Exception ex) {
            s.launch(e);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) v;
            if (cell == null) {
                cell = new LinearLayout(DesktopActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                ImageView icon = new ImageView(DesktopActivity.this);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
                TextView label = new TextView(DesktopActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(0, dp(6), 0, 0);
                cell.addView(label);
            }
            Apps.Entry e = shown.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(e.icon);
            ((TextView) cell.getChildAt(1)).setText(e.label);
            return cell;
        }
    }
}
