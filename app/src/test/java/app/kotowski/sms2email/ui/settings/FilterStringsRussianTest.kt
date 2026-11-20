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
@Config(sdk = [34], qualifiers = "ru")
class FilterStringsRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `filter strings resolve to the shipped russian text`() {
        // The feature toggle in the Advanced section (the explicit opt-in).
        assertEquals("Фильтрация сообщений", context.getString(R.string.settings_message_filtering))
        assertEquals(
            "Фильтрует входящие сообщения по регулярному выражению перед пересылкой. Пока выключена, пересылаются все сообщения.",
            context.getString(R.string.settings_message_filtering_desc),
        )
        assertEquals("Фильтр сообщений", context.getString(R.string.settings_filter_title))
        assertEquals("Режим", context.getString(R.string.settings_filter_mode_label))
        assertEquals("Пропускать совпадения", context.getString(R.string.settings_filter_mode_skip))
        assertEquals(
            "Совпадающие сообщения пропускаются, всё остальное пересылается",
            context.getString(R.string.settings_filter_mode_skip_hint),
        )
        assertEquals("Пересылать совпадения", context.getString(R.string.settings_filter_mode_forward))
        assertEquals(
            "Пересылаются только совпадающие сообщения, всё остальное пропускается",
            context.getString(R.string.settings_filter_mode_forward_hint),
        )
        assertEquals("Правила не настроены — все сообщения пересылаются", context.getString(R.string.settings_filter_off))
        assertEquals("Пропускает сообщения, которые совпадают:", context.getString(R.string.settings_filter_card_skip))
        assertEquals("Пересылает только сообщения, которые совпадают:", context.getString(R.string.settings_filter_card_forward))
        assertEquals("текст совпадает с \"vip\"", context.getString(R.string.settings_filter_card_body, "vip"))
        assertEquals("номер отправителя совпадает с \"123\"", context.getString(R.string.settings_filter_card_sender, "123"))
        assertEquals("получено на SIM 1, SIM 2", context.getString(R.string.settings_filter_card_slots, "SIM 1, SIM 2"))
        assertEquals("(SIM 1 ИЛИ 2)", context.getString(R.string.settings_filter_slots_group, "1 ИЛИ 2"))
        assertEquals("Текст (regex)", context.getString(R.string.settings_filter_body))
        assertEquals("Номер отправителя (regex)", context.getString(R.string.settings_filter_sender))
        assertEquals(
            "Регулярные выражения: совпадение в любом месте текста, с учётом регистра. (?i) — игнорировать регистр.",
            context.getString(R.string.settings_filter_regex_hint),
        )
        assertEquals("И", context.getString(R.string.settings_filter_and))
        assertEquals("ИЛИ", context.getString(R.string.settings_filter_or))
        assertEquals("Добавить правило", context.getString(R.string.settings_filter_add_rule))
        assertEquals("Удалить правило", context.getString(R.string.settings_filter_remove_rule))
        assertEquals("без условий", context.getString(R.string.settings_filter_no_conditions))
        assertEquals("SIM 2", context.getString(R.string.settings_filter_slot, 2))
        assertEquals("Проверьте фильтр", context.getString(R.string.settings_filter_try_title))
        assertEquals("Пример текста", context.getString(R.string.settings_filter_try_body))
        assertEquals("Пример отправителя", context.getString(R.string.settings_filter_try_sender))
        assertEquals("Пропущено этим фильтром", context.getString(R.string.settings_filter_try_skip))
        assertEquals("Будет переслано", context.getString(R.string.settings_filter_try_forward))
        assertEquals("Предпросмотр", context.getString(R.string.settings_filter_preview_label))
        assertEquals("Пропущено фильтром", context.getString(R.string.settings_filter_status_skipped))
        assertEquals("Не соответствует правилам фильтра", context.getString(R.string.settings_filter_status_no_match))
        // The count toasts (the count stands alone in the construction).
        assertEquals("Пропущено фильтром: 1", context.getString(R.string.settings_filter_toast_skipped, 1))
        assertEquals(
            "Пропущено: 3 — не соответствует правилам фильтра",
            context.getString(R.string.settings_filter_toast_no_match, 3),
        )
        assertEquals(
            "Неверное регулярное выражение — проверьте скобки и спецсимволы",
            context.getString(R.string.settings_filter_invalid_regex),
        )
        assertEquals(
            "Слишком длинно (макс. 512 байт)",
            context.getString(R.string.settings_filter_too_long, AppConfig.FILTER_FACET_MAX_BYTES),
        )
    }
}
