package com.parentalcontrol.childapp.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.parentalcontrol.childapp.vpn.DnsVpnService

object VpnStateChecker {

    private const val TAG = "VpnStateChecker"

    fun isVpnRunning(context: Context): Boolean {

        return try {

            // =================================================
            // 1. DnsVpnService internal state
            // =================================================

            if (DnsVpnService.isRunning) {

                Log.d(
                    TAG,
                    "✅ DnsVpnService reports running"
                )

                return true
            }

            // =================================================
            // 2. Android VPN network state
            // =================================================

            val connectivityManager =
                context.getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            val networks =
                connectivityManager.allNetworks

            for (network in networks) {

                val capabilities =
                    connectivityManager.getNetworkCapabilities(
                        network
                    )

                if (
                    capabilities?.hasTransport(
                        NetworkCapabilities.TRANSPORT_VPN
                    ) == true
                ) {

                    Log.d(
                        TAG,
                        "✅ Android reports an active VPN network"
                    )

                    return true
                }
            }

            Log.d(
                TAG,
                "⚠️ No active VPN detected"
            )

            false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN state check failed",
                e
            )

            false
        }
    }
}