package app.kotowski.sms2email.di

import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.SmsProcessor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// The explicit door to the singletons that can fail to build on a broken keystore, for code that
// runs before (or without) Hilt's generated injection (Application/Activity onCreate, the
// SmsReceiver). Building any of them builds EncryptedPrefs, whose MasterKey/prefs construction
// throws when the device keystore is unavailable; only an explicit call site can catch that and
// degrade instead of crashing.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PrefsEntryPoint {
    fun prefs(): EncryptedPrefs

    fun themePreference(): ThemePreference

    fun backgroundStateReconciler(): BackgroundStateReconciler

    fun smsProcessor(): SmsProcessor
}
