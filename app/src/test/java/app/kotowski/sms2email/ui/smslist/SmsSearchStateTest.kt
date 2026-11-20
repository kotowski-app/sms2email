package app.kotowski.sms2email.ui.smslist

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsSearchStateTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `the initial state is idle with an empty query`() {
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            search = rememberSmsSearchState { announced.add(it) }
            Text("content")
        }
        assertFalse(search!!.isSearchMode)
        assertEquals("", search.rawQuery)
        assertEquals("", search.query)
        assertEquals(listOf(""), announced)
    }

    @Test
    fun `toggle enters search mode and the field drives the raw query`() {
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            search = rememberSmsSearchState { }
            Text("content")
        }
        search!!.toggle()
        composeTestRule.waitForIdle()
        assertTrue(search.isSearchMode)
        assertEquals("", search.query)
        search.onRawQueryChange("foo bar")
        composeTestRule.waitForIdle()
        assertEquals("foo bar", search.rawQuery)
        assertEquals("foo bar", search.query)
    }

    @Test
    fun `the announced query is the trimmed raw query`() {
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            search = rememberSmsSearchState { announced.add(it) }
            Text("content")
        }
        search!!.toggle()
        search.onRawQueryChange("  foo  ")
        composeTestRule.waitForIdle()
        assertEquals("  foo  ", search.rawQuery)
        assertEquals("foo", search.query)
        assertEquals(listOf("", "foo"), announced)
    }

    @Test
    fun `clearing exits search mode and empties the query`() {
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            search = rememberSmsSearchState { announced.add(it) }
            Text("content")
        }
        search!!.toggle()
        search.onRawQueryChange("foo")
        composeTestRule.waitForIdle()
        search.clear()
        composeTestRule.waitForIdle()
        assertFalse(search.isSearchMode)
        assertEquals("", search.rawQuery)
        assertEquals("", search.query)
        assertEquals(listOf("", "foo", ""), announced)
    }

    @Test
    fun `toggling off empties the raw query`() {
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            search = rememberSmsSearchState { announced.add(it) }
            Text("content")
        }
        search!!.toggle()
        search.onRawQueryChange("foo")
        composeTestRule.waitForIdle()
        search.toggle()
        composeTestRule.waitForIdle()
        assertFalse(search.isSearchMode)
        assertEquals("", search.rawQuery)
        assertEquals(listOf("", "foo", ""), announced)
    }

    // Behind a SaveableStateHolder (the same mechanism a rotation uses): the typed
    // query must be restored into the fresh composition, not dropped.

    @Test
    fun `the search mode and typed query survive the composition leaving and re-entering`() {
        val showing = mutableStateOf(true)
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showing.value) "search" else "elsewhere") {
                if (showing.value) {
                    search = rememberSmsSearchState { announced.add(it) }
                    Text("content")
                }
            }
        }
        search!!.toggle()
        search.onRawQueryChange("foo")
        composeTestRule.waitForIdle()
        assertEquals("foo", search.query)
        composeTestRule.runOnIdle { showing.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { showing.value = true }
        composeTestRule.waitForIdle()
        assertTrue(search.isSearchMode)
        assertEquals("foo", search.rawQuery)
        assertEquals("foo", search.query)
        // The re-announced query is the saved one, not the empty reset.
        assertEquals(listOf("", "foo", "foo"), announced)
    }

    @Test
    fun `the idle search state survives the composition leaving and re-entering`() {
        val showing = mutableStateOf(true)
        val announced = mutableListOf<String>()
        var search: SmsSearchState? = null
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showing.value) "search" else "elsewhere") {
                if (showing.value) {
                    search = rememberSmsSearchState { announced.add(it) }
                    Text("content")
                }
            }
        }
        composeTestRule.runOnIdle { showing.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { showing.value = true }
        composeTestRule.waitForIdle()
        assertFalse(search!!.isSearchMode)
        assertEquals("", search.rawQuery)
        assertEquals(listOf("", ""), announced)
    }
}
