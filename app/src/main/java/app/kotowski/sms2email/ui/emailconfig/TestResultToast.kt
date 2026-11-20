package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.util.ToastUtil

// The success/failure toast of the SMTP/sender test runs (Settings and Onboarding):
// fired once, by the ViewModel, when a run completes — the result state alone never
// triggers it, so a re-entry or a rotation can't re-show a stale result.
internal fun showTestResultToast(
    context: Context,
    result: EmailResult?,
) {
    val message =
        when (result) {
            null -> return
            is EmailResult.Success -> context.getString(R.string.email_config_test_success)
            is EmailResult.Failure -> result.message
        }
    ToastUtil.show(context, message)
}
