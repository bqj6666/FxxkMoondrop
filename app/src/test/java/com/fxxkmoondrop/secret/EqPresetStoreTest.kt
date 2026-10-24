package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetStoreTest {

    private fun profile(vararg gains: Float) = MoondropEq.Profile(
            1.5f,
            gains.mapIndexed { i, g -> MoondropEq.Band(100 * (i + 1), g, 1.0f + i) }.toMutableList())

    private fun preset(name: String, id: String = name) =
            EqPresetStore.fromProfile(name, id, profile(1f, -2f, 0.5f), 1000L)

    @Test
    fun roundTripKeepsValues() {
        val src = listOf(preset("流行", "a"), preset("夜听", "b"))
        val back = EqPresetStore.decode(EqPresetStore.encode(src))
        assertEquals(2, back.size)
        assertEquals("流行", back[0].name)
        assertEquals(1.5f, back[0].preampDb, 1e-4f)
        assertEquals(3, back[0].bands.size)
        assertEquals(-2f, back[0].bands[1].gainDb, 1e-4f)
        assertEquals(1.0f + 2, back[0].bands[2].q, 1e-4f)
        assertEquals(1000L, back[1].createdAt)
    }

    @Test
    fun decodeRejectsBrokenInput() {
        assertTrue(EqPresetStore.decode(null).isEmpty())
        assertTrue(EqPresetStore.decode("").isEmpty())
        assertTrue(EqPresetStore.decode("not json").isEmpty())
        assertTrue(EqPresetStore.decode("{}").isEmpty())
        // bands 为空的条目整体丢弃，不能留一条空壳
        assertTrue(EqPresetStore.decode("""[{"id":"x","name":"n","bands":[]}]""").isEmpty())
        // 缺 id 或 name 的条目也丢弃
        assertTrue(EqPresetStore.decode("""[{"id":"","name":"n","bands":[{"f":100,"g":1,"q":1}]}]""").isEmpty())
    }

    @Test
    fun decodeDropsOnlyBadEntries() {
        val json = """[
            {"id":"a","name":"好","preamp":0,"bands":[{"f":100,"g":1,"q":1}]},
            {"id":"b","name":"坏","preamp":0,"bands":[]},
            {"id":"c","name":"也好","preamp":-1,"bands":[{"f":200,"g":-1,"q":2}]}
        ]"""
        val list = EqPresetStore.decode(json)
        assertEquals(2, list.size)
        assertEquals("好", list[0].name)
        assertEquals("也好", list[1].name)
    }

    @Test
    fun uniqueNameAppendsSuffix() {
        assertEquals("安静", EqPresetStore.uniqueName("安静", emptyList()))
        assertEquals("安静 (2)", EqPresetStore.uniqueName("安静", listOf("安静")))
        assertEquals("安静 (3)", EqPresetStore.uniqueName("安静", listOf("安静", "安静 (2)")))
        // 大小写不同也算重名，避免出现两个只差大小写的条目
        assertEquals("pop (2)", EqPresetStore.uniqueName("pop", listOf("POP")))
    }

    @Test
    fun uniqueNameTrimsAndFallsBack() {
        assertEquals("我的预设", EqPresetStore.uniqueName("   ", emptyList()))
        val long = "x".repeat(40)
        assertEquals(EqPresetStore.MAX_NAME_LEN, EqPresetStore.uniqueName(long, emptyList()).length)
        val dedup = EqPresetStore.uniqueName(long, listOf("x".repeat(EqPresetStore.MAX_NAME_LEN)))
        assertEquals(EqPresetStore.MAX_NAME_LEN, dedup.length)
        assertTrue(dedup.endsWith(" (2)"))
    }

    @Test
    fun toProfileReturnsDeepCopy() {
        val p = preset("a")
        val a = EqPresetStore.toProfile(p)
        val b = EqPresetStore.toProfile(p)
        assertNotSame(a, b)
        a.bands[0].gainDb = -9f
        assertEquals(1f, b.bands[0].gainDb, 1e-4f)
        assertEquals(1f, p.bands[0].gainDb, 1e-4f)
    }

    @Test
    fun fromProfileDoesNotAliasBands() {
        val pr = profile(0f, 2f)
        val p = EqPresetStore.fromProfile("n", "id", pr, 1L)
        pr.bands[0].gainDb = -7f
        assertEquals(0f, p.bands[0].gainDb, 1e-4f)
        assertFalse(p.bands[0] === pr.bands[0])
    }
}
