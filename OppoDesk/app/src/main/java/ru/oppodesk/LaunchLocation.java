package ru.oppodesk;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/** Проекция двух независимых системных снимков для одного запущенного пакета. */
final class LaunchLocation {
    private LaunchLocation() {}

    static UnaryOperator<String> tasks(String pkg) {
        Pattern component = Pattern.compile("(?:^taskId=\\d+:\\s+|\\btopActivity=(?:ComponentInfo\\{)?)"
                + Pattern.quote(pkg) + "/");
        return new UnaryOperator<>() {
            private String root;

            public String apply(String line) {
                String row = line.trim();
                if (row.startsWith("RootTask")) root = TaskSnapshot.project(row);
                if (!row.startsWith("taskId=") || !component.matcher(row).find()) return null;
                return (root == null ? "RootTask=?" : root) + "\n  " + TaskSnapshot.project(row);
            }
        };
    }

    static UnaryOperator<String> displayRoute(int target) {
        Pattern id = Pattern.compile("mDisplayId=(\\d+)");
        Pattern device = Pattern.compile("mPrimaryDisplayDevice=.*\\(([a-z]+:[^)]*)\\)$");
        Pattern deviceId = Pattern.compile("mUniqueId=([a-z]+:\\S+)");
        Pattern currentStack = Pattern.compile("mCurrentLayerStack=-?\\d+");
        Pattern fields = Pattern.compile("mLayerStack=-?\\d+|"
                + "(?:mHasContent|mIsEnabled|mIsInTransition)=(?:true|false)");
        return new UnaryOperator<>() {
            private final Map<String, Integer> devices = new HashMap<>();
            private boolean selected;
            private String currentDevice;

            private String deviceLabel(String uniqueId) {
                int number = devices.computeIfAbsent(uniqueId, ignored -> devices.size() + 1);
                return "device#" + number + " type=" + uniqueId.substring(0, uniqueId.indexOf(':'));
            }

            public String apply(String line) {
                String row = line.trim();
                Matcher m = deviceId.matcher(row);
                if (m.matches()) {
                    currentDevice = deviceLabel(m.group(1));
                    selected = false;
                    return null;
                }
                if (currentDevice != null && currentStack.matcher(row).matches()) {
                    return currentDevice + " " + row;
                }
                m = id.matcher(row);
                if (m.matches()) {
                    currentDevice = null;
                    selected = m.group(1).equals("0") || m.group(1).equals(Integer.toString(target));
                    return selected ? row : null;
                }
                if (!selected) return null;
                m = device.matcher(row);
                if (m.matches()) return "mPrimaryDisplayDevice=" + deviceLabel(m.group(1));
                if (row.equals("mPrimaryDisplayDevice=null") || fields.matcher(row).matches()) return row;
                return null;
            }
        };
    }

    private static final Pattern EXIT_FIELDS = Pattern.compile(
            "\\btimestamp=\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"
            + "|\\b(?:pid|status)=-?\\d+"
            + "|\\b(?:subreason|reason)=\\d+(?: ?\\([A-Z0-9_ -]+\\))?");

    static String processExit(String line) {
        String row = line.trim();
        if (!row.startsWith("timestamp=") && !row.startsWith("process=")
                && !row.startsWith("ApplicationExitInfo")) return null;
        Matcher fields = EXIT_FIELDS.matcher(row);
        StringBuilder summary = new StringBuilder();
        while (fields.find()) {
            if (summary.length() > 0) summary.append(' ');
            summary.append(fields.group());
        }
        return summary.length() > 0 ? summary.toString() : null;
    }

    static UnaryOperator<String> windows(String pkg) {
        Pattern component = Pattern.compile("\\bu\\d+\\s+" + Pattern.quote(pkg) + "/");
        Pattern owner = Pattern.compile("(?:^|\\s)package=" + Pattern.quote(pkg) + "(?=\\s|$)");
        Pattern fields = Pattern.compile("\\b(?:mDisplayId=-?\\d+|rootTaskId=-?\\d+"
                + "|(?:mHasSurface|isOnScreen|isVisible)=(?:true|false))\\b");
        return new UnaryOperator<>() {
            private boolean inWindow;
            private boolean matches;
            private final StringBuilder pending = new StringBuilder();

            public String apply(String line) {
                String row = line.trim();
                if (row.startsWith("Window #")) {
                    inWindow = true;
                    matches = component.matcher(row).find();
                    pending.setLength(0);
                    return matches ? "window " + pkg : null;
                }
                if (!row.isEmpty() && !line.startsWith("    ")) inWindow = false;
                if (!inWindow) return null;
                boolean foundOwner = !matches && owner.matcher(row).find();
                if (foundOwner) matches = true;
                Matcher m = fields.matcher(row);
                while (m.find()) pending.append(' ').append(m.group());
                if (!matches || (!foundOwner && pending.length() == 0)) return null;
                String result = (foundOwner ? "window " + pkg : "") + pending;
                pending.setLength(0);
                return result;
            }
        };
    }
}
