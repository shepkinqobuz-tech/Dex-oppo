package ru.oppodesk;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SurfaceCompositionTest {
    private static final String TELEGRAM = "org.telegram.messenger.web";

    @Test public void keepsDisplayAndLayerGeometrySeparatedByOutput() {
        var filter = SurfaceComposition.project(TELEGRAM);
        assertEquals("Выход 1 type=physical", filter.apply("Display 461982 (physical, \"Internal\")"));
        assertEquals("  layerStack=0 toInternalDisplay=true",
                filter.apply("   layerFilter={layerStack=0 toInternalDisplay=true }"));
        assertEquals("  displaySpace.bounds=[0, 0, 1440, 3168]",
                filter.apply("   displaySpace=ProjectionSpace{bounds=Rect(0, 0, 1440, 3168),"
                        + " content=Rect(0, 0, 1440, 3168), orientation=ROTATION_0}"));
        assertEquals("  слой ru.oppodesk/.MainActivity",
                filter.apply("  - Output Layer 0x123(ru.oppodesk/.MainActivity#15)"));
        assertEquals("  displayFrame=[0 0 1440 3168]",
                filter.apply("      displayFrame=[0 0 1440 3168] bufferTransform=0"));
        assertEquals("Выход 2 type=physical", filter.apply("Display 994302 (physical, \"S24E650\")"));
        assertEquals("  layerStack=330 toInternalDisplay=false",
                filter.apply("   layerFilter={layerStack=330 toInternalDisplay=false }"));
        assertEquals("  displaySpace.bounds=[0, 0, 1920, 1080]",
                filter.apply("   displaySpace=ProjectionSpace{bounds=Rect(0, 0, 1920, 1080),"
                        + " content=Rect(0, 0, 1920, 1080), orientation=ROTATION_0}"));
        assertEquals("  слой " + TELEGRAM, filter.apply("  - Output Layer 0x456("
                + TELEGRAM + "/org.telegram.ui.LaunchActivity#106)"));
        assertEquals("  displayFrame=[0 0 1920 1080] sourceCrop=[0.0 0.0 1920.0 1080.0]",
                filter.apply("      displayFrame=[0 0 1920 1080] sourceCrop=[0.0 0.0 1920.0 1080.0]"));
        assertEquals("  composition=DEVICE (2)", filter.apply("      composition=DEVICE (2)"));
    }

    @Test public void omitsUnrelatedLayersAndTheirGeometry() {
        var filter = SurfaceComposition.project(TELEGRAM);
        assertNull(filter.apply("  - Output Layer 0x123(" + TELEGRAM + "/Main#15)"));
        filter.apply("Display 42 (physical, \"Private name\")");
        filter.apply("  - Output Layer 0x123(" + TELEGRAM + "/Main#15)");
        assertNull(filter.apply("  - Output Layer 0x456(private." + TELEGRAM + "/Main#16)"));
        assertNull(filter.apply("      displayFrame=[0 1 2 3]"));
        assertNull(filter.apply("  - Output Layer 0x789(" + TELEGRAM + ".other/Main#17)"));
        assertNull(filter.apply("      composition=DEVICE (2)"));
    }

    @Test public void reportsPermissionDenialWithoutIdentifiers() {
        var filter = SurfaceComposition.project(TELEGRAM);
        assertEquals("SurfaceFlinger: доступ запрещён",
                filter.apply("Permission Denial: can't dump SurfaceFlinger from pid=123, uid=2000"));
    }
}
