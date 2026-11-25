package ru.oppodesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/** Поля вывода SurfaceFlinger для выбранного приложения и рабочего стола. */
final class SurfaceComposition {
    private static final Pattern DISPLAY = Pattern.compile("^Display \\S+ \\((physical|virtual)[,)]");
    private static final String LAYER_PREFIX = "- Output Layer ";
    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final String COORDINATES = NUMBER + "(?:[ ,]+" + NUMBER + "){3}";
    private static final String RECT = "\\[" + COORDINATES + "\\]";
    private static final Pattern FILTER = Pattern.compile("\\b(?:layerStack=-?\\d+|toInternalDisplay=(?:true|false))\\b");
    private static final Pattern BOUNDS = Pattern.compile(
            "^(displaySpace|layerStackSpace)=ProjectionSpace\\{bounds=(?:Rect\\((" + COORDINATES
                    + ")\\)|\\[(" + COORDINATES + ")\\])");
    private static final Pattern LAYER_FIELDS = Pattern.compile(
            "\\b(?:(?:displayFrame|sourceCrop)=" + RECT + "|composition=[A-Z_]+(?: \\(-?\\d+\\))?)");

    private SurfaceComposition() {}

    static UnaryOperator<String> project(String pkg) {
        Pattern appLayer = Pattern.compile("(?<![a-zA-Z0-9_.$])" + Pattern.quote(pkg) + "/");
        Pattern deskLayer = Pattern.compile("(?<![a-zA-Z0-9_.$])ru\\.oppodesk/(?:ru\\.oppodesk)?"
                + "\\.(?:MainActivity|DesktopActivity|DiagnosticsActivity)(?![a-zA-Z0-9_.$])");
        return new UnaryOperator<>() {
            private int output;
            private boolean outputFields;
            private boolean selectedLayer;

            @Override public String apply(String line) {
                String row = line.trim();
                if (row.startsWith("Permission Denial")) return "SurfaceFlinger: доступ запрещён";
                Matcher display = DISPLAY.matcher(row);
                if (display.find()) {
                    outputFields = true;
                    selectedLayer = false;
                    return "Выход " + ++output + " type=" + display.group(1);
                }
                if (output == 0) return null;
                if (row.startsWith(LAYER_PREFIX)) {
                    outputFields = false;
                    Matcher desk = deskLayer.matcher(row);
                    if (desk.find()) {
                        selectedLayer = true;
                        return "  слой " + desk.group();
                    }
                    selectedLayer = appLayer.matcher(row).find();
                    return selectedLayer ? "  слой " + pkg : null;
                }
                if (outputFields) {
                    if (row.startsWith("layerFilter=")) return fields(row, FILTER);
                    Matcher bounds = BOUNDS.matcher(row);
                    if (!bounds.find()) return null;
                    String coordinates = bounds.group(2) == null ? bounds.group(3) : bounds.group(2);
                    return "  " + bounds.group(1) + ".bounds=[" + coordinates + "]";
                }
                return selectedLayer ? fields(row, LAYER_FIELDS) : null;
            }
        };
    }

    private static String fields(String row, Pattern pattern) {
        Matcher matches = pattern.matcher(row);
        StringBuilder line = new StringBuilder();
        while (matches.find()) {
            line.append(' ').append(matches.group());
        }
        return line.length() == 0 ? null : " " + line;
    }
}
