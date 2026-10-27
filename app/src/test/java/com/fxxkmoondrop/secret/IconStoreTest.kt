package com.fxxkmoondrop.secret

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class IconStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun smallAndLargeImagesAlwaysEncodeFreshOutput() {
        for (size in listOf(64, 127, 128, 4096)) {
            for (oldCache in listOf(false, true)) {
                val dir = temporary.newFolder()
                val cache = File(dir, "moondrop_custom_icon.png")
                if (oldCache) cache.writeText("stale image")
                val permanent = File(dir, IconStore.FILE_NAME)
                permanent.writeText("previous icon")
                val targets = mutableListOf<Int>()
                val fresh = "new $size".toByteArray()
                val png = IconStore.encodePng(size) { target ->
                    targets.add(target)
                    fresh
                }
                IconStore.replaceLocal(permanent, png)
                assertEquals(listOf(minOf(size, 512)), targets)
                assertArrayEquals(fresh, permanent.readBytes())
                assertEquals(if (oldCache) 2 else 1, dir.listFiles()!!.size)
                if (oldCache) assertEquals("stale image", cache.readText())
            }
        }
    }

    @Test fun oversizedEncodingRetriesWithSmallerTargets() {
        val targets = mutableListOf<Int>()
        val png = IconStore.encodePng(4096) {
            targets.add(it)
            if (targets.size < 3) ByteArray(IconStore.MAX_BYTES + 1) else byteArrayOf(42)
        }
        assertEquals(listOf(512, 384, 288), targets)
        assertArrayEquals(byteArrayOf(42), png)
    }

    @Test fun failedEncodingPreservesPermanentIcon() {
        val permanent = temporary.newFile(IconStore.FILE_NAME)
        permanent.writeText("previous icon")
        for (size in listOf(64, 127, 128, 4096)) {
            assertThrows(IOException::class.java) {
                val png = IconStore.encodePng(size) { ByteArray(IconStore.MAX_BYTES + 1) }
                IconStore.replaceLocal(permanent, png)
            }
            assertThrows(IOException::class.java) {
                val png = IconStore.encodePng(size) { byteArrayOf() }
                IconStore.replaceLocal(permanent, png)
            }
            assertEquals("previous icon", permanent.readText())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IconStore.replaceLocal(permanent, byteArrayOf())
        }
        assertEquals("previous icon", permanent.readText())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test fun failedAtomicReplacementCleansTemporaryFile() {
        val destination = temporary.newFolder(IconStore.FILE_NAME)
        val existing = File(destination, "existing")
        existing.writeText("keep")
        assertThrows(IOException::class.java) {
            IconStore.replaceLocal(destination, byteArrayOf(42))
        }
        assertEquals("keep", existing.readText())
        assertEquals(listOf(destination), temporary.root.listFiles()!!.toList())
    }
}
