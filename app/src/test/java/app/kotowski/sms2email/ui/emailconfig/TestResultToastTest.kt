package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import android.widget.Toast
import app.kotowski.sms2email.R
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.testing.withMockedToast
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never

class TestResultToastTest {
    @Mock private lateinit var context: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(context.getString(R.string.email_config_test_success)).thenReturn("Success")
    }

    @Test
    fun `showTestResultToast toasts the success string`() {
        withMockedToast { staticToast ->
            showTestResultToast(context, EmailResult.Success)
            staticToast.verify {
                Toast.makeText(eq(context), eq("Success"), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `showTestResultToast toasts the failure message`() {
        withMockedToast { staticToast ->
            showTestResultToast(context, EmailResult.Failure("smtp said no"))
            staticToast.verify {
                Toast.makeText(eq(context), eq("smtp said no"), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `showTestResultToast shows nothing for a null result`() {
        withMockedToast { staticToast ->
            showTestResultToast(context, null)
            staticToast.verify(
                MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<CharSequence>(), any<Int>()) },
                never(),
            )
            staticToast.verify(
                MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) },
                never(),
            )
        }
    }
}
