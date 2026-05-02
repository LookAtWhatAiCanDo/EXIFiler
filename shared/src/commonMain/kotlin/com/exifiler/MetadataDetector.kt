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
     * **Behavior by [exifFilters] value:**
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
     * Glasses files no longer exists. Callers that previously relied on that behavior should pass
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

    // -- JPEG detection ----------------------------------------------------------------------------

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

    // -- MP4 detection -----------------------------------------------------------------------------
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

        val comment = findCommentInBoxes(source, Long.MAX_VALUE) ?: return DetectionResult.NoMatch
        if (!comment.contains(RAY_BAN_DEVICE_PREFIX)) return DetectionResult.NoMatch
        return DetectionResult.Match(extractDeviceName(comment) ?: MATCH_DEVICE_NAME)
    }

    private const val MAX_CMT_BYTES: Long = 64L * 1024L

    /**
     * Recursively walks MP4 boxes within a container of [containerSize] bytes (or
     * [Long.MAX_VALUE] for the unbounded top-level), descending into `moov`, `udta`,
     * `meta`, and `ilst`, and returning the text of the first `\u00a9cmt` atom found.
     */
    private fun findCommentInBoxes(source: BufferedSource, containerSize: Long): String? {
        var remaining = containerSize
        while (remaining == Long.MAX_VALUE || remaining >= 8) {
            if (!source.request(8)) return null
            val sizeField = source.readInt().toLong() and 0xFFFFFFFFL
            // Box types may contain byte 0xA9 (\u00a9), invalid as a lone UTF-8 byte.
            // Decode as Latin-1 so byte value maps 1:1 to char, keeping "\u00a9cmt" matches correct.
            val type = readLatin1(source, 4)
            if (remaining != Long.MAX_VALUE) remaining -= 8

            val dataSize: Long = when {
                sizeField == 1L -> {
                    // 64-bit extended-size box: next 8 bytes hold the full size including the
                    // 16-byte header (4 size + 4 type + 8 extended size).
                    if (!source.request(8)) return null
                    val extSize = source.readLong()
                    if (remaining != Long.MAX_VALUE) remaining -= 8
                    if (extSize < 16L) return null
                    extSize - 16L
                }
                sizeField == 0L -> {
                    // Box extends to end of container (or EOF at the top level).
                    if (remaining == Long.MAX_VALUE) Long.MAX_VALUE else remaining
                }
                sizeField < 8L -> return null
                else -> sizeField - 8
            }
            if (dataSize < 0) return null
            if (remaining != Long.MAX_VALUE && dataSize != Long.MAX_VALUE && dataSize > remaining) return null

            when (type) {
                "moov", "udta", "ilst" -> {
                    val found = findCommentInBoxes(source, dataSize)
                    if (found != null) return found
                }
                "meta" -> {
                    // `meta` is a FullBox (4-byte version/flags before children) in MP4, but a
                    // plain container in QuickTime. Peek bytes [4..8) of the body: if they look
                    // like a known child box type, the body starts directly with a box header;
                    // otherwise consume the 4-byte version/flags first.
                    var innerSize = dataSize
                    if (!metaBodyStartsWithChildBox(source)) {
                        if (!source.request(4)) return null
                        source.skip(4)
                        if (innerSize != Long.MAX_VALUE) innerSize -= 4
                    }
                    val found = findCommentInBoxes(source, innerSize)
                    if (found != null) return found
                }
                "\u00a9cmt" -> {
                    if (dataSize in 1..MAX_CMT_BYTES) {
                        if (!source.request(dataSize)) return null
                        val text = parseCmtPayload(source.readByteArray(dataSize))
                        if (text != null) return text
                    } else if (dataSize > 0 && dataSize != Long.MAX_VALUE) {
                        if (!skipBytes(source, dataSize)) return null
                    }
                }
                else -> {
                    if (dataSize == Long.MAX_VALUE) return null
                    if (dataSize > 0 && !skipBytes(source, dataSize)) return null
                }
            }
            if (remaining != Long.MAX_VALUE && dataSize != Long.MAX_VALUE) remaining -= dataSize
        }
        return null
    }

    /** Reads [n] bytes and decodes as Latin-1, mapping each byte value directly to a char. */
    private fun readLatin1(source: BufferedSource, n: Int): String {
        val bytes = source.readByteArray(n.toLong())
        return CharArray(n) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
    }

    /**
     * Peeks 8 bytes ahead and returns true when bytes [4..8) are the type of a known
     * `meta` child box. Distinguishes FullBox `meta` from plain-container `meta`.
     */
    private fun metaBodyStartsWithChildBox(source: BufferedSource): Boolean {
        val peek = source.peek()
        if (!peek.request(8)) return false
        peek.skip(4)
        return readLatin1(peek, 4) in setOf("hdlr", "keys", "ilst")
    }

    private fun skipBytes(source: BufferedSource, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val chunk = minOf(left, 8192L)
            if (!source.request(chunk)) return false
            source.skip(chunk)
            left -= chunk
        }
        return true
    }

    /**
     * Decodes the payload of a `\u00a9cmt` atom. Handles two layouts:
     *   - iTunes-style: `[4 size][4 "data"][4 type-indicator][4 locale][UTF-8 text]`
     *   - QuickTime-style: `[2 text-len][2 lang-code][UTF-8 text]`
     */
    private fun parseCmtPayload(data: ByteArray): String? {
        if (data.size >= 16 && data.copyOfRange(4, 8).decodeToString() == "data") {
            return data.copyOfRange(16, data.size).decodeToString().trim('\u0000')
        }
        if (data.size > 4) {
            return data.copyOfRange(4, data.size).decodeToString().trim('\u0000')
        }
        return null
    }

    /** Extracts the `device=...` value from a Ray-Ban Meta comment string, or null. */
    private fun extractDeviceName(comment: String): String? {
        val idx = comment.indexOf("device=")
        if (idx < 0) return null
        val start = idx + "device=".length
        val end = comment.indexOf('&', start).let { if (it < 0) comment.length else it }
        return comment.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }
}
