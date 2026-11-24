package ru.oppodesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сохраняет поля задач и иерархию, исключая Intent, URI и содержимое окон. */
final class TaskSnapshot {
    private static final String PACKAGE = "(?:android|[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)";
    private static final String COMPONENT = PACKAGE + "/[a-zA-Z0-9_.$]+";
    private static final Pattern DISPLAY = Pattern.compile(
            "^(?:Display\\s+#|[mM]?[dD]isplayId=)(\\d+)\\b");
    private static final Pattern ROOT = Pattern.compile("^RootTask\\s+id=(\\d+)\\b");
    private static final Pattern TASK_ROW = Pattern.compile(
            "^taskId=(\\d+):(?:\\s+(" + COMPONENT + ")(?=\\s|$))?");
    private static final Pattern TASK = Pattern.compile(
            "^(?:(mPreferredTopFocusableRootTask|mLastFocusedRootTask)\\s*=\\s*|\\*\\s*)?"
                    + "Task(?:\\{[^#]*#|\\s+#)(\\d+)\\b");
    private static final Pattern ACTIVITY = Pattern.compile(
            "^(?:(mResumedActivity|topResumedActivity|ResumedActivity|Resumed|mLastPausedActivity)"
                    + "\\s*[:=]\\s*|\\*?\\s*Hist\\s+#\\d+:\\s*)"
                    + "ActivityRecord\\{[^}]*?\\bu(\\d+)\\s+(" + COMPONENT + ")(?=[\\s}]|$)"
                    + "(?:\\s+t(\\d+))?");
    private static final Pattern FIELDS = Pattern.compile(
            "(?<!\\S)(?:(?:displayId|rootTaskId|userId|U|windowingMode)=-?\\d+"
                    + "|(?:visible|visibleRequested|isVisible|isVisibleRequested"
                    + "|mVisibleRequested|mVisible)=(?:true|false)"
                    + "|(?:mode|type|windowingMode)=[a-zA-Z_-]{1,32}"
                    + "|A=(?:\\d+:)?" + PACKAGE
                    + "|(?:realActivity|origActivity|topActivity|baseActivity|I|aI)"
                    + "=(?:ComponentInfo\\{" + COMPONENT + "\\}|" + COMPONENT + "))(?=\\s|[},]|$)");

    private TaskSnapshot() {}

    static String project(String line) {
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
        String body = line.substring(start);
        String indent = line.substring(0, Math.min(start, 24));
        Matcher m = DISPLAY.matcher(body);
        if (m.find()) return indent + "displayId=" + m.group(1);
        m = ROOT.matcher(body);
        if (m.find()) return indent + "RootTask id=" + m.group(1) + fields(body);
        m = TASK_ROW.matcher(body);
        if (m.find()) {
            String component = m.group(2) == null ? "" : " " + m.group(2);
            return indent + "taskId=" + m.group(1) + ":" + component + fields(body);
        }
        m = TASK.matcher(body);
        if (m.find()) {
            String kind = m.group(1) == null ? "" : m.group(1) + "=";
            return indent + kind + "Task #" + m.group(2) + fields(body);
        }
        m = ACTIVITY.matcher(body);
        if (m.find()) {
            String kind = m.group(1) == null ? "Hist" : m.group(1);
            String task = m.group(4) == null ? "" : " taskId=" + m.group(4);
            return indent + kind + " u" + m.group(2) + " " + m.group(3) + task + fields(body);
        }
        return null;
    }

    private static String fields(String body) {
        Matcher fields = FIELDS.matcher(body);
        StringBuilder result = new StringBuilder();
        while (fields.find()) result.append(' ').append(fields.group());
        return result.toString();
    }
}
