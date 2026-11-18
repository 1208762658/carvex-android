package io.github.iamr0s.carvex.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 导航下行只承诺"语义确定"的字段：文本格式必须在 JVM 上可验证。 */
class NavigationSnapshotTest {
    @Test
    fun distanceTextSwitchesAtOneKilometre() {
        assertEquals("999米", NavigationSnapshot.distanceText(999))
        assertEquals("1.0公里", NavigationSnapshot.distanceText(1000))
        assertEquals("1.2公里", NavigationSnapshot.distanceText(1234))
        assertEquals("0米", NavigationSnapshot.distanceText(0))
    }

    @Test
    fun unknownDistanceStaysAbsent() {
        assertNull(NavigationSnapshot.distanceText(null))
        assertNull(NavigationSnapshot.distanceText(-1))
        assertNull(NavigationSnapshot(road = "测试路").remainText())
        assertNull(NavigationSnapshot(remainMeters = 1200).maneuverText())
    }
}
