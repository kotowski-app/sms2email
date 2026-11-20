package app.kotowski.sms2email.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStartDestinationTest {
    @Test
    fun `scam warning is start destination when not dismissed`() {
        assertEquals(
            "scam_warning",
            getStartDestination(scamWarningDismissed = false, onboardingComplete = true),
        )
    }

    @Test
    fun `dashboard is start destination when onboarding complete`() {
        assertEquals(
            "dashboard",
            getStartDestination(scamWarningDismissed = true, onboardingComplete = true),
        )
    }

    @Test
    fun `onboarding is start destination when onboarding not complete`() {
        assertEquals(
            "onboarding",
            getStartDestination(scamWarningDismissed = true, onboardingComplete = false),
        )
    }

    @Test
    fun `onboarding is default when nothing is complete`() {
        assertEquals(
            "onboarding",
            getStartDestination(scamWarningDismissed = true, onboardingComplete = false),
        )
    }
}

fun getStartDestination(
    scamWarningDismissed: Boolean,
    onboardingComplete: Boolean,
): String =
    when {
        !scamWarningDismissed -> "scam_warning"
        onboardingComplete -> "dashboard"
        else -> "onboarding"
    }
