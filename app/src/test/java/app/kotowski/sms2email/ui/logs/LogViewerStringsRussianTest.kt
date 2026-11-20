package app.kotowski.sms2email.ui.logs

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Proves the shipped values-ru log viewer strings resolve under the Russian locale, with the
// user-approved «логи» terminology (not «журнал») and the save-to-file wording.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class LogViewerStringsRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `log viewer strings resolve to the shipped russian text`() {
        assertEquals("Логи приложения", context.getString(R.string.log_viewer_title))
        assertEquals("Копировать", context.getString(R.string.log_viewer_copy))
        assertEquals("Скопировано в буфер обмена", context.getString(R.string.log_viewer_copied))
        assertEquals("Поиск", context.getString(R.string.log_viewer_search))
        assertEquals("Поиск по сообщениям логов", context.getString(R.string.log_viewer_search_hint))
        assertEquals("Логи ещё не записаны", context.getString(R.string.log_viewer_empty))
        assertEquals("Нет логов, подходящих под запрос.", context.getString(R.string.log_viewer_no_match))
        assertEquals("Сохранить в файл", context.getString(R.string.log_viewer_export))
        assertEquals("Логи сохранены", context.getString(R.string.log_viewer_exported))
        assertEquals("Не удалось сохранить логи", context.getString(R.string.log_viewer_export_failed))
        assertEquals("Очистить", context.getString(R.string.log_viewer_clear))
        assertEquals("Очистить логи?", context.getString(R.string.log_viewer_clear_title))
        assertEquals(
            "Удалить все логи приложения? Это действие нельзя отменить.",
            context.getString(R.string.log_viewer_clear_confirm),
        )
        assertEquals("Очистить", context.getString(R.string.log_viewer_clear))
    }

    @Test
    fun `banner strings resolve with the record cap substituted`() {
        assertEquals("Ограниченный просмотр", context.getString(R.string.log_viewer_banner_title))
        assertEquals(
            "Просмотр хранит последние 500 записей, самая новая показана первой. Метки времени указаны по UTC. Чтобы прочитать все логи, убедитесь, что запись логов в файл включена, и нажмите «Сохранить в файл».",
            context.getString(R.string.log_viewer_banner_desc, 500),
        )
    }

    @Test
    fun `log to file settings strings resolve to the shipped russian text`() {
        assertEquals("Запись логов в файл", context.getString(R.string.settings_log_to_file_title))
        assertEquals(
            "Дополнительно записывать логи в файл, чтобы можно было сохранить весь журнал.",
            context.getString(R.string.settings_log_to_file_desc),
        )
        assertEquals("Максимальный размер", context.getString(R.string.settings_log_size_label))
        assertEquals(
            "Максимальный размер: 1 МБ",
            context.getString(R.string.settings_log_size_value, "1 МБ"),
        )
        assertEquals(
            "Выберите размер и нажмите «Сохранить», чтобы включить запись логов в файл.",
            context.getString(R.string.settings_log_to_file_hint),
        )
        assertEquals("Уменьшить размер логов?", context.getString(R.string.settings_log_to_file_shrink_title))
        assertEquals(
            "Файл логов будет усечён до последних 64 КБ.",
            context.getString(R.string.settings_log_to_file_shrink_detail, "64 КБ"),
        )
        assertEquals("Уменьшить", context.getString(R.string.settings_log_to_file_shrink_confirm))
        assertEquals("Выключить запись логов?", context.getString(R.string.settings_log_to_file_disable_title))
        assertEquals(
            "Файл логов будет удалён. Сначала сохраните его в файл, если хотите оставить копию.",
            context.getString(R.string.settings_log_to_file_disable_detail),
        )
        assertEquals("Выключить", context.getString(R.string.settings_log_to_file_disable_confirm))
        assertEquals(
            "Сначала включите запись логов в файл.",
            context.getString(R.string.log_viewer_enable_toast),
        )
        assertEquals("64 КБ", context.getString(R.string.log_size_64kb))
        assertEquals("256 КБ", context.getString(R.string.log_size_256kb))
        assertEquals("1 МБ", context.getString(R.string.log_size_1mb))
        assertEquals("4 МБ", context.getString(R.string.log_size_4mb))
        assertEquals("16 МБ", context.getString(R.string.log_size_16mb))
    }

    @Test
    fun `log level settings strings resolve to the shipped russian text`() {
        assertEquals("Уровень логов", context.getString(R.string.settings_log_level_label))
        assertEquals(
            "Записываются только записи этого уровня и серьёзнее, более подробные пропускаются. «Выкл» ничего не записывает.",
            context.getString(R.string.settings_log_level_desc),
        )
        assertEquals("Отладка", context.getString(R.string.log_level_debug))
        assertEquals("Информация", context.getString(R.string.log_level_info))
        assertEquals("Предупреждение", context.getString(R.string.log_level_warning))
        assertEquals("Ошибка", context.getString(R.string.log_level_error))
        assertEquals("Выкл", context.getString(R.string.option_off))
    }
}
