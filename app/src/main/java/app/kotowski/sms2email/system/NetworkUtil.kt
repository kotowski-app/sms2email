package app.kotowski.sms2email.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.kotowski.sms2email.R

fun NetworkCapabilities.isReliableInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

object NetworkUtil {
    fun hasReliableInternet(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)?.isReliableInternet() ?: false
    }

    fun checkNetwork(context: Context): String? {
        if (!hasReliableInternet(context)) {
            return context.getString(R.string.error_waiting_for_internet)
        }
        return null
    }
}
