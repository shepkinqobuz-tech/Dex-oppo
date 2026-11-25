package ru.oppodesk;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.UnaryOperator;

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
        run(null, -1, script);
    }

    static void run(String operation, int displayId, String script) {
        TaskTrace.Session trace = operation == null ? null : TaskTrace.active();
        pool.execute(() -> {
            if (trace != null) trace.stack("до " + operation, displayId);
            Result result = execute(script, 6000, UnaryOperator.identity(), trace != null);
            publish(result);
            if (trace == null) return;
            trace.command(operation, displayId, script, result);
            trace.afterCommand("после " + operation, displayId);
        });
    }

    static void launchAndMove(String source, int displayId, String start, String move,
                              String waitForWindowPackage) {
        TaskTrace.Session trace = TaskTrace.active();
        pool.execute(() -> {
            if (trace != null) trace.stack("до launch " + source, displayId);
            Result launch = capture(start, 6000, UnaryOperator.identity());
            if (trace != null) trace.command("launch " + source, displayId, start, launch);
            if (failed(launch)) {
                publish(launch);
                return;
            }
            if (waitForWindowPackage != null
                    && !waitForVisibleWindow(waitForWindowPackage, displayId, trace)) {
                return;
            }
            Result relocation = capture(move, 6000, UnaryOperator.identity());
            publish(relocation);
            if (trace == null) return;
            trace.command("move " + source, displayId, move, relocation);
            trace.afterCommand("после launch/move " + source, displayId);
        });
    }

    private static boolean waitForVisibleWindow(String pkg, int displayId, TaskTrace.Session trace) {
        String script = "timeout 3s dumpsys -t 2 window windows";
        Result snapshot = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            snapshot = capture(script, 4000, LaunchLocation.windows(pkg, true));
            if (trace != null) trace.command("Окно перед переносом " + pkg, displayId, script, snapshot);
            if (snapshot.exitCode() != 0) break;
            if (LaunchLocation.hasVisibleSurface(snapshot.stdout(), 0)
                    || LaunchLocation.hasVisibleSurface(snapshot.stdout(), displayId)) {
                return true;
            }
            if (attempt == 4) break;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String message = "Пробный перенос пропущен: видимое окно " + pkg + " не подтверждено";
        if (trace != null) trace.event(message, displayId);
        publish(new Result(snapshot.startedAt(), snapshot.durationMs(), 1,
                snapshot.stdout(), message + "\n" + snapshot.stderr()));
        return false;
    }

    private static boolean failed(Result result) {
        String out = result.stdout + "\n" + result.stderr;
        return result.exitCode != 0 || out.contains("Exception")
                || out.contains("Error") || out.contains("NOTASK");
    }

    private static void publish(Result result) {
        String out = (result.stdout + "\n" + result.stderr).trim();
        lastResult = "Код завершения: " + result.exitCode + "\n"
                + (out.isEmpty() ? "(нет вывода)" : out);
        lastError = failed(result)
                ? "Команда не выполнена:\n" + lastResult : "";
    }

    /** Выполняет команду для отчёта, сохраняя статус рабочих команд. */
    static Result capture(String script, int limit, UnaryOperator<String> project) {
        return execute(script, limit, project, true);
    }

    record Result(long startedAt, long durationMs, int exitCode, String stdout, String stderr) {}

    private static Result execute(String script, int limit, UnaryOperator<String> project,
                                  boolean separateStreams) {
        long started = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        Process process = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            String command = separateStreams ? script : "(" + script + ") 2>&1";
            process = (Process) m.invoke(null, new String[] {"sh", "-c", command}, null, null);
            FutureTask<String> errors = null;
            if (separateStreams) {
                InputStream errorStream = process.getErrorStream();
                errors = new FutureTask<>(() -> read(errorStream, 3000, UnaryOperator.identity()));
                Thread reader = new Thread(errors, "OppoDesk stderr");
                reader.setDaemon(true);
                reader.start();
            }
            String stdout = read(process.getInputStream(), limit, project);
            int exitCode = process.waitFor();
            String stderr = errors == null ? "" : errors.get();
            return new Result(started, elapsedMs(startNanos), exitCode, stdout, stderr);
        } catch (Exception e) {
            if (process != null) {
                try { process.destroy(); } catch (RuntimeException ignored) {}
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(started, elapsedMs(startNanos), -1, "", "Shizuku: " + e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String read(InputStream stream, int limit, UnaryOperator<String> project)
            throws IOException {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String kept = project.apply(line);
                if (kept == null) continue;
                if (out.length() + kept.length() + 1 <= limit && !truncated) {
                    out.append(kept).append('\n');
                } else {
                    truncated = true;
                }
            }
        }
        if (truncated) out.append("[вывод сокращён]\n");
        if (out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }
}
