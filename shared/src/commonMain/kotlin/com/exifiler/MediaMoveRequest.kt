package com.exifiler

data class MediaMoveRequest(
    val sourceUri: String,
    val targetCollection: String,
    val filename: String
)
