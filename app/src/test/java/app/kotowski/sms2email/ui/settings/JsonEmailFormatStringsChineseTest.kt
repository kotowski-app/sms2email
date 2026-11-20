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
@Config(sdk = [34], qualifiers = "zh")
class JsonEmailFormatStringsChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `json email format toggle strings resolve to the shipped chinese text`() {
        assertEquals("JSON 邮件格式", context.getString(R.string.settings_json_email_format))
        assertEquals(
            "将短信内容以结构化 JSON 而非纯文本发送。用于自动化处理。",
            context.getString(R.string.settings_json_email_format_desc),
        )
    }
}
