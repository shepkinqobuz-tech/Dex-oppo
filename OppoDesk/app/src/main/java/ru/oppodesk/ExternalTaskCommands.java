package ru.oppodesk;

/** Commands for the RootTaskInfo format confirmed on ColorOS 17. */
final class ExternalTaskCommands {
    private ExternalTaskCommands() {}

    // topActivity in am stack list describes the entire root. Use it for package
    // matching only when the row is the root itself.
    private static final String SELECT = """
            $1 == "RootTask" {
                root = ""
                display = ""
                if ($2 ~ /^id=[0-9]+$/) root = substr($2, 4)
                for (i = 3; i <= NF; i++)
                    if ($i ~ /^displayId=[0-9]+$/) display = substr($i, 11)
                next
            }
            root != "" && display != "" && $1 ~ /^taskId=[0-9]+:$/ {
                id = substr($1, 8, length($1) - 8)
                count[root]++
                if (expected != "" && id != expected) next
                exact = ($2 == cmp || $2 == shortcmp)
                belongs = index($2, pkg "/") == 1
                visible = 0
                for (i = 2; i <= NF; i++) {
                    if ($i == "visible=true") visible = 1
                    if (root == id && index($i, "topActivity=ComponentInfo{" pkg "/") == 1)
                        belongs = 1
                }
                if (!belongs) next
                if (display != target && (mode != "move" || display != "0" || root != id)) next
                score = 4 * exact + 2 * visible + (display == target)
                if (!found || score > bestScore) {
                    found = 1
                    bestScore = score
                    bestId = id
                    bestRoot = root
                    bestDisplay = display
                }
            }
            END {
                if (found && (bestDisplay == target || count[bestRoot] == 1))
                    print bestId, bestRoot, bestDisplay
            }
            """;

    static String move(String component, String shortComponent, int displayId) {
        return selector(component, shortComponent, displayId) + """
                for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
                    selected=$(select_task move '') || exit $?
                    [ -n "$selected" ] && break
                    sleep 0.25
                done
                if [ -z "$selected" ]; then
                    echo "NOTASK: не найдена задача $pkg для безопасного переноса" >&2
                    exit 1
                fi
                read -r task root current <<EOF
                $selected
                EOF
                echo "Selected taskId=$task rootTaskId=$root displayId=$current"
                if [ "$current" != "$target" ]; then
                    timeout 3s am display move-stack "$root" "$target" || exit $?
                fi
                for attempt in 1 2 3 4 5 6; do
                    selected=$(select_task check "$task") || exit $?
                    if [ -n "$selected" ]; then
                        echo "Verified taskId=$task displayId=$target"
                        exit 0
                    fi
                    sleep 0.25
                done
                echo "Положение задачи $task на дисплее $target не подтверждено" >&2
                exit 1
                """;
    }

    static String focus(String component, String shortComponent, int displayId) {
        return selector(component, shortComponent, displayId) + """
                selected=$(select_task check '') || exit $?
                if [ -z "$selected" ]; then
                    echo "NOTASK: задача $pkg отсутствует на дисплее $target" >&2
                    exit 1
                fi
                echo "Focus task ${selected%% *} on display $target"
                timeout 3s am task focus "${selected%% *}"
                """;
    }

    private static String selector(String component, String shortComponent, int displayId) {
        String pkg = component.substring(0, component.indexOf('/'));
        return "pkg=" + quote(pkg) + "\ncmp=" + quote(component)
                + "\nshortcmp=" + quote(shortComponent) + "\ntarget=" + displayId + "\n"
                + """
                select_task() {
                    local listing
                    listing=$(timeout 3s am stack list) || return $?
                    printf '%s\n' "$listing" | awk -v pkg="$pkg" -v cmp="$cmp" -v shortcmp="$shortcmp" \\
                        -v target="$target" -v mode="$1" -v expected="$2" '""" + SELECT + "'\n}\n";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
