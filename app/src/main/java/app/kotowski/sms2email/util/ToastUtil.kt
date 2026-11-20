package app.kotowski.sms2email.util

import android.content.Context
import android.widget.Toast

object ToastUtil {
    fun show(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun show(
        context: Context,
        resId: Int,
    ) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
