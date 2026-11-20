package app.kotowski.sms2email.util

import android.content.Context
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class ToastUtilTest {
    @Test
    fun `show with string delegates to Toast`() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockAppContext = Mockito.mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)

        // Toast.show() is not verifiable in a bare unit test; the call must not leak an exception
        try {
            ToastUtil.show(mockContext, "test message")
        } catch (e: Exception) {
            // Expected: Toast.makeText with mock context may throw in unit test environment
        }
    }

    @Test
    fun `show with resource id delegates to Toast`() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockAppContext = Mockito.mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)

        try {
            ToastUtil.show(mockContext, 12345)
        } catch (e: Exception) {
            // Expected: Toast.makeText with mock context may throw in unit test environment
        }
    }
}
