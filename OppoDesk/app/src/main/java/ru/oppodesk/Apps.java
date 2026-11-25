package ru.oppodesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Список установленных приложений и их запуск на внешнем экране. */
final class Apps {
    static final class Entry {
        String label;
        Drawable icon;
        ComponentName component;
    }

    /** Последние запущенные приложения — показываются на панели задач. */
    static final List<Entry> recent = new ArrayList<>();

    static final String PREFS = "desk";
    static final String KEY_DPI = "dpi";
    static final String KEY_ICON_SIZE = "icon_size";
    static final String KEY_RECENT = "recent_components";
    static final String KEY_APPS = "apps";
    /** Плотность монитора по умолчанию: 213 dpi даёт на Full HD экран 1440×810 dp. */
    static final int DEFAULT_DPI = 213;

    private Apps() {}

    /** Приложения, выбранные для показа на мониторе; пустой набор — показывать все. */
    static Set<String> selected(Context ctx) {
        return new HashSet<>(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_APPS, new HashSet<String>()));
    }

    static void saveSelected(Context ctx, Set<String> keys) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_APPS, new HashSet<>(keys)).apply();
    }

    static String key(Entry e) {
        return e.component.flattenToString();
    }

    static List<Entry> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Entry> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            Entry e = new Entry();
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.loadIcon(pm);
            e.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            out.add(e);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /**
     * Прошивка не даёт запускать приложения сразу на внешнем экране, поэтому
     * приложение открывается обычным образом, после чего его окно переносится
     * на экран displayId командой оболочки.
     */
    static void launch(Entry e, int displayId, String source) {
        String pkg = e.component.getPackageName();
        TaskTrace.Session trace = TaskTrace.active();
        if (trace != null) trace.watch(pkg, displayId);
        String cmp = pkg + "/" + e.component.getClassName();
        String start = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -f 0x10200000 -n '" + cmp + "'";
        Sh.launchAndMove(source, displayId, start, ExternalTaskCommands.move(
                cmp, e.component.flattenToShortString(), displayId));
        remember(e);
    }

    /** Приложение уже запущено обычным способом — только проверить, что оно на мониторе. */
    static void ensureOnDisplay(Entry e, int displayId) {
        String pkg = e.component.getPackageName();
        TaskTrace.Session trace = TaskTrace.active();
        if (trace != null) trace.watch(pkg, displayId);
        Sh.run("move desktop " + pkg, displayId, ExternalTaskCommands.move(
                key(e), e.component.flattenToShortString(), displayId));
        remember(e);
    }

    /** Focus only an existing task on the requested display; never start it on the phone. */
    static void focusExisting(Entry e, int displayId) {
        String pkg = e.component.getPackageName();
        Sh.run("focus " + pkg, displayId, ExternalTaskCommands.focus(
                key(e), e.component.flattenToShortString(), displayId));
    }

    /** Restore launcher shortcuts, not a claim that Android tasks are still running. */
    static void restoreRecent(Context ctx) {
        recent.clear();
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RECENT, "");
        if (saved.isEmpty()) return;
        List<Entry> installed = load(ctx);
        for (String key : saved.split("\\n")) {
            for (Entry entry : installed) {
                if (key.equals(key(entry))) {
                    recent.add(entry);
                    break;
                }
            }
            if (recent.size() >= 8) break;
        }
    }

    static void clearRecent(Context ctx) {
        recent.clear();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECENT).apply();
        DeskService service = DeskService.instance;
        if (service != null) service.refreshRecent();
    }

    private static void saveRecent(Context ctx) {
        StringBuilder saved = new StringBuilder();
        for (Entry entry : recent) {
            if (saved.length() > 0) saved.append('\n');
            saved.append(key(entry));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENT, saved.toString()).apply();
    }

    static void remember(Entry e) {
        for (int k = recent.size() - 1; k >= 0; k--) {
            if (recent.get(k).component.equals(e.component)) recent.remove(k);
        }
        recent.add(0, e);
        while (recent.size() > 8) recent.remove(recent.size() - 1);
        DeskService s = DeskService.instance;
        if (s != null) {
            saveRecent(s);
            s.refreshRecent();
        }
    }
}
