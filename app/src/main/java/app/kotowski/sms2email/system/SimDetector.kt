package app.kotowski.sms2email.system

import android.content.Context
import android.telephony.TelephonyManager
import app.kotowski.sms2email.util.AndroidVersion
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimDetector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var cachedSupportedModemCount: Int? = null

        fun getSupportedModemCount(): Int {
            if (cachedSupportedModemCount != null) {
                return cachedSupportedModemCount!!
            }
            return try {
                val telephonyManager =
                    context.getSystemService(TelephonyManager::class.java)
                        ?: run {
                            cachedSupportedModemCount = 1
                            return 1
                        }

                val count =
                    if (AndroidVersion.is11Plus()) {
                        telephonyManager.supportedModemCount
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.phoneCount
                    }

                cachedSupportedModemCount = count
                count
            } catch (e: Exception) {
                Logger.w("SimDetector", "Failed to get supported modem count: ${e.message}, defaulting to 1")
                cachedSupportedModemCount = 1
                1
            }
        }

        fun isSingleSimDevice(): Boolean = getSupportedModemCount() <= 1
    }
