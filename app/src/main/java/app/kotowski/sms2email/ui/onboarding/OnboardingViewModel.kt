package app.kotowski.sms2email.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.SettingsImporter
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.ui.emailconfig.EmailConfig
import app.kotowski.sms2email.ui.emailconfig.EmailConfigController
import app.kotowski.sms2email.ui.emailconfig.isEmailFormComplete
import app.kotowski.sms2email.ui.emailconfig.showTestResultToast
import app.kotowski.sms2email.util.EmailUtil
import app.kotowski.sms2email.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val config: EmailConfig = EmailConfig(),
    val smtpTestSucceeded: Boolean = false,
    val testEmailSucceeded: Boolean = false,
    val onboardingComplete: Boolean = false,
    val alreadyConfigured: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val prefs: EncryptedPrefs,
        private val testRunner: SmtpTestRunner,
        private val contactResolver: ContactResolver,
        private val simDetector: SimDetector,
        // Exposed so the screen calls the manager and the shared request flow directly,
        // no delegates here.
        val permissionManager: PermissionManager,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        val isSingleSimDevice: Boolean = simDetector.isSingleSimDevice()

        // Exposed so the screen calls the form's field handlers directly, no per-field
        // delegates here.
        val form =
            EmailConfigController(appContext, prefs, testRunner, contactResolver)

        // The settings import: the read and the apply run on the ViewModel's scope (not the
        // composition's), so an in-flight import survives a rotation and is dropped only
        // when the wizard's entry is left.
        val importRunner =
            SettingsImporter(appContext, prefs) { form.reloadFromPrefs(keepTestRuns = true) }

        private val onboardingCompleteState = MutableStateFlow(false)

        private val alreadyConfigured: Boolean = prefs.isOnboardingComplete

        // Seeded through the form's own persist path (its toggle-on handler): it writes both
        // the flag and the device-name prefix to the draft and to the prefs. Persisting only
        // the flag would drift the stored config (auto on, prefix empty) and the send path
        // reads the prefix alone - every subject would lose it, e.g. when onboarding is skipped.
        init {
            if (prefs.load().subjectPrefix.isEmpty() && !prefs.isOnboardingComplete) {
                form.onAutoSubjectPrefixChanged(true)
            }
        }

        val state: StateFlow<OnboardingUiState> =
            form.state
                .combine(onboardingCompleteState, ::toUiState)
                .stateIn(viewModelScope, SharingStarted.Eagerly, toUiState(form.state.value, false))

        fun testConnection() {
            viewModelScope.launch {
                showTestResultToast(appContext, form.runTestConnection())
            }
        }

        fun sendTestEmail() {
            viewModelScope.launch {
                showTestResultToast(appContext, form.runTestEmail())
            }
        }

        fun importPicked(uri: Uri): Job? =
            importRunner.importPicked(viewModelScope, uri) { success ->
                ToastUtil.show(appContext, if (success) R.string.config_import_success else R.string.config_import_failed)
            }

        fun tryAutoFillEmail() {
            val current = form.state.value
            val settings = current.settings
            if (settings.senderEmail.isEmpty() && settings.smtpHost.isNotBlank() && settings.smtpUsername.isNotBlank()) {
                val derived = EmailUtil.deriveSenderEmail(settings.smtpUsername, settings.smtpHost)
                if (derived.isNotEmpty()) {
                    form.update {
                        it.copy(
                            settings =
                                it.settings.copy(
                                    senderEmail = derived,
                                    destEmail = it.settings.destEmail.ifEmpty { derived },
                                ),
                        )
                    }
                }
            }
        }

        fun skipOnboarding() {
            viewModelScope.launch {
                prefs.update { it.copy(isForwardingEnabled = false) }
                prefs.isOnboardingComplete = true
                onboardingCompleteState.value = true
            }
        }

        fun completeOnboarding(): Boolean {
            // The wizard's completeness rule (the live form, shared with the settings
            // save gate): a red or empty port field refuses the finish alike.
            if (!isEmailFormComplete(form.state.value)) {
                return false
            }
            viewModelScope.launch {
                form.commitDraft()
                prefs.update { it.copy(isForwardingEnabled = AppSettings.DEFAULT_FORWARDING_ENABLED) }
                prefs.isOnboardingComplete = true
                onboardingCompleteState.value = true
            }
            return true
        }

        private fun toUiState(
            config: EmailConfig,
            onboardingComplete: Boolean,
        ): OnboardingUiState =
            OnboardingUiState(
                config = config,
                smtpTestSucceeded = config.test.smtpTestResult is EmailResult.Success,
                testEmailSucceeded = config.test.testEmailResult is EmailResult.Success,
                onboardingComplete = onboardingComplete,
                alreadyConfigured = alreadyConfigured,
            )
    }
