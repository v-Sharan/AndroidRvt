package com.androidrvt.viewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun loadProfiles(): List<CameraProfile> {
        val stored = preferences.getString(KEY_PROFILES, null)
        val profiles = stored?.let(::parse).orEmpty()
        return profiles.ifEmpty {
            listOf(CameraProfile(name = "Test pattern", rtspUrl = TEST_PATTERN_URL, bindToWifi = false))
        }
    }

    fun loadActiveId(): String? = preferences.getString(KEY_ACTIVE, null)

    fun save(profiles: List<CameraProfile>, activeId: String) {
        val array = JSONArray()
        profiles.forEach { array.put(toJson(it)) }
        preferences.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }

    private fun parse(text: String): List<CameraProfile> = runCatching {
        val array = JSONArray(text)
        List(array.length()) { fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun toJson(profile: CameraProfile) = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("rtspUrl", profile.rtspUrl)
        .put("transport", profile.transport.name)
        .put("latencyMs", profile.latencyMs)
        .put("frameTimeoutMs", profile.frameTimeoutMs)
        .put("connectTimeoutMs", profile.connectTimeoutMs)
        .put("hardwareDecoder", profile.hardwareDecoder)
        .put("udpHost", profile.udpHost)
        .put("udpTargetPort", profile.udpTargetPort)
        .put("udpLocalPort", profile.udpLocalPort)
        .put("heartbeatMs", profile.heartbeatMs)
        .put("controlRateHz", profile.controlRateHz)
        .put("bindToWifi", profile.bindToWifi)

    private fun fromJson(json: JSONObject): CameraProfile {
        val defaults = CameraProfile()
        return CameraProfile(
            id = json.optString("id", defaults.id),
            name = json.optString("name", defaults.name),
            rtspUrl = json.optString("rtspUrl", defaults.rtspUrl),
            transport = runCatching { Transport.valueOf(json.optString("transport")) }
                .getOrDefault(defaults.transport),
            latencyMs = json.optInt("latencyMs", defaults.latencyMs),
            frameTimeoutMs = json.optInt("frameTimeoutMs", defaults.frameTimeoutMs),
            connectTimeoutMs = json.optInt("connectTimeoutMs", defaults.connectTimeoutMs),
            hardwareDecoder = json.optBoolean("hardwareDecoder", defaults.hardwareDecoder),
            udpHost = json.optString("udpHost", defaults.udpHost),
            udpTargetPort = json.optInt("udpTargetPort", defaults.udpTargetPort),
            udpLocalPort = json.optInt("udpLocalPort", defaults.udpLocalPort),
            heartbeatMs = json.optInt("heartbeatMs", defaults.heartbeatMs),
            controlRateHz = json.optInt("controlRateHz", defaults.controlRateHz),
            bindToWifi = json.optBoolean("bindToWifi", defaults.bindToWifi),
        )
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active"
    }
}
