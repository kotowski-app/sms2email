package app.kotowski.sms2email.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RememberOnResumeTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun dispatch(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    @Test
    fun `onResume fires on ON_RESUME`() {
        var resumeCount = 0
        val owner = TestLifecycleOwner()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RememberOnResume(onResume = { resumeCount++ })
                Text("content")
            }
        }
        owner.dispatch(Lifecycle.Event.ON_CREATE)
        owner.dispatch(Lifecycle.Event.ON_START)
        owner.dispatch(Lifecycle.Event.ON_RESUME)
        assertEquals(1, resumeCount)
    }

    @Test
    fun `onPause fires on ON_PAUSE`() {
        var pauseCount = 0
        val owner = TestLifecycleOwner()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RememberOnResume(onPause = { pauseCount++ })
                Text("content")
            }
        }
        owner.dispatch(Lifecycle.Event.ON_CREATE)
        owner.dispatch(Lifecycle.Event.ON_START)
        owner.dispatch(Lifecycle.Event.ON_RESUME)
        owner.dispatch(Lifecycle.Event.ON_PAUSE)
        assertEquals(1, pauseCount)
    }

    @Test
    fun `other events do not fire callbacks`() {
        var resumeCount = 0
        var pauseCount = 0
        val owner = TestLifecycleOwner()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RememberOnResume(onResume = { resumeCount++ }, onPause = { pauseCount++ })
                Text("content")
            }
        }
        owner.dispatch(Lifecycle.Event.ON_CREATE)
        owner.dispatch(Lifecycle.Event.ON_START)
        owner.dispatch(Lifecycle.Event.ON_STOP)
        owner.dispatch(Lifecycle.Event.ON_START)
        assertEquals(0, resumeCount)
        assertEquals(0, pauseCount)
    }

    @Test
    fun `observer is removed when the lifecycle owner changes`() {
        var resumeCount = 0
        val first = TestLifecycleOwner()
        val second = TestLifecycleOwner()
        var owner by mutableStateOf<TestLifecycleOwner>(first)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RememberOnResume(onResume = { resumeCount++ })
                Text("content")
            }
        }
        first.dispatch(Lifecycle.Event.ON_CREATE)
        first.dispatch(Lifecycle.Event.ON_START)
        first.dispatch(Lifecycle.Event.ON_RESUME)
        assertEquals(1, resumeCount)
        owner = second
        composeTestRule.waitForIdle()
        second.dispatch(Lifecycle.Event.ON_CREATE)
        second.dispatch(Lifecycle.Event.ON_START)
        second.dispatch(Lifecycle.Event.ON_RESUME)
        assertEquals(2, resumeCount)
        first.dispatch(Lifecycle.Event.ON_PAUSE)
        first.dispatch(Lifecycle.Event.ON_RESUME)
        assertEquals(2, resumeCount)
    }
}
