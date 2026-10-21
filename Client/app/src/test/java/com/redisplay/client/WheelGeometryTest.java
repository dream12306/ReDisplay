package com.redisplay.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WheelGeometryTest {
    @Test
    public void mapsEightEqualSectorsAcrossBothRings() {
        assertEquals(0, WheelGeometry.selectionIndex(0, -60, 34, 94, 158));
        assertEquals(1, WheelGeometry.selectionIndex(45, -45, 34, 94, 158));
        assertEquals(2, WheelGeometry.selectionIndex(60, 0, 34, 94, 158));
        assertEquals(8, WheelGeometry.selectionIndex(0, -130, 34, 94, 158));
        assertEquals(10, WheelGeometry.selectionIndex(130, 0, 34, 94, 158));
    }

    @Test
    public void centerAndOutsideAreCancellationZones() {
        assertEquals(-1, WheelGeometry.selectionIndex(0, 0, 34, 94, 158));
        assertEquals(-1, WheelGeometry.selectionIndex(0, -200, 34, 94, 158));
    }
}
