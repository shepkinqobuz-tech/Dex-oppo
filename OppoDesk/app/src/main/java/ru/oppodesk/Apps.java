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

    /** Ждёт появления окна приложения и переносит его на экран displayId, если оно не там. */
    static String mover(String pkg, int displayId) {
        return " r=; t=; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do"
                + " r=$(am stack list | grep -E '^RootTask id=|taskId=[0-9]*: " + pkg + "/'"
                + " | grep -B1 -m1 'taskId=[0-9]*: " + pkg + "/');"
                + " t=$(echo \"$r\" | sed -n 's/.*taskId=\\([0-9]*\\):.*/\\1/p' | head -n 1);"
                + " [ -n \"$t\" ] && break; sleep 0.25; done;"
                + " if [ -z \"$t\" ]; then echo NOTASK " + pkg + ";"
                + " else case \"$r \" in *displayId=" + displayId + "[!0-9]*) ;;"
                + " *) am display move-stack $t " + displayId + " ;; esac; fi";
    }

    /**
     * Прошивка не даёт запускать приложения сразу на внешнем экране, поэтому
     * приложение открывается обычным образом, после чего его окно переносится
     * на экран displayId командой оболочки.
     */
    static void launch(Entry e, int displayId) {
        String pkg = e.component.getPackageName();
        String cmp = pkg + "/" + e.component.getClassName();
        Sh.run("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -f 0x10200000 -n '" + cmp + "' >/dev/null;" + mover(pkg, displayId));
        remember(e);
    }

    /** Приложение уже запущено обычным способом — только проверить, что оно на мониторе. */
    static void ensureOnDisplay(Entry e, int displayId) {
        Sh.run(mover(e.component.getPackageName(), displayId));
        remember(e);
    }

    private static void remember(Entry e) {
        for (int k = recent.size() - 1; k >= 0; k--) {
            if (recent.get(k).component.equals(e.component)) recent.remove(k);
        }
        recent.add(0, e);
        while (recent.size() > 8) recent.remove(recent.size() - 1);
        DeskService s = DeskService.instance;
        if (s != null) s.refreshRecent();
    }
}
