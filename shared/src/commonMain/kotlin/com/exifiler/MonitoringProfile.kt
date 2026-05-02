package com.exifiler

/**
 * Defines a monitoring rule: which input folder to watch, which file types to include,
 * optional EXIF metadata filters, and the destination folder for matched files.
 */
data class MonitoringProfile(
    /** Stable unique identifier (UUID string). */
    val id: String,
    /** Human-readable label shown in the UI. */
    val name: String,
    /**
     * MediaStore relative path of the folder to watch, e.g. `"Download"` or `"DCIM/Camera"`.
     * A trailing `/` is optional — the service normalises it at query time.
     */
    val inputFolder: String,
    /**
     * File-extension allow-list (without the leading dot), e.g. `["jpg", "jpeg", "mp4"]`.
     * An empty list means *all* supported file types are accepted.
     */
    val filePatterns: List<String>,
    /**
     * EXIF / media-metadata key-value pairs that must all match for a file to be processed,
     * e.g. `mapOf("Make" to "Meta")`.  Stored for display and future enforcement; the current
     * implementation delegates actual metadata detection to [MetadataDetector].
     */
    val exifFilters: Map<String, String>,
    /** MediaStore relative path of the destination folder, e.g. `"DCIM/EXIFiler"`. */
    val outputFolder: String,
    /** When `false` the service ignores this profile entirely. */
    val isEnabled: Boolean = true,
) {
    companion object {
        /**
         * Pre-configured profile that reproduces the original single-folder behaviour:
         * monitor `Download/` for JPEG/MP4 files from Meta AI Glasses and move them to
         * `DCIM/EXIFiler`.
         */
        val DEFAULT = MonitoringProfile(
            id = "default",
            name = "Meta AI Glasses",
            inputFolder = "Download",
            filePatterns = listOf("jpg", "jpeg", "mp4", "mov"),
            exifFilters = mapOf("Make" to "Meta"),
            outputFolder = "DCIM/EXIFiler",
            isEnabled = true,
        )
    }
}
