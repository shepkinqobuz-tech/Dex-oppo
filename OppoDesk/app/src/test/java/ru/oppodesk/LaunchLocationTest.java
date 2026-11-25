package ru.oppodesk;

import static org.junit.Assert.*;
import org.junit.Test;

public class LaunchLocationTest {
    private static final String PKG = "org.telegram.messenger.web";
    private static final String ALIAS = PKG + "/org.telegram.messenger.DefaultIcon";

    @Test public void followsDev12AliasOnEitherDisplayAndKeepsRootId() {
        var filter = LaunchLocation.tasks(PKG);
        assertNull(filter.apply("RootTask id=2767 displayId=326 userId=0"));
        String row = "  taskId=2767: " + ALIAS + " userId=0 visible=true";
        assertEquals("RootTask id=2767 displayId=326 userId=0\n" + row, filter.apply(row));
        filter.apply("RootTask id=9000 displayId=0 userId=0");
        assertEquals("RootTask id=9000 displayId=0 userId=0\n" + row, filter.apply(row));
        assertNull(filter.apply("  taskId=3: " + PKG + ".other/.Launcher"));
    }

    @Test public void retainsDisplayWhenOwnerAppearsAfterWindowHeader() {
        var filter = LaunchLocation.windows(PKG);
        assertNull(filter.apply("  Window #3 Window{abcd u0 private title}:"));
        assertNull(filter.apply("    mDisplayId=0 rootTaskId=2767"));
        assertEquals("window " + PKG + " mDisplayId=0 rootTaskId=2767",
                filter.apply("    mOwnerUid=123 package=" + PKG + " appop=NONE"));
        assertEquals(" mHasSurface=true", filter.apply("    mHasSurface=true token=private"));
        assertNull(filter.apply("  Window #4 Window{f u0 other.app/.Main}:"));
        assertNull(filter.apply("    isVisible=true"));
    }

    @Test public void identifiesComponentWindowWithoutExportingTitle() {
        var filter = LaunchLocation.windows(PKG);
        assertEquals("window " + PKG, filter.apply("  Window #2 Window{f u0 " + ALIAS + "}:"));
        assertEquals(" mDisplayId=326", filter.apply("    mDisplayId=326"));
    }
}
