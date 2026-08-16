package dev.mahlernim.timelinevisualizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMercatorTest {
    @Test
    fun projectsOriginToWorldCenter() {
        val point = WebMercator.project(0.0, 0.0)
        assertEquals(0.5, point.x, 0.000001)
        assertEquals(0.5, point.y, 0.000001)
    }

    @Test
    fun choosesShortPathAcrossDateLine() {
        val direct = listOf(WebMercator.project(0.0, 179.0).x, WebMercator.project(0.0, -179.0).x)
        val wrapped = WebMercator.shortestWrappedX(direct)
        assertTrue((wrapped.maxOrNull()!! - wrapped.minOrNull()!!) < 0.01)
    }
}
