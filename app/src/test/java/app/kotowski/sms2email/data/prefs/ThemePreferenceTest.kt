package app.kotowski.sms2email.data.prefs

import app.kotowski.sms2email.ThemeMode
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import kotlin.test.assertEquals

class ThemePreferenceTest {
    @Mock
    private lateinit var prefs: EncryptedPrefs

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        `when`(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
    }

    @Test
    fun `the initial mode is the stored pref`() {
        `when`(prefs.themeMode).thenReturn(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePreference(prefs).mode.value)
    }

    @Test
    fun `set persists the mode and emits it`() {
        val preference = ThemePreference(prefs)
        preference.set(ThemeMode.LIGHT)
        verify(prefs).themeMode = ThemeMode.LIGHT
        assertEquals(ThemeMode.LIGHT, preference.mode.value)
    }
}
