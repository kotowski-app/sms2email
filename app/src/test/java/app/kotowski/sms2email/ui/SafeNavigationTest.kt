package app.kotowski.sms2email.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SafeNavigationTest {
    @Mock
    private lateinit var navController: NavController

    @Mock
    private lateinit var currentEntry: NavBackStackEntry

    @Mock
    private lateinit var previousEntry: NavBackStackEntry

    @Mock
    private lateinit var lifecycle: Lifecycle

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
    }

    @Test
    fun `safeNavigate calls navigate when lifecycle is RESUMED`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)

        navController.safeNavigate("test_route")

        verify(navController).navigate(eq("test_route"), any<NavOptionsBuilder.() -> Unit>())
    }

    @Test
    fun `safeNavigate skips navigate when lifecycle is STARTED`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)

        navController.safeNavigate("test_route")

        verify(navController, never()).navigate(eq("test_route"), any<NavOptionsBuilder.() -> Unit>())
    }

    @Test
    fun `safeNavigate skips navigate when lifecycle is CREATED`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.CREATED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)

        navController.safeNavigate("test_route")

        verify(navController, never()).navigate(eq("test_route"), any<NavOptionsBuilder.() -> Unit>())
    }

    @Test
    fun `safeNavigate skips navigate when lifecycle is DESTROYED`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.DESTROYED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)

        navController.safeNavigate("test_route")

        verify(navController, never()).navigate(eq("test_route"), any<NavOptionsBuilder.() -> Unit>())
    }

    @Test
    fun `safeNavigate skips navigate when currentBackStackEntry is null`() {
        whenever(navController.currentBackStackEntry).thenReturn(null)

        navController.safeNavigate("test_route")

        verify(navController, never()).navigate(eq("test_route"), any<NavOptionsBuilder.() -> Unit>())
    }

    @Test
    fun `safePopBackStack calls popBackStack when RESUMED and previous entry exists`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)
        whenever(navController.previousBackStackEntry).thenReturn(previousEntry)

        navController.safePopBackStack()

        verify(navController).popBackStack()
    }

    @Test
    fun `safePopBackStack skips pop when STARTED even if previous entry exists`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)
        whenever(navController.previousBackStackEntry).thenReturn(previousEntry)

        navController.safePopBackStack()

        verify(navController, never()).popBackStack()
    }

    @Test
    fun `safePopBackStack skips pop when RESUMED but no previous entry - prevents white screen`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)
        whenever(navController.previousBackStackEntry).thenReturn(null)

        navController.safePopBackStack()

        verify(navController, never()).popBackStack()
    }

    @Test
    fun `safePopBackStack skips pop when currentBackStackEntry is null`() {
        whenever(navController.currentBackStackEntry).thenReturn(null)

        navController.safePopBackStack()

        verify(navController, never()).popBackStack()
    }

    @Test
    fun `safePopBackStack skips pop when lifecycle is INITIALIZED`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.INITIALIZED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)
        whenever(navController.previousBackStackEntry).thenReturn(previousEntry)

        navController.safePopBackStack()

        verify(navController, never()).popBackStack()
    }

    @Test
    fun `safePopBackStack skips pop when CREATED even if previous entry exists`() {
        whenever(currentEntry.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.CREATED)
        whenever(navController.currentBackStackEntry).thenReturn(currentEntry)
        whenever(navController.previousBackStackEntry).thenReturn(previousEntry)

        navController.safePopBackStack()

        verify(navController, never()).popBackStack()
    }
}
