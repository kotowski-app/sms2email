package app.kotowski.sms2email.ui.onboarding

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WizardStepTest {
    @Test
    fun `welcome always allows proceeding`() {
        assertTrue(WizardStep.WELCOME.canProceed())
    }

    @Test
    fun `permissions depends on permissionsGranted`() {
        assertTrue(WizardStep.PERMISSIONS.canProceed(permissionsGranted = true))
        assertFalse(WizardStep.PERMISSIONS.canProceed(permissionsGranted = false))
    }

    @Test
    fun `smtp depends on smtpSucceeded`() {
        assertFalse(WizardStep.SMTP.canProceed())
        assertTrue(WizardStep.SMTP.canProceed(smtpSucceeded = true))
    }

    @Test
    fun `sender depends on testEmailSucceeded`() {
        assertFalse(WizardStep.SENDER.canProceed())
        assertTrue(WizardStep.SENDER.canProceed(testEmailSucceeded = true))
    }

    @Test
    fun `done always allows proceeding`() {
        assertTrue(WizardStep.DONE.canProceed())
    }

    @Test
    fun `entries follow the wizard order`() {
        assertSame(WizardStep.entries[0], WizardStep.WELCOME)
        assertSame(WizardStep.entries[1], WizardStep.PERMISSIONS)
        assertSame(WizardStep.entries[2], WizardStep.SMTP)
        assertSame(WizardStep.entries[3], WizardStep.SENDER)
        assertSame(WizardStep.entries[4], WizardStep.DONE)
    }

    @Test
    fun `previous of the first step is null`() {
        assertNull(WizardStep.WELCOME.previous)
    }

    @Test
    fun `next of the last step is null`() {
        assertNull(WizardStep.DONE.next)
    }

    @Test
    fun `previous and next walk the wizard order`() {
        assertSame(WizardStep.WELCOME.next, WizardStep.PERMISSIONS)
        assertSame(WizardStep.PERMISSIONS.previous, WizardStep.WELCOME)
        assertSame(WizardStep.PERMISSIONS.next, WizardStep.SMTP)
        assertSame(WizardStep.SMTP.previous, WizardStep.PERMISSIONS)
        assertSame(WizardStep.SMTP.next, WizardStep.SENDER)
        assertSame(WizardStep.SENDER.previous, WizardStep.SMTP)
        assertSame(WizardStep.SENDER.next, WizardStep.DONE)
        assertSame(WizardStep.DONE.previous, WizardStep.SENDER)
    }

    @Test
    fun `only the last step is last`() {
        WizardStep.entries.forEach { step ->
            assertEquals(step == WizardStep.DONE, step.isLast, "isLast of $step")
        }
    }

    @Test
    fun `saver restores every step from its name`() {
        WizardStep.entries.forEach { step ->
            assertSame(step, WizardStep.Saver.restore(step.name))
        }
    }

    @Test
    fun `saver restores welcome for an unknown step name`() {
        assertSame(WizardStep.WELCOME, WizardStep.Saver.restore("NO_SUCH_STEP"))
        assertSame(WizardStep.WELCOME, WizardStep.Saver.restore(""))
    }
}
