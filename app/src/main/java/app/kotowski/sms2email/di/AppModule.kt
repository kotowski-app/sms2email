package app.kotowski.sms2email.di

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.work.WorkManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.Sms2EmailDatabase
import app.kotowski.sms2email.data.database.SmsDao
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.NetworkCheck
import app.kotowski.sms2email.system.NetworkUtil
import app.kotowski.sms2email.system.OpenAppIntentProvider
import app.kotowski.sms2email.ui.MainActivity
import app.kotowski.sms2email.work.CleanupWorkerManager
import app.kotowski.sms2email.work.ExactWakeArmer
import app.kotowski.sms2email.work.SmsRetryScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): Sms2EmailDatabase =
        Room
            .databaseBuilder(
                context,
                Sms2EmailDatabase::class.java,
                "sms2email_db",
            ).build()

    @Provides
    @Singleton
    fun provideSmsDao(database: Sms2EmailDatabase): SmsDao = database.smsDao()

    @Provides
    @Singleton
    fun provideCleanupWorkerManager(
        @ApplicationContext context: Context,
    ): CleanupWorkerManager = CleanupWorkerManager(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun provideSmsRetryScheduler(
        @ApplicationContext context: Context,
        smsRepository: SmsRepository,
        prefs: EncryptedPrefs,
        exactWakeArmer: ExactWakeArmer,
    ): SmsRetryScheduler = SmsRetryScheduler(context, smsRepository, prefs, exactWakeArmer)

    // The production network gate of SmtpTester; the tests build the tester
    // with their own check instead of going through the graph.
    @Provides
    @Singleton
    fun provideSmtpNetworkCheck(): NetworkCheck = NetworkCheck { NetworkUtil.hasReliableInternet(it) }

    // The notification's open-app target: the composition root is where the
    // system→ui edge is allowed to land, system/ itself stays ui-free.
    @Provides
    @Singleton
    fun provideOpenAppIntentProvider(): OpenAppIntentProvider =
        OpenAppIntentProvider { context -> Intent(context, MainActivity::class.java) }

    // App-wide background scope for long-running collectors that do I/O (Room, WorkManager).
    // IO so their DB work never runs on the main thread.
    @Provides
    @Singleton
    fun provideBackgroundScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The live-path ingest retry delay (SmsProcessor): through the graph so the unit tests
    // can run the retry chain on a short delay instead of waiting it out.
    @Provides
    fun provideIngestRetryDelayMs(): Long = AppConfig.INGEST_RETRY_DELAY_MS
}
