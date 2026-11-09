package com.androidrvt.viewer.data

import java.util.UUID

const val TEST_PATTERN_URL = "test"

enum class Transport(val label: String, val gstValue: String) {
    AUTO("Auto", "udp+tcp"),
    UDP("UDP", "udp"),
    TCP("TCP", "tcp"),
}

data class CameraProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New camera",
    val rtspUrl: String = "rtsp://",
    val transport: Transport = Transport.AUTO,
    val latencyMs: Int = 100,
    val frameTimeoutMs: Int = 3000,
    val connectTimeoutMs: Int = 5000,
    val hardwareDecoder: Boolean = true,
    val udpHost: String = "",
    val udpTargetPort: Int = 5000,
    val udpLocalPort: Int = 5001,
    val heartbeatMs: Int = 1000,
    val controlRateHz: Int = 20,
    val bindToWifi: Boolean = true,
)
