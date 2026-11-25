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
    @Test public void showsOnlyRequestedDisplayRoutesWithoutDeviceIdentifiers() {
        var filter = LaunchLocation.displayRoute(327);
        assertNull(filter.apply("    mUniqueId=local:1234"));
        assertEquals("device#1 type=local mCurrentLayerStack=327",
                filter.apply("    mCurrentLayerStack=327"));
        assertEquals("mDisplayId=0", filter.apply("    mDisplayId=0"));
        assertEquals("mPrimaryDisplayDevice=device#1 type=local",
                filter.apply("    mPrimaryDisplayDevice=Internal(local:1234)"));
        assertNull(filter.apply("    mDisplayId=328"));
        assertNull(filter.apply("    mLayerStack=328"));
        assertEquals("mDisplayId=327", filter.apply("    mDisplayId=327"));
        assertEquals("mPrimaryDisplayDevice=device#2 type=virtual",
                filter.apply("    mPrimaryDisplayDevice=Panel (virtual:private-owner)"));
        assertEquals("mLayerStack=0", filter.apply("    mLayerStack=0"));
        assertNull(filter.apply("    mBaseDisplayInfo=DisplayInfo{address=private}"));
    }

    @Test public void projectsExitReasonWithoutDescription() {
        assertEquals("timestamp=2026-11-25 11:41:00.003 pid=42 reason=4 (CRASH) status=0",
                LaunchLocation.processExit("ApplicationExitInfo #0: timestamp=2026-11-25 11:41:00.003"
                        + " pid=42 reason=4 (CRASH) status=0 description=private"));
        assertEquals("reason=4 (CRASH) subreason=0 (UNKNOWN) status=0",
                LaunchLocation.processExit("    process=private reason=4 (CRASH) subreason=0 (UNKNOWN) status=0"));
        assertNull(LaunchLocation.processExit("private trace file"));
    }

    @Test public void verifiesVisibleSurfaceOnOneDisplayAndInOneWindow() {
        String snapshot = """
                window package
                 mDisplayId=0
                 mHasSurface=true
                 isOnScreen=true
                 isVisible=false
                window package
                 mDisplayId=329
                 mHasSurface=true
                 isOnScreen=true
                 isVisible=true
                """;
        assertFalse(LaunchLocation.hasVisibleSurface(snapshot, 0));
        assertFalse(LaunchLocation.hasVisibleSurface(snapshot, 32));
        assertTrue(LaunchLocation.hasVisibleSurface(snapshot, 329));
    }

    @Test public void ignoresPackageOwnedStartingWindowWhenCheckingForActivitySurface() {
        var filter = LaunchLocation.windows(PKG, true);
        assertNull(filter.apply("  Window #2 Window{f u0 Splash Screen " + PKG + "}:"));
        assertNull(filter.apply("    mDisplayId=0 rootTaskId=2803"));
        assertNull(filter.apply("    mOwnerUid=123 package=" + PKG + " appop=NONE"));
        assertNull(filter.apply("    mHasSurface=true"));
        assertEquals("window " + PKG, filter.apply("  Window #3 Window{b u0 " + ALIAS + "}:"));
        assertEquals(" mDisplayId=0", filter.apply("    mDisplayId=0"));
    }
}
