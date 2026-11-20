package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// The values-ru strings shown for failed and retrying messages (mapped by
// SmtpErrorMapper) must resolve under the Russian locale.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class SmtpErrorStringsRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `generic smtp error strings resolve to the shipped russian text`() {
        assertEquals(
            "Не удалось выполнить авторизацию. Проверьте e-mail и пароль или убедитесь, что включён пароль приложения.",
            context.getString(R.string.smtp_auth_failed),
        )
        assertEquals(
            "Соединение прервалось по таймауту. Проверьте имя сервера и порт, затем повторите попытку.",
            context.getString(R.string.smtp_timeout),
        )
        assertEquals("Ошибка сети. Проверьте подключение к интернету.", context.getString(R.string.smtp_network_error))
        assertEquals("Произошла непредвиденная ошибка при отправке письма.", context.getString(R.string.smtp_unknown))
        assertEquals(
            "Не удалось разрешить адрес сервера (DNS). Проверьте имя сервера.",
            context.getString(R.string.smtp_dns_error),
        )
        assertEquals(
            "Соединение отклонено или прервано по таймауту. Проверьте настройки файрвола и порта.",
            context.getString(R.string.smtp_connection_error),
        )
        assertEquals("Сеть недоступна. Проверьте подключение к интернету.", context.getString(R.string.smtp_socket_error))
        assertEquals(
            "Ошибка SSL/TLS. Убедитесь, что сервер поддерживает TLS.",
            context.getString(R.string.smtp_ssl_port_error),
        )
    }

    @Test
    fun `enhanced status code strings resolve to the shipped russian text`() {
        assertEquals("Адрес получателя не существует.", context.getString(R.string.smtp_enh_5_1_1))
        assertEquals("Домен получателя не найден.", context.getString(R.string.smtp_enh_5_1_2))
        assertEquals("Неверный формат адреса получателя.", context.getString(R.string.smtp_enh_5_1_3))
        assertEquals(
            "Адрес получателя используется несколькими почтовыми ящиками.",
            context.getString(R.string.smtp_enh_5_1_4),
        )
        assertEquals("Адрес получателя перемещён.", context.getString(R.string.smtp_enh_5_1_6))
        assertEquals("Почтовый ящик получателя временно отключён.", context.getString(R.string.smtp_enh_4_2_1))
        assertEquals("Почтовый ящик получателя переполнен.", context.getString(R.string.smtp_enh_5_2_2))
        assertEquals("Сообщение или вложение слишком велико.", context.getString(R.string.smtp_enh_5_2_3))
        assertEquals("На сервере получателя закончилось место на диске.", context.getString(R.string.smtp_enh_4_3_1))
        assertEquals(
            "Сообщение превышает лимит размера для сети сервера.",
            context.getString(R.string.smtp_enh_5_3_4),
        )
        assertEquals("Сервер получателя не отвечает. Повторите попытку позже.", context.getString(R.string.smtp_enh_4_4_1))
        assertEquals(
            "Истекло время ожидания; сервер получателя был недоступен.",
            context.getString(R.string.smtp_enh_4_4_7),
        )
        assertEquals(
            "Письмо отклонено согласно политике безопасности получателя.",
            context.getString(R.string.smtp_enh_5_7_0),
        )
        assertEquals(
            "Письмо заблокировано: отправитель не авторизован (ошибка анти-спам-фильтра или DMARC).",
            context.getString(R.string.smtp_enh_5_7_1),
        )
        assertEquals("Развёртывание списка рассылки запрещено.", context.getString(R.string.smtp_enh_5_7_2))
        assertEquals("Неверные учётные данные для авторизации.", context.getString(R.string.smtp_enh_5_7_8))
        assertEquals(
            "Ошибка авторизации (требуется пароль приложения или проблема с двухфакторной аутентификацией).",
            context.getString(R.string.smtp_enh_5_7_9),
        )
        assertEquals(
            "Требуется шифрование (TLS), но оно не поддерживается получателем.",
            context.getString(R.string.smtp_enh_5_7_11),
        )
    }

    @Test
    fun `three digit code strings resolve to the shipped russian text`() {
        assertEquals("Сервер готов.", context.getString(R.string.smtp_220))
        assertEquals("Защищённое соединение (TLS) временно недоступно.", context.getString(R.string.smtp_454))
        assertEquals("Неверные параметры соединения.", context.getString(R.string.smtp_501))
        assertEquals("Требуется шифрование, но его согласование не удалось.", context.getString(R.string.smtp_523))
        assertEquals(
            "Требуется защищённое соединение; STARTTLS не включён.",
            context.getString(R.string.smtp_530),
        )
        assertEquals(
            "Для выбранного метода аутентификации требуется шифрование.",
            context.getString(R.string.smtp_538),
        )
        assertEquals("Авторизация выполнена успешно.", context.getString(R.string.smtp_235))
        assertEquals("Получен запрос сервера на аутентификацию.", context.getString(R.string.smtp_334))
        assertEquals("Пароль от почты истёк или требует обновления.", context.getString(R.string.smtp_432))
        assertEquals(
            "Сервер отклонил вход: метод безопасности слишком слаб.",
            context.getString(R.string.smtp_534),
        )
        assertEquals("Ошибка авторизации: неверный e-mail или пароль.", context.getString(R.string.smtp_535))
        assertEquals(
            "Почтовый сервер временно недоступен. Повторите попытку позже.",
            context.getString(R.string.smtp_421),
        )
        assertEquals("На сервере закончилось место или память.", context.getString(R.string.smtp_431))
        assertEquals("Соединение разорвано во время передачи.", context.getString(R.string.smtp_442))
        assertEquals("Почтовый ящик получателя временно занят.", context.getString(R.string.smtp_450))
        assertEquals(
            "Возникла временная ошибка локального сервера. Повторите попытку позже.",
            context.getString(R.string.smtp_451),
        )
        assertEquals(
            "На сервере или в почтовом ящике получателя закончилось место.",
            context.getString(R.string.smtp_452),
        )
        assertEquals("Возникла временная ошибка параметров сервера.", context.getString(R.string.smtp_455))
        assertEquals("Ошибка локального анти-спам-фильтра.", context.getString(R.string.smtp_471))
        assertEquals("Сервер не распознал команду.", context.getString(R.string.smtp_500))
        assertEquals("Сервер не поддерживает запрошенную возможность.", context.getString(R.string.smtp_502))
        assertEquals("Неверная последовательность команд.", context.getString(R.string.smtp_503))
        assertEquals("Параметр команды не поддерживается.", context.getString(R.string.smtp_504))
        assertEquals("Адрес получателя неверен.", context.getString(R.string.smtp_510))
        assertEquals("Адрес получателя неверен.", context.getString(R.string.smtp_511))
        assertEquals("Домен получателя не найден (ошибка DNS).", context.getString(R.string.smtp_512))
        assertEquals("Неверный тип адреса или синтаксическая ошибка.", context.getString(R.string.smtp_513))
        assertEquals(
            "Почтовый сервер настроен на отклонение входящих писем.",
            context.getString(R.string.smtp_521),
        )
        assertEquals("Сервер получателя отклонил письмо (анти-спам-фильтр).", context.getString(R.string.smtp_541))
        assertEquals(
            "Получатель не существует или письмо заблокировано.",
            context.getString(R.string.smtp_550),
        )
        assertEquals(
            "Получатель не является локальным; пересылка не удалась.",
            context.getString(R.string.smtp_551),
        )
        assertEquals(
            "Почтовый ящик получателя переполнен или письмо слишком велико.",
            context.getString(R.string.smtp_552),
        )
        assertEquals("Адрес получателя синтаксически неверен.", context.getString(R.string.smtp_553))
        assertEquals(
            "Письмо отклонено (спам, ограничение политики или чёрный список).",
            context.getString(R.string.smtp_554),
        )
        assertEquals("Параметры письма не распознаны.", context.getString(R.string.smtp_555))
        assertEquals("Домен получателя не принимает письма.", context.getString(R.string.smtp_556))
        assertEquals(
            "Сообщение отклонено строгим правилом или анти-спам-фильтром.",
            context.getString(R.string.smtp_571),
        )
    }

    @Test
    fun `fallback strings resolve to the shipped russian text with the code substituted`() {
        assertEquals("Временная ошибка сервера (499). Повторите попытку позже.", context.getString(R.string.smtp_transient_fallback, 499))
        assertEquals(
            "Окончательная ошибка сервера (599). Не удалось отправить письмо.",
            context.getString(R.string.smtp_permanent_fallback, 599),
        )
    }
}
