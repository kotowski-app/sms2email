package app.kotowski.sms2email.ui.settings

import android.content.Context
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class FilterStringsChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `filter strings resolve to the shipped chinese text`() {
        assertEquals("消息过滤", context.getString(R.string.settings_message_filtering))
        assertEquals(
            "转发前按正则表达式过滤收到的消息。关闭时，所有消息都会被转发。",
            context.getString(R.string.settings_message_filtering_desc),
        )
        assertEquals("消息过滤器", context.getString(R.string.settings_filter_title))
        assertEquals("模式", context.getString(R.string.settings_filter_mode_label))
        assertEquals("跳过匹配项", context.getString(R.string.settings_filter_mode_skip))
        assertEquals(
            "匹配的消息将被跳过，其余消息将被转发",
            context.getString(R.string.settings_filter_mode_skip_hint),
        )
        assertEquals("转发匹配项", context.getString(R.string.settings_filter_mode_forward))
        assertEquals(
            "仅转发匹配的消息，其余消息将被跳过",
            context.getString(R.string.settings_filter_mode_forward_hint),
        )
        assertEquals("未配置规则 — 所有消息都会被转发", context.getString(R.string.settings_filter_off))
        assertEquals("跳过匹配以下条件的消息：", context.getString(R.string.settings_filter_card_skip))
        assertEquals("仅转发匹配以下条件的消息：", context.getString(R.string.settings_filter_card_forward))
        assertEquals("正文匹配 \"vip\"", context.getString(R.string.settings_filter_card_body, "vip"))
        assertEquals("发件人号码匹配 \"123\"", context.getString(R.string.settings_filter_card_sender, "123"))
        assertEquals("接收于 SIM 1, SIM 2", context.getString(R.string.settings_filter_card_slots, "SIM 1, SIM 2"))
        assertEquals("（SIM 1 或者 2）", context.getString(R.string.settings_filter_slots_group, "1 或者 2"))
        assertEquals("正文（正则表达式）", context.getString(R.string.settings_filter_body))
        assertEquals("发件人号码（正则表达式）", context.getString(R.string.settings_filter_sender))
        assertEquals(
            "正则表达式，可在文本任意位置匹配，区分大小写。使用 (?i) 忽略大小写。",
            context.getString(R.string.settings_filter_regex_hint),
        )
        assertEquals("并且", context.getString(R.string.settings_filter_and))
        assertEquals("或者", context.getString(R.string.settings_filter_or))
        assertEquals("添加规则", context.getString(R.string.settings_filter_add_rule))
        assertEquals("删除规则", context.getString(R.string.settings_filter_remove_rule))
        assertEquals("无", context.getString(R.string.settings_filter_no_conditions))
        assertEquals("SIM 2", context.getString(R.string.settings_filter_slot, 2))
        assertEquals("测试过滤器", context.getString(R.string.settings_filter_try_title))
        assertEquals("示例正文", context.getString(R.string.settings_filter_try_body))
        assertEquals("示例发件人", context.getString(R.string.settings_filter_try_sender))
        assertEquals("被此过滤器跳过", context.getString(R.string.settings_filter_try_skip))
        assertEquals("将被转发", context.getString(R.string.settings_filter_try_forward))
        assertEquals("预览", context.getString(R.string.settings_filter_preview_label))
        assertEquals("被过滤器跳过", context.getString(R.string.settings_filter_status_skipped))
        assertEquals("不匹配过滤器规则", context.getString(R.string.settings_filter_status_no_match))
        assertEquals("被过滤器跳过：1", context.getString(R.string.settings_filter_toast_skipped, 1))
        assertEquals(
            "已跳过：3 — 不匹配过滤器规则",
            context.getString(R.string.settings_filter_toast_no_match, 3),
        )
        assertEquals(
            "无效的正则表达式 — 请检查括号和特殊字符",
            context.getString(R.string.settings_filter_invalid_regex),
        )
        assertEquals(
            "过长（最多 512 字节）",
            context.getString(R.string.settings_filter_too_long, AppConfig.FILTER_FACET_MAX_BYTES),
        )
    }
}
