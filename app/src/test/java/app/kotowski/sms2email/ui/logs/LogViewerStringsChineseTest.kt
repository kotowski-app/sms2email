package app.kotowski.sms2email.ui.logs

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Proves the shipped values-zh log viewer strings resolve under the Chinese
// locale, with the save-to-file wording.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class LogViewerStringsChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `log viewer strings resolve to the shipped chinese text`() {
        assertEquals("应用日志", context.getString(R.string.log_viewer_title))
        assertEquals("复制", context.getString(R.string.log_viewer_copy))
        assertEquals("已复制到剪贴板", context.getString(R.string.log_viewer_copied))
        assertEquals("搜索", context.getString(R.string.log_viewer_search))
        assertEquals("搜索日志消息", context.getString(R.string.log_viewer_search_hint))
        assertEquals("尚未捕获日志", context.getString(R.string.log_viewer_empty))
        assertEquals("没有符合搜索条件的日志。", context.getString(R.string.log_viewer_no_match))
        assertEquals("保存到文件", context.getString(R.string.log_viewer_export))
        assertEquals("日志已保存", context.getString(R.string.log_viewer_exported))
        assertEquals("日志保存失败", context.getString(R.string.log_viewer_export_failed))
        assertEquals("清除", context.getString(R.string.log_viewer_clear))
        assertEquals("清除日志？", context.getString(R.string.log_viewer_clear_title))
        assertEquals(
            "删除所有应用日志？此操作无法撤销。",
            context.getString(R.string.log_viewer_clear_confirm),
        )
    }

    @Test
    fun `banner strings resolve with the record cap substituted`() {
        assertEquals("受限视图", context.getString(R.string.log_viewer_banner_title))
        assertEquals(
            "查看器保留最近 500 条记录，并按最新在前显示。时间戳为 UTC 时间。如需阅读完整日志，请确保已启用日志写入文件，然后点击“保存到文件”。",
            context.getString(R.string.log_viewer_banner_desc, 500),
        )
    }

    @Test
    fun `log to file settings strings resolve to the shipped chinese text`() {
        assertEquals("日志写入文件", context.getString(R.string.settings_log_to_file_title))
        assertEquals(
            "同时将日志条目保存到文件，以便导出完整历史。",
            context.getString(R.string.settings_log_to_file_desc),
        )
        assertEquals("最大日志大小", context.getString(R.string.settings_log_size_label))
        assertEquals(
            "最大日志大小：1 MB",
            context.getString(R.string.settings_log_size_value, "1 MB"),
        )
        assertEquals(
            "选择大小并点击“保存”以启用日志写入文件。",
            context.getString(R.string.settings_log_to_file_hint),
        )
        assertEquals("减小日志大小？", context.getString(R.string.settings_log_to_file_shrink_title))
        assertEquals(
            "日志文件将被截断至最近 64 KB。",
            context.getString(R.string.settings_log_to_file_shrink_detail, "64 KB"),
        )
        assertEquals("减小", context.getString(R.string.settings_log_to_file_shrink_confirm))
        assertEquals("关闭日志写入文件？", context.getString(R.string.settings_log_to_file_disable_title))
        assertEquals(
            "日志文件将被删除。如果您想保留副本，请先将其保存到文件。",
            context.getString(R.string.settings_log_to_file_disable_detail),
        )
        assertEquals("关闭", context.getString(R.string.settings_log_to_file_disable_confirm))
        assertEquals(
            "请先启用日志写入文件",
            context.getString(R.string.log_viewer_enable_toast),
        )
        assertEquals("64 KB", context.getString(R.string.log_size_64kb))
        assertEquals("256 KB", context.getString(R.string.log_size_256kb))
        assertEquals("1 MB", context.getString(R.string.log_size_1mb))
        assertEquals("4 MB", context.getString(R.string.log_size_4mb))
        assertEquals("16 MB", context.getString(R.string.log_size_16mb))
    }

    @Test
    fun `log level settings strings resolve to the shipped chinese text`() {
        assertEquals("日志级别", context.getString(R.string.settings_log_level_label))
        assertEquals(
            "仅记录此级别及更严重的条目，更详细的条目将被跳过。“关闭”不记录任何内容。",
            context.getString(R.string.settings_log_level_desc),
        )
        assertEquals("调试", context.getString(R.string.log_level_debug))
        assertEquals("信息", context.getString(R.string.log_level_info))
        assertEquals("警告", context.getString(R.string.log_level_warning))
        assertEquals("错误", context.getString(R.string.log_level_error))
        assertEquals("关闭", context.getString(R.string.option_off))
    }
}
