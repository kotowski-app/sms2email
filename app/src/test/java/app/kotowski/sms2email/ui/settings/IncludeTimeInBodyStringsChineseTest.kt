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
class IncludeTimeInBodyStringsChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `include time in body toggle strings resolve to the shipped chinese text`() {
        assertEquals("在邮件中包含时间", context.getString(R.string.settings_include_time_in_body))
        assertEquals(
            "将消息接收时间作为邮件的第一行。显示语言与应用语言一致，并遵循设备的 12/24 小时制格式。",
            context.getString(R.string.settings_include_time_in_body_desc),
        )
    }
}
