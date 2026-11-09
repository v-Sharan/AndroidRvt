package com.androidrvt.viewer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import com.androidrvt.viewer.AppLog

/**
 * Keeps traffic on the camera's Wi-Fi even when that network has no internet,
 * which would otherwise make Android route it over mobile data.
 */
class NetworkBinder(context: Context, private val onNetworkChanged: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val wifiLock = wifi.createWifiLock(lockMode(), "AndroidRvt:video").apply {
        setReferenceCounted(false)
    }

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    fun bindToWifi(enabled: Boolean) {
        if (enabled == (callback != null)) return
        if (enabled) register() else unregister()
    }

    fun setHighPerformance(enabled: Boolean) {
        if (enabled) wifiLock.acquire() else if (wifiLock.isHeld) wifiLock.release()
    }

    private fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == boundNetwork) return
                boundNetwork = network
                connectivity.bindProcessToNetwork(network)
                AppLog.add("Bound traffic to Wi-Fi")
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                if (network != boundNetwork) return
                boundNetwork = null
                connectivity.bindProcessToNetwork(null)
                AppLog.add("Wi-Fi lost")
            }
        }
        callback = networkCallback
        runCatching { connectivity.requestNetwork(request, networkCallback) }
            .onFailure {
                callback = null
                AppLog.add("Could not request Wi-Fi: ${it.message}")
            }
    }

    private fun unregister() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        if (boundNetwork != null) {
            boundNetwork = null
            connectivity.bindProcessToNetwork(null)
            onNetworkChanged()
        }
    }

    @Suppress("DEPRECATION")
    private fun lockMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
}
