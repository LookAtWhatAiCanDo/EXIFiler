package com.exifiler

sealed class DetectionResult {
    data class Match(val deviceName: String) : DetectionResult()
    object NoMatch : DetectionResult()
    object Unsupported : DetectionResult()
}
