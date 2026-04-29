package com.exifiler

import okio.BufferedSource

object MetadataDetector {

    private const val META_MAKE = "Meta"
    private const val RAY_BAN_DEVICE_PREFIX = "device=Ray-Ban Meta Smart Glasses"
    private const val MATCH_DEVICE_NAME = "Ray-Ban Meta Smart Glasses"

    fun detect(source: BufferedSource, filename: String): DetectionResult {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> detectJpeg(source)
            lower.endsWith(".mp4") || lower.endsWith(".mov") -> detectMp4(source)
            else -> DetectionResult.Unsupported
        }
    }

    private fun isMetaMake(make: String?): Boolean =
        make != null && make.trim('\u0000').equals(META_MAKE, ignoreCase = true)

    private fun detectJpeg(source: BufferedSource): DetectionResult {
        // Read JPEG SOI marker
        val soi = source.readShort()
        if (soi != 0xFFD8.toShort()) return DetectionResult.Unsupported

        // Scan JPEG segments looking for APP1 (0xFFE1) which contains EXIF
        while (!source.exhausted()) {
            val marker = source.readShort().toInt() and 0xFFFF
            if (marker == 0xFFD9) break // EOI
            if (marker == 0xFFDA) break // SOS - no more metadata after this

            val segmentLength = (source.readShort().toInt() and 0xFFFF) - 2
            if (segmentLength < 0) break

            if (marker == 0xFFE1) {
                // Possibly EXIF APP1
                val header = source.readByteArray(6)
                val remaining = segmentLength - 6
                if (remaining > 0 && header.decodeToString().startsWith("Exif")) {
                    val exifData = source.readByteArray(remaining.toLong())
                    val make = extractExifMake(exifData)
                    if (isMetaMake(make)) {
                        return DetectionResult.Match(MATCH_DEVICE_NAME)
                    }
                } else {
                    if (remaining > 0) source.skip(remaining.toLong())
                }
            } else {
                source.skip(segmentLength.toLong())
            }
        }
        return DetectionResult.NoMatch
    }

    private fun extractExifMake(exifData: ByteArray): String? {
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
            if (tag == 0x010F) { // Make tag
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

    private fun detectMp4(source: BufferedSource): DetectionResult {
        // Scan MP4 boxes looking for comment metadata
        outer@ while (!source.exhausted()) {
            if (!source.request(8)) break
            val sizeField = source.readInt().toLong()
            val type = source.readByteArray(4).decodeToString()

            // Compute the data size of this box
            val boxDataSize: Long = when {
                sizeField == 0L -> break@outer // box extends to end of file
                sizeField == 1L -> { // 64-bit extended size
                    if (!source.request(8)) break@outer
                    source.readLong() - 16
                }
                else -> sizeField - 8
            }

            if (type == "udta") {
                // Parse udta children
                val comment = parseUdtaForComment(source, boxDataSize)
                if (comment != null && comment.contains(RAY_BAN_DEVICE_PREFIX)) {
                    return DetectionResult.Match(MATCH_DEVICE_NAME)
                }
                continue
            }

            // Skip box
            if (boxDataSize > 0) source.skip(boxDataSize)
        }
        return DetectionResult.NoMatch
    }

    private fun parseUdtaForComment(source: BufferedSource, udtaSize: Long): String? {
        var remaining = udtaSize
        while (remaining >= 8) {
            if (!source.request(8)) break
            val size = source.readInt().toLong()
            val type = source.readByteArray(4).decodeToString()
            remaining -= 8

            val dataSize = (size - 8).coerceAtLeast(0)
            remaining -= dataSize

            if (type == "\u00a9cmt" || type == "©cmt") {
                if (dataSize > 0) {
                    val data = source.readByteArray(dataSize)
                    // Skip language tag (first 4 bytes if present)
                    return if (data.size > 4) data.copyOfRange(4, data.size).decodeToString()
                    else data.decodeToString()
                }
            } else if (dataSize > 0) {
                source.skip(dataSize)
            }
        }
        if (remaining > 0 && remaining < udtaSize) source.skip(remaining)
        return null
    }
}
