package app.kotowski.sms2email.ui.onboarding

import androidx.compose.runtime.saveable.Saver

/**
 * The onboarding wizard steps in navigation order.
 *
 * The proceed rule of each step lives on the step itself; the declaration order
 * drives the previous/next/isLast helpers and the dot indicator (via entries).
 */
enum class WizardStep {
    WELCOME,
    PERMISSIONS,
    SMTP,
    SENDER,
    DONE,
    ;

    fun canProceed(
        permissionsGranted: Boolean = true,
        smtpSucceeded: Boolean = false,
        testEmailSucceeded: Boolean = false,
    ): Boolean =
        when (this) {
            WELCOME -> true
            PERMISSIONS -> permissionsGranted
            SMTP -> smtpSucceeded
            SENDER -> testEmailSucceeded
            DONE -> true
        }

    val previous: WizardStep?
        get() = entries.getOrNull(ordinal - 1)

    val next: WizardStep?
        get() = entries.getOrNull(ordinal + 1)

    val isLast: Boolean
        get() = ordinal == entries.lastIndex

    companion object {
        /**
         * The step's saved state for [rememberSaveable] across config changes.
         *
         * Saved by name, not ordinal: a reordered enum must not teleport the user to
         * the wrong step, and an unknown value degrades to WELCOME (same rule as
         * [SmtpProtocol.fromStored]).
         */
        val Saver: Saver<WizardStep, String> =
            Saver(
                save = { it.name },
                restore = { saved -> entries.firstOrNull { step -> step.name == saved } ?: WELCOME },
            )
    }
}
