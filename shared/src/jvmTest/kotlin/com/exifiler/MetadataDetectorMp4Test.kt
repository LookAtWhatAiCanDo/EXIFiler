package com.exifiler

import okio.buffer
import okio.source
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MetadataDetectorMp4Test {

    private val metaFilter = mapOf("Make" to "Meta")

    private fun source(filename: String) =
        File("../samples/$filename").source().buffer()

    @Test
    fun `iTunes-style Ray-Ban Meta Smart Glasses 2 mp4 is detected`() {
        val result = source("20260317_121355_5d0c68ad.mp4").use { src ->
            MetadataDetector.detect(src, "20260317_121355_5d0c68ad.mp4", metaFilter)
        }
        assertIs<DetectionResult.Match>(result)
        assertEquals("Ray-Ban Meta Smart Glasses 2", result.deviceName)
    }

    @Test
    fun `mp4 with no filters matches any`() {
        val result = source("20260317_121355_5d0c68ad.mp4").use { src ->
            MetadataDetector.detect(src, "20260317_121355_5d0c68ad.mp4", emptyMap())
        }
        assertIs<DetectionResult.Match>(result)
    }

    @Test
    fun `mp4 with non-Meta Make filter returns NoMatch`() {
        val result = source("20260317_121355_5d0c68ad.mp4").use { src ->
            MetadataDetector.detect(src, "20260317_121355_5d0c68ad.mp4", mapOf("Make" to "Apple"))
        }
        assertEquals(DetectionResult.NoMatch, result)
    }
}
