package com.exifiler

import okio.BufferedSource

object MetadataDetector {

    private const val META_MAKE = "Meta"
    private const val RAY_BAN_DEVICE_PREFIX = "device=Ray-Ban Meta Smart Glasses"
    private const val MATCH_DEVICE_NAME = "Ray-Ban Meta Smart Glasses"

    /**
     * Maps well-known EXIF IFD0 tag names (as used in [MonitoringProfile.exifFilters]) to their
     * numeric tag IDs so that arbitrary string-valued IFD0 tags can be checked generically.
     */
    private val EXIF_STRING_TAGS = mapOf(
        "Make"      to 0x010F,
        "Model"     to 0x0110,
        "Software"  to 0x0131,
        "Artist"    to 0x013B,
        "Copyright" to 0x8298,
    )

    /**
     * Detect whether [source] (named [filename]) matches the given [exifFilters].
     *
     * **Behaviour by [exifFilters] value:**
     * - `emptyMap()` (the default): any file whose extension is supported counts as a match.
     *   Use this when a [MonitoringProfile] has no EXIF constraints — all files in the watched
     *   folder should be moved regardless of their embedded metadata.
     * - Non-empty map: the file's embedded metadata must satisfy **all** key/value pairs.
     *   Supported keys are the EXIF IFD0 string-tag names listed in [EXIF_STRING_TAGS]
     *   (`"Make"`, `"Model"`, `"Software"`, `"Artist"`, `"Copyright"`).
     *   For MP4/MOV, **only** `"Make"="Meta"` is supported (maps to the Ray-Ban `©cmt` atom
     *   check); any other filter combination returns [DetectionResult.NoMatch] for video files.
     *
     * Note: the old zero-argument version of this function that implicitly filtered for Meta AI
     * Glasses files no longer exists. Callers that previously relied on that behaviour should pass
     * `mapOf("Make" to "Meta")` explicitly — which is what [MonitoringProfile.DEFAULT] does.
     */
    fun detect(
        source: BufferedSource,
        filename: String,
        exifFilters: Map<String, String> = emptyMap(),
    ): DetectionResult {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> detectJpeg(source, exifFilters)
            lower.endsWith(".mp4") || lower.endsWith(".mov") -> detectMp4(source, exifFilters)
            else -> DetectionResult.Unsupported
        }
    }

    // ── JPEG detection ────────────────────────────────────────────────────────────────────────────

    private fun detectJpeg(source: BufferedSource, exifFilters: Map<String, String>): DetectionResult {
        // Read JPEG SOI marker
        if (!source.request(2)) return DetectionResult.Unsupported
        val soi = source.readShort()
        if (soi != 0xFFD8.toShort()) return DetectionResult.Unsupported

        // No filters → any valid JPEG matches
        if (exifFilters.isEmpty()) return DetectionResult.Match("any")

        // Scan JPEG segments looking for APP1 (0xFFE1) which contains EXIF
        while (true) {
            if (!source.request(4)) break
            val marker = source.readShort().toInt() and 0xFFFF
            if (marker == 0xFFD9) break // EOI
            if (marker == 0xFFDA) break // SOS - no more metadata after this

            val segmentLength = (source.readShort().toInt() and 0xFFFF) - 2
            if (segmentLength < 0) break

            if (marker == 0xFFE1 && segmentLength >= 6) {
                // Possibly EXIF APP1 — guard that we can read the 6-byte header
                if (!source.request(6)) break
                val header = source.readByteArray(6)
                val remaining = segmentLength - 6
                if (remaining > 0 && header.decodeToString().startsWith("Exif")) {
                    if (!source.request(remaining.toLong())) break
                    val exifData = source.readByteArray(remaining.toLong())
                    if (matchesExifFilters(exifData, exifFilters)) {
                        val make = extractExifStringTag(exifData, 0x010F)?.trim('\u0000')
                        // Prefer the actual EXIF Make value; fall back to the filter's Make value;
                        // then to the first filter entry description; finally to the default name.
                        val deviceName = make
                            ?: exifFilters["Make"]
                            ?: exifFilters.entries.firstOrNull()?.let { "${it.key}=${it.value}" }
                            ?: MATCH_DEVICE_NAME
                        return DetectionResult.Match(deviceName)
                    }
                } else {
                    if (remaining > 0) {
                        if (!source.request(remaining.toLong())) break
                        source.skip(remaining.toLong())
                    }
                }
            } else if (segmentLength > 0) {
                if (!source.request(segmentLength.toLong())) break
                source.skip(segmentLength.toLong())
            }
        }
        return DetectionResult.NoMatch
    }

    /**
     * Returns true when all [exifFilters] entries are satisfied by the IFD0 tags in [exifData].
     * Keys not present in [EXIF_STRING_TAGS] are treated as non-matching (conservative).
     */
    private fun matchesExifFilters(exifData: ByteArray, exifFilters: Map<String, String>): Boolean {
        if (exifFilters.isEmpty()) return true
        return exifFilters.all { (key, value) ->
            val tagId = EXIF_STRING_TAGS[key] ?: return@all false
            val extracted = extractExifStringTag(exifData, tagId) ?: return@all false
            extracted.trim('\u0000').equals(value, ignoreCase = true)
        }
    }

    /** Extracts the ASCII value of any IFD0 EXIF tag identified by [tagId]. */
    private fun extractExifStringTag(exifData: ByteArray, tagId: Int): String? {
        if (exifData.size < 8) return null
        // Determine byte order
        val littleEndian = exifData[0] == 0x49.toByte() && exifData[1] == 0x49.toByte()

        fun readShort(offset: Int): Int {
            val a = exifData[offset].toInt() and 0xFF
            val b = exifData[offset + 1].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }

        fun readInt(offset: Int): Int {
            val a = exifData[offset].toInt() and 0xFF
            val b = exifData[offset + 1].toInt() and 0xFF
            val c = exifData[offset + 2].toInt() and 0xFF
            val d = exifData[offset + 3].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        val ifd0Offset = readInt(4)
        if (ifd0Offset + 2 > exifData.size) return null

        val entryCount = readShort(ifd0Offset)
        for (i in 0 until entryCount) {
            val entryOffset = ifd0Offset + 2 + i * 12
            if (entryOffset + 12 > exifData.size) break
            val tag = readShort(entryOffset)
            if (tag == tagId) {
                val dataType = readShort(entryOffset + 2)
                val count = readInt(entryOffset + 4)
                val valueOffset = readInt(entryOffset + 8)
                if (dataType == 2 && count > 0) { // ASCII
                    val start = if (count <= 4) entryOffset + 8 else valueOffset
                    if (start + count > exifData.size) return null
                    return exifData.copyOfRange(start, start + count).decodeToString()
                }
            }
        }
        return null
    }

    // ── MP4 detection ─────────────────────────────────────────────────────────────────────────────
    //
    // MP4 container atoms do not map cleanly to EXIF IFD0 tags, so only the well-known
    // Ray-Ban Meta Smart Glasses signature (©cmt atom containing a "device=…" string) is
    // supported.  When [exifFilters] is non-empty and does not specify `Make=Meta`, this method
    // returns [DetectionResult.NoMatch] — it cannot inspect arbitrary MP4 metadata.

    private fun detectMp4(source: BufferedSource, exifFilters: Map<String, String>): DetectionResult {
        // No filters → any valid MP4/MOV matches without inspecting metadata
        if (exifFilters.isEmpty()) return DetectionResult.Match("any")

        // MP4 metadata inspection is only supported when Make=Meta is specified.
        // Other arbitrary EXIF keys cannot be reliably resolved from MP4 container atoms.
        val targetMake = exifFilters["Make"]
        if (targetMake == null || !targetMake.trim('\u0000').equals(META_MAKE, ignoreCase = true)) {
            return DetectionResult.NoMatch
        }

        // Scan top-level MP4 boxes looking for comment metadata
        outer@ while (true) {
            if (!source.request(8)) break
            // Read size as unsigned 32-bit to cover the full range (0 to 4,294,967,295 bytes)
            val sizeField = source.readInt().toLong() and 0xFFFFFFFFL
            val type = source.readByteArray(4).decodeToString()

            val boxDataSize: Long = when {
                sizeField == 0L -> {
                    // Box extends to EOF — consume udta if it's this box, then stop
                    if (type == "udta") {
                        val comment = parseUdtaForComment(source, Long.MAX_VALUE)
                        if (comment != null && comment.contains(RAY_BAN_DEVICE_PREFIX)) {
                            return DetectionResult.Match(MATCH_DEVICE_NAME)
                        }
                    }
                    break@outer
                }
                sizeField == 1L -> { // 64-bit extended size field
                    if (!source.request(8)) break@outer
                    source.readLong() - 16
                }
                sizeField < 8L -> break@outer // malformed box
                else -> sizeField - 8
            }

            if (type == "udta") {
                val comment = parseUdtaForComment(source, boxDataSize)
                if (comment != null && comment.contains(RAY_BAN_DEVICE_PREFIX)) {
                    return DetectionResult.Match(MATCH_DEVICE_NAME)
                }
                continue
            }

            // Skip box data
            if (boxDataSize > 0) {
                if (!source.request(boxDataSize)) break
                source.skip(boxDataSize)
            }
        }
        return DetectionResult.NoMatch
    }

    private fun parseUdtaForComment(source: BufferedSource, udtaSize: Long): String? {
        var remaining = udtaSize
        while (remaining == Long.MAX_VALUE || remaining >= 8) {
            if (!source.request(8)) break
            // Read child box size as unsigned 32-bit
            val sizeField = source.readInt().toLong() and 0xFFFFFFFFL
            val type = source.readByteArray(4).decodeToString()
            if (remaining != Long.MAX_VALUE) remaining -= 8

            // Validate and compute data size
            if (sizeField == 0L || sizeField == 1L || sizeField < 8L) break // unsupported / malformed
            val dataSize = sizeField - 8
            if (remaining != Long.MAX_VALUE && dataSize > remaining) break // child exceeds parent

            if (remaining != Long.MAX_VALUE) remaining -= dataSize

            if (type == "\u00a9cmt") {
                if (dataSize > 4) {
                    if (!source.request(dataSize)) break
                    val data = source.readByteArray(dataSize)
                    // Skip the 4-byte iTunes-style language header if present
                    return data.copyOfRange(4, data.size).decodeToString()
                } else if (dataSize > 0) {
                    if (!source.request(dataSize)) break
                    return source.readByteArray(dataSize).decodeToString()
                }
            } else if (dataSize > 0) {
                if (!source.request(dataSize)) break
                source.skip(dataSize)
            }
        }
        return null
    }
}
