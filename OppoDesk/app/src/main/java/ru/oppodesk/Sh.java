package ru.oppodesk;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Выполнение команд с правами оболочки через Shizuku. */
final class Sh {
    /** Текст последней ошибки — показывается на экране телефона. */
    static volatile String lastError = "";
    static volatile String lastResult = "Команды ещё не выполнялись";
    private static final ExecutorService pool = Executors.newSingleThreadExecutor();

    private Sh() {}

    static boolean alive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean ready() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestPermission() {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1);
            }
        } catch (Throwable t) {
            lastError = "Shizuku: " + t;
        }
    }

    static void run(final String script) {
        pool.execute(() -> {
            try {
                Method m = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null,
                        new String[] {"sh", "-c", "(" + script + ") 2>&1"}, null, null);
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < 400) out.append(line).append('\n');
                    }
                }
                int exitCode = p.waitFor();
                String s = out.toString().trim();
                lastResult = "Код завершения: " + exitCode + "\n"
                        + (s.isEmpty() ? "(нет вывода)" : s);
                lastError = (exitCode != 0 || s.contains("Exception")
                        || s.contains("Error") || s.contains("NOTASK"))
                        ? "Команда не выполнена:\n" + lastResult : "";
            } catch (Throwable t) {
                lastError = "Shizuku: " + t;
                lastResult = lastError;
            }
        });
    }
}
