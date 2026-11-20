package app.kotowski.sms2email.ui.settings

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class JsonEmailFormatStringsRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `json email format toggle strings resolve to the shipped russian text`() {
        assertEquals("JSON-формат письма", context.getString(R.string.settings_json_email_format))
        assertEquals(
            "Отправляет содержимое SMS в виде структурированного JSON вместо обычного текста. Предназначено для автоматической обработки.",
            context.getString(R.string.settings_json_email_format_desc),
        )
    }
}
