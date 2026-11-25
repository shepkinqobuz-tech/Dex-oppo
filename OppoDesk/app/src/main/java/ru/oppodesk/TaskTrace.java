package ru.oppodesk;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Временная запись сценария. Рабочие команды Shizuku и чтение отчёта независимы. */
final class TaskTrace {
    private static final String STACK = "timeout 4s am stack list";
    private static final String ACTIVITIES = "timeout 6s dumpsys -t 4 activity activities";
    private static final String WINDOWS = "timeout 5s dumpsys -t 3 window windows";
    private static final String DISPLAY = "timeout 5s dumpsys -t 3 display";
    private static final Handler timer = new Handler(Looper.getMainLooper());
    private static final int MAX_REPORT_CHARS = 120_000;
    private static final ThreadPoolExecutor snapshots = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> new Thread(runnable, "OppoDesk task snapshots"));

    private static volatile Session report = new Session();
    private static volatile Session active;

    private TaskTrace() {}

    static Session active() {
        return active;
    }

    static void start(int displayId) {
        Session session = new Session();
        report = session;
        active = session;
        session.event("Запись начата", displayId);
        session.after("начало записи", displayId);
    }

    static void stop(int displayId, Runnable completed) {
        Session session = active;
        active = null;
        if (session == null) {
            completed.run();
            return;
        }
        session.event("Запись остановлена", displayId);
        session.finish(completed);
    }

    static String report() {
        return report.render();
    }

    static void event(String label, int displayId) {
        Session session = active;
        if (session != null) session.event(label, displayId);
    }

    static void manualSnapshot(int displayId, Runnable completed) {
        Session session = report;
        if (!session.enqueue("ручной снимок", displayId, () -> {
            session.snapshot("ручной снимок", displayId);
            completed.run();
        })) {
            completed.run();
        }
    }

    static final class Session {
        private final ArrayDeque<String> entries = new ArrayDeque<>();
        // Запуск приложения и события DesktopActivity приходят в основном потоке.
        private String observedPackage;
        private int observedDisplay;
        private int launchNumber;

        void watch(String pkg, int displayId) {
            observedPackage = pkg;
            observedDisplay = displayId;
            int number = ++launchNumber;
            for (int delay : new int[] {500, 2000, 6000, 12000, 16000, 24000}) {
                timer.postDelayed(() -> {
                    if (active == this && launchNumber == number) location("запуск +" + delay + " мс");
                }, delay);
            }
        }

        void location(String reason) {
            String pkg = observedPackage;
            if (pkg == null) return;
            int displayId = observedDisplay;
            String label = "Расположение " + pkg + " / " + reason;
            enqueue(label, displayId, () -> {
                taskCommand(label, displayId, STACK, 4000, LaunchLocation.tasks(pkg));
                taskCommand(label, displayId, WINDOWS, 4000, LaunchLocation.windows(pkg));
                if (reason.equals("запуск +2000 мс") || reason.equals("desktop.resume")) {
                    taskCommand(label, displayId, DISPLAY, 4000, LaunchLocation.displayRoute(displayId));
                }
            });
        }

        private void finish(Runnable completed) {
            String pkg = observedPackage;
            if (pkg == null || !enqueue("завершение сценария", observedDisplay, () -> {
                try {
                    taskCommand("Отображение дисплеев", observedDisplay, DISPLAY, 4000,
                            LaunchLocation.displayRoute(observedDisplay));
                    taskCommand("История завершений " + pkg, observedDisplay,
                            "timeout 5s dumpsys -t 3 activity exit-info '" + pkg + "'", 4000,
                            LaunchLocation::processExit);
                } finally {
                    completed.run();
                }
            })) completed.run();
        }

        private int size;
        private boolean dropped;

        void event(String label, int displayId) {
            add(System.currentTimeMillis() + " | " + label + " | targetDisplayId=" + displayId);
        }

        void stack(String label, int displayId) {
            taskCommand(label, displayId, STACK, 12_000);
        }

        void after(String label, int displayId) {
            enqueue(label, displayId, () -> snapshot(label, displayId));
        }

        void afterCommand(String label, int displayId) {
            stack(label, displayId);
            enqueue(label, displayId, () -> taskCommand(label, displayId, ACTIVITIES, 20_000));
        }

        private boolean enqueue(String label, int displayId, Runnable work) {
            event("Запрошен снимок: " + label, displayId);
            try {
                snapshots.execute(work);
                return true;
            } catch (RejectedExecutionException e) {
                event("Пропущен снимок (очередь заполнена): " + label, displayId);
                return false;
            }
        }

        private void snapshot(String label, int displayId) {
            stack(label, displayId);
            taskCommand(label, displayId, ACTIVITIES, 20_000);
        }

        private void taskCommand(String label, int displayId, String script, int limit) {
            taskCommand(label, displayId, script, limit, TaskSnapshot::project);
        }

        private void taskCommand(String label, int displayId, String script, int limit,
                                 UnaryOperator<String> filter) {
            Sh.Result result = Sh.capture(script, limit, filter);
            if (result.exitCode() == 0 && result.stdout().isEmpty()) {
                result = new Sh.Result(result.startedAt(), result.durationMs(), result.exitCode(),
                        "[нет совпадающих распознанных строк]", result.stderr());
            }
            command(label, displayId, script, result);
        }

        void command(String label, int displayId, String script, Sh.Result result) {
            add(result.startedAt() + " | " + label + " | targetDisplayId=" + displayId
                    + " | " + result.durationMs() + " ms | exit=" + result.exitCode()
                    + "\n$ " + script + "\nstdout:\n" + result.stdout()
                    + "\nstderr:\n" + result.stderr());
        }

        private synchronized void add(String entry) {
            while (!entries.isEmpty() && size + entry.length() > MAX_REPORT_CHARS) {
                size -= entries.removeFirst().length();
                dropped = true;
            }
            entries.addLast(entry);
            size += entry.length();
        }

        private synchronized String render() {
            return (dropped ? "[ранние записи удалены из-за лимита]\n\n" : "")
                    + String.join("\n\n", entries);
        }
    }
}
