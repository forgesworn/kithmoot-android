package dev.forgesworn.kithmoot.media.effects

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacePrivacyTest {
    @Test fun `all faces get generous covers with the same geometry as desktop`() {
        assertEquals(listOf(FaceBox(60f, 50f, 180f, 185f), FaceBox(380f, 170f, 90f, 111f)),
            faceCovers(listOf(FaceBox(100f, 100f, 100f, 100f), FaceBox(400f, 200f, 50f, 60f)), 640, 480))
        assertEquals(listOf(FaceBox(0f, 0f, 120f, 120f)), faceCovers(listOf(FaceBox(-10f, -10f, 100f, 100f)), 120, 120))
    }

    @Test fun `missing or malformed detection covers the whole picture`() {
        assertNull(faceCovers(emptyList(), 640, 480))
        val good = FaceBox(1f, 1f, 10f, 10f)
        for (bad in listOf(good.copy(x = Float.NaN), good.copy(width = 0f), good.copy(x = 700f))) {
            assertNull(faceCovers(listOf(good, bad), 640, 480))
        }
    }

    @Test fun `face modes cannot take the raw camera route even with no backdrop`() {
        for (face in listOf(FacePrivacyMode.MASK, FacePrivacyMode.AVATAR)) {
            val choice = BackgroundChoice(scene = null, face = face)
            assertTrue(choice.on)
            assertEquals(FrameRoute.COMPOSE, routeFor(choice.on, true, true, false))
            assertEquals(FrameRoute.DROP, routeFor(choice.on, true, true, true))
            assertEquals(FrameRoute.DROP, routeFor(choice.on, true, false, false))
        }
    }

    @Test fun `face choice persists with a backdrop off or before a backdrop was chosen`() {
        val strings = mutableMapOf<String, String>()
        val store = object : BackgroundStore {
            override fun getString(key: String, default: String?) = strings[key] ?: default
            override fun getBoolean(key: String, default: Boolean) = default
            override fun putString(key: String, value: String) { strings[key] = value }
            override fun putBoolean(key: String, value: Boolean) {}
            override fun remove(key: String) { strings.remove(key) }
        }
        val prefs = BackgroundPreference(store)
        for (face in listOf(FacePrivacyMode.MASK, FacePrivacyMode.AVATAR)) {
            val choice = BackgroundChoice(scene = null, face = face)
            prefs.save(choice)
            assertEquals(choice, prefs.load())
            strings.remove("background:scene")
            assertEquals(face, prefs.load().face)
        }
    }
}
