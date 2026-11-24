package ru.oppodesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TaskSnapshotTest {
    @Test public void keepsDisplayAndRootIdsSeparateFromChildTaskIds() {
        // RootTaskInfo.toString() from AOSP. Display 273 is from the dev10 report.
        assertEquals("RootTask id=3000 displayId=273 userId=0",
                TaskSnapshot.project("RootTask id=3000 bounds=[0,0][1920,1080] displayId=273 userId=0"));
        assertEquals("  taskId=2583: com.coloros.gallery3d/.app.MainActivity userId=0 visible=true"
                        + " topActivity=ComponentInfo{com.coloros.gallery3d/.app.MainActivity}",
                TaskSnapshot.project("  taskId=2583: com.coloros.gallery3d/.app.MainActivity"
                        + " bounds=[0,0][1920,1080] userId=0 visible=true"
                        + " topActivity=ComponentInfo{com.coloros.gallery3d/.app.MainActivity}"));
        assertEquals("RootTask id=3001 displayId=0 userId=0",
                TaskSnapshot.project("RootTask id=3001 bounds=[0,0][1440,3168] displayId=0 userId=0"));
        assertEquals("RootTask id=3002 displayId=274",
                TaskSnapshot.project("RootTask id=3002 displayId=274"));
    }

    @Test public void keepsGalleryAffinityAndActivityFromDev10Report() {
        // The handoff preserves these fields, with indentation reconstructed from AOSP.
        assertEquals("displayId=273",
                TaskSnapshot.project("Display #273 (activities from top to bottom):"));
        assertEquals("  Task #2583 A=10173:com.oppo.gallery3d.app.Gallery"
                        + " visible=true visibleRequested=true mode=fullscreen",
                TaskSnapshot.project("  * Task{dbea #2583 A=10173:com.oppo.gallery3d.app.Gallery"
                        + " visible=true visibleRequested=true mode=fullscreen}"));
        assertEquals("    topResumedActivity u0 com.coloros.gallery3d/.app.MainActivity taskId=2583",
                TaskSnapshot.project("    topResumedActivity=ActivityRecord{af32"
                        + " u0 com.coloros.gallery3d/.app.MainActivity t2583}"));
    }

    @Test public void keepsDistinctComponentsAndVisibilityAcrossTasks() {
        assertEquals("    taskId=3100: com.android.chrome/.Main visible=false",
                TaskSnapshot.project("    taskId=3100: com.android.chrome/.Main visible=false"));
        assertEquals("    taskId=3101: com.android.chrome.beta/.Main visible=true",
                TaskSnapshot.project("    taskId=3101: com.android.chrome.beta/.Main visible=true"));
        assertEquals("    taskId=3102: org.telegram.messenger/.LauncherAlias visible=false",
                TaskSnapshot.project("    taskId=3102: org.telegram.messenger/.LauncherAlias visible=false"));
        assertEquals("      Hist u0 org.telegram.messenger/.Main taskId=3102",
                TaskSnapshot.project("      * Hist #0: ActivityRecord{afd"
                        + " u0 org.telegram.messenger/.Main t3102}"));
    }

    @Test public void keepsFocusAndResumedActivityOnDisplay() {
        assertEquals("  mLastFocusedRootTask=Task #3000",
                TaskSnapshot.project("  mLastFocusedRootTask=Task{dbea #3000}"));
        assertEquals("    Resumed u0 android/.ResolverActivity taskId=3001",
                TaskSnapshot.project("    Resumed: ActivityRecord{ea u0 android/.ResolverActivity t3001}"));
    }

    @Test public void omitsIntentDataAndUnknownRows() {
        assertNull(TaskSnapshot.project("    intent={dat=https://example.com/account flg=0x10000000}"));
        assertNull(TaskSnapshot.project("      app=ProcessRecord{123 secret account}"));
        assertEquals("  Task #3100 rootTaskId=3000 visible=true",
                TaskSnapshot.project("  Task{ff #3100 rootTaskId=3000 title=Private visible=true}"));
        assertEquals("  taskId=3100: visible=false",
                TaskSnapshot.project("  taskId=3100: unavailable visible=false"));
    }
}
