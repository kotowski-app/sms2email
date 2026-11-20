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
class IncludeTimeInBodyStringsRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `include time in body toggle strings resolve to the shipped russian text`() {
        assertEquals("Отображать время в письме", context.getString(R.string.settings_include_time_in_body))
        assertEquals(
            "Добавляет время получения сообщения первой строкой письма. Отображается на языке приложения и с учётом 12/24-часового формата устройства.",
            context.getString(R.string.settings_include_time_in_body_desc),
        )
    }
}
