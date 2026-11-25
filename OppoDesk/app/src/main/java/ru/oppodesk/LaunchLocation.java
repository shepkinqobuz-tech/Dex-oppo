package ru.oppodesk;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
