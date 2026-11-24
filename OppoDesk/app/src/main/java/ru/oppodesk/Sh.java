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
                              String tracedMove) {
        TaskTrace.Session trace = TaskTrace.active();
        pool.execute(() -> {
            if (trace == null) {
                publish(execute(start + " >/dev/null;" + move, 6000,
                        UnaryOperator.identity(), false));
                return;
            }
            trace.stack("до launch " + source, displayId);
            Result launch = capture(start, 6000, UnaryOperator.identity());
            Result relocation = capture(tracedMove, 6000, UnaryOperator.identity());
            publish(new Result(relocation.startedAt, relocation.durationMs, relocation.exitCode,
                    relocation.stdout, launch.stderr + "\n" + relocation.stderr));
            trace.command("launch " + source, displayId, start, launch);
            trace.command("move " + source, displayId, tracedMove, relocation);
            trace.afterCommand("после launch/move " + source, displayId);
        });
    }

    private static void publish(Result result) {
        String out = (result.stdout + "\n" + result.stderr).trim();
        lastResult = "Код завершения: " + result.exitCode + "\n"
                + (out.isEmpty() ? "(нет вывода)" : out);
        lastError = (result.exitCode != 0 || out.contains("Exception")
                || out.contains("Error") || out.contains("NOTASK"))
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
