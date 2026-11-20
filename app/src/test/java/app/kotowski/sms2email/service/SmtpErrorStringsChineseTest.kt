package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// The values-zh strings shown for failed and retrying messages (mapped by
// SmtpErrorMapper) must resolve under the Chinese locale.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class SmtpErrorStringsChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `generic smtp error strings resolve to the shipped chinese text`() {
        assertEquals(
            "身份验证失败。请检查邮箱和密码，或确保已启用授权码（应用专用密码）。",
            context.getString(R.string.smtp_auth_failed),
        )
        assertEquals(
            "连接超时。请检查服务器地址和端口，然后重试。",
            context.getString(R.string.smtp_timeout),
        )
        assertEquals("网络错误。请检查您的网络连接。", context.getString(R.string.smtp_network_error))
        assertEquals("发送邮件时发生未知错误。", context.getString(R.string.smtp_unknown))
        assertEquals(
            "DNS 解析失败。请检查服务器地址。",
            context.getString(R.string.smtp_dns_error),
        )
        assertEquals(
            "连接被拒绝或超时。请检查防火墙和端口设置。",
            context.getString(R.string.smtp_connection_error),
        )
        assertEquals("无法访问网络。请检查您的网络连接。", context.getString(R.string.smtp_socket_error))
        assertEquals(
            "SSL/TLS 错误。请确保您的服务器支持 TLS。",
            context.getString(R.string.smtp_ssl_port_error),
        )
    }

    @Test
    fun `enhanced status code strings resolve to the shipped chinese text`() {
        assertEquals("收件人邮箱地址不存在。", context.getString(R.string.smtp_enh_5_1_1))
        assertEquals("找不到收件人域名。", context.getString(R.string.smtp_enh_5_1_2))
        assertEquals("收件人邮箱地址格式无效。", context.getString(R.string.smtp_enh_5_1_3))
        assertEquals(
            "收件人邮箱地址由多个邮箱共用。",
            context.getString(R.string.smtp_enh_5_1_4),
        )
        assertEquals("收件人邮箱地址已更改。", context.getString(R.string.smtp_enh_5_1_6))
        assertEquals("收件人邮箱暂时不可用。", context.getString(R.string.smtp_enh_4_2_1))
        assertEquals("收件人邮箱已满。", context.getString(R.string.smtp_enh_5_2_2))
        assertEquals("您的消息或附件过大。", context.getString(R.string.smtp_enh_5_2_3))
        assertEquals("收件人邮件服务器磁盘空间不足。", context.getString(R.string.smtp_enh_4_3_1))
        assertEquals(
            "消息过大，超出服务器网络限制。",
            context.getString(R.string.smtp_enh_5_3_4),
        )
        assertEquals("收件人服务器无响应。请稍后重试。", context.getString(R.string.smtp_enh_4_4_1))
        assertEquals(
            "消息超时；无法访问收件人服务器。",
            context.getString(R.string.smtp_enh_4_4_7),
        )
        assertEquals(
            "邮件因收件人的安全策略被拒绝。",
            context.getString(R.string.smtp_enh_5_7_0),
        )
        assertEquals(
            "邮件被阻止：发件人未授权（反垃圾邮件或 DMARC 失败）。",
            context.getString(R.string.smtp_enh_5_7_1),
        )
        assertEquals("禁止展开邮件列表。", context.getString(R.string.smtp_enh_5_7_2))
        assertEquals("身份验证凭据无效。", context.getString(R.string.smtp_enh_5_7_8))
        assertEquals(
            "身份验证失败（需要授权码或双因素认证问题）。",
            context.getString(R.string.smtp_enh_5_7_9),
        )
        assertEquals(
            "需要加密（TLS），但收件人不支持。",
            context.getString(R.string.smtp_enh_5_7_11),
        )
    }

    @Test
    fun `three digit code strings resolve to the shipped chinese text`() {
        assertEquals("服务器已就绪。", context.getString(R.string.smtp_220))
        assertEquals("安全连接（TLS）暂时不可用。", context.getString(R.string.smtp_454))
        assertEquals("连接参数无效。", context.getString(R.string.smtp_501))
        assertEquals("需要加密，但协商失败。", context.getString(R.string.smtp_523))
        assertEquals(
            "需要安全连接；未启用 STARTTLS。",
            context.getString(R.string.smtp_530),
        )
        assertEquals(
            "所请求的身份验证方法需要加密。",
            context.getString(R.string.smtp_538),
        )
        assertEquals("身份验证成功。", context.getString(R.string.smtp_235))
        assertEquals("已收到服务器的身份验证质询。", context.getString(R.string.smtp_334))
        assertEquals("邮箱密码已过期或需要更新。", context.getString(R.string.smtp_432))
        assertEquals(
            "服务器拒绝登录：安全方法过于薄弱。",
            context.getString(R.string.smtp_534),
        )
        assertEquals("身份验证失败：邮箱或密码无效。", context.getString(R.string.smtp_535))
        assertEquals(
            "邮件服务器暂时不可用。请稍后重试。",
            context.getString(R.string.smtp_421),
        )
        assertEquals("服务器空间或内存不足。", context.getString(R.string.smtp_431))
        assertEquals("传输过程中连接中断。", context.getString(R.string.smtp_442))
        assertEquals("收件人邮箱暂时忙碌。", context.getString(R.string.smtp_450))
        assertEquals(
            "发生临时本地服务器错误。请稍后重试。",
            context.getString(R.string.smtp_451),
        )
        assertEquals(
            "服务器或收件人邮箱存储空间不足。",
            context.getString(R.string.smtp_452),
        )
        assertEquals("发生临时服务器参数错误。", context.getString(R.string.smtp_455))
        assertEquals("发生本地反垃圾邮件过滤器错误。", context.getString(R.string.smtp_471))
        assertEquals("服务器无法识别该命令。", context.getString(R.string.smtp_500))
        assertEquals("服务器不支持所请求的功能。", context.getString(R.string.smtp_502))
        assertEquals("命令顺序错误。", context.getString(R.string.smtp_503))
        assertEquals("不支持该命令参数。", context.getString(R.string.smtp_504))
        assertEquals("收件人邮箱地址无效。", context.getString(R.string.smtp_510))
        assertEquals("收件人邮箱地址无效。", context.getString(R.string.smtp_511))
        assertEquals("找不到收件人域名（DNS 错误）。", context.getString(R.string.smtp_512))
        assertEquals("地址类型不正确或包含语法错误。", context.getString(R.string.smtp_513))
        assertEquals(
            "邮件服务器已设置为拒绝收到的邮件。",
            context.getString(R.string.smtp_521),
        )
        assertEquals("收件人服务器拒绝了该邮件（垃圾邮件过滤器）。", context.getString(R.string.smtp_541))
        assertEquals(
            "收件人不存在，或邮件被阻止。",
            context.getString(R.string.smtp_550),
        )
        assertEquals(
            "收件人不是本地的；转发失败。",
            context.getString(R.string.smtp_551),
        )
        assertEquals(
            "收件人邮箱已满，或消息过大。",
            context.getString(R.string.smtp_552),
        )
        assertEquals("收件人邮箱地址语法无效。", context.getString(R.string.smtp_553))
        assertEquals(
            "邮件被拒绝（垃圾邮件、策略限制或黑名单）。",
            context.getString(R.string.smtp_554),
        )
        assertEquals("无法识别邮件参数。", context.getString(R.string.smtp_555))
        assertEquals("收件人域名不接受邮件。", context.getString(R.string.smtp_556))
        assertEquals(
            "消息被严格策略或垃圾邮件过滤器拒绝。",
            context.getString(R.string.smtp_571),
        )
    }

    @Test
    fun `fallback strings resolve to the shipped chinese text with the code substituted`() {
        assertEquals("服务器临时错误（499）。请稍后重试。", context.getString(R.string.smtp_transient_fallback, 499))
        assertEquals(
            "服务器永久错误（599）。邮件无法发送。",
            context.getString(R.string.smtp_permanent_fallback, 599),
        )
    }
}
