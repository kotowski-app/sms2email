package app.kotowski.sms2email.data.filter

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric for org.json; the predicate itself is pure.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsFilterTest {
    @Test
    fun `the default filter is off and forwards everything`() {
        val filter = SmsFilter()
        assertEquals(FilterMode.SKIP, filter.mode)
        assertTrue(filter.rules.isEmpty())
        assertTrue(filter.shouldForward("anything", "+1234", 1))
        assertTrue(filter.shouldForward("anything", "+1234", null))
    }

    @Test
    fun `the body facet hits partially and misses when absent`() {
        val rule = FilterRule(body = "prize")
        assertTrue(rule.hits("You won a prize today", "", null))
        assertFalse(rule.hits("nothing here", "", null))
        assertFalse(rule.hits("", "", null))
    }

    @Test
    fun `the sender facet hits the stored phone number partially`() {
        val rule = FilterRule(sender = "5550")
        assertTrue(rule.hits("", TestPhone.SELF, null))
        assertFalse(rule.hits("", "+9876", null))
    }

    @Test
    fun `the slot facet hits only its listed tray slots`() {
        val rule = FilterRule(slots = listOf(1, 3))
        assertTrue(rule.hits("", "", 1))
        assertTrue(rule.hits("", "", 3))
        assertFalse(rule.hits("", "", 2))
        // null (the unknown subscription) and 0 (no tray slot) are never in 1..3.
        assertFalse(rule.hits("", "", null))
        assertFalse(rule.hits("", "", 0))
    }

    @Test
    fun `an out-of-range slot never hits the slot facet`() {
        val rule = FilterRule(slots = listOf(2))
        assertFalse(rule.hits("", "", 4))
        assertFalse(rule.hits("", "", -1))
    }

    @Test
    fun `a blank facet is inactive and a rule with no active facet never hits`() {
        val blank = FilterRule(body = " ", sender = "", slots = emptyList())
        assertFalse(blank.isActive)
        assertFalse(blank.hits("anything", "+1234", 1))
    }

    @Test
    fun `all active facets must hit and blank facets do not count`() {
        val rule = FilterRule(body = "aaa", sender = "123", slots = listOf(2))
        assertTrue(rule.hits("has aaa", "+123", 2))
        assertFalse(rule.hits("has aaa", "nope", 2))
        assertFalse(rule.hits("nope", "+123", 2))
        // The blank slot facet is inactive: only body + sender must hit.
        assertTrue(rule.copy(slots = emptyList()).hits("has aaa", "+123", null))
        assertTrue(FilterRule(body = "aaa").hits("has aaa", "nope", 1))
    }

    @Test
    fun `inactive rules never hit and the active rules are or'd`() {
        val filter =
            SmsFilter(
                FilterMode.SKIP,
                listOf(
                    FilterRule(),
                    FilterRule(body = "second"),
                ),
            )
        assertTrue(filter.shouldForward("no match", "", null))
        assertFalse(filter.shouldForward("the second rule", "", null))
    }

    @Test
    fun `skip mode blocks a hit and forward mode keeps a hit`() {
        val rules = listOf(FilterRule(body = "vip"))
        assertFalse(SmsFilter(FilterMode.SKIP, rules).shouldForward("vip here", "", null))
        assertTrue(SmsFilter(FilterMode.SKIP, rules).shouldForward("nothing here", "", null))
        assertTrue(SmsFilter(FilterMode.FORWARD, rules).shouldForward("vip here", "", null))
        assertFalse(SmsFilter(FilterMode.FORWARD, rules).shouldForward("nothing here", "", null))
    }

    @Test
    fun `the match is partial and case-sensitive`() {
        assertTrue(FilterRule(body = "abc").hits("x abc y", "", null))
        assertFalse(FilterRule(body = "abc").hits("x ABC y", "", null))
    }

    @Test
    fun `the pattern's own case flag still applies`() {
        assertTrue(FilterRule(body = "(?i)abc").hits("x ABC y", "", null))
    }

    @Test
    fun `the match supports alternation and anchors`() {
        assertTrue(FilterRule(body = "cat|dog").hits("a dog walks", "", null))
        assertTrue(FilterRule(body = "^hello$").hits("hello", "", null))
        assertFalse(FilterRule(body = "^hello$").hits("hello world", "", null))
    }

    @Test
    fun `unicode and russian bodies match`() {
        assertTrue(FilterRule(body = "срочн").hits("Это срочно сообщение", "", null))
    }

    // Fail-open: an uncompilable pattern is a dead facet that never hits.

    @Test
    fun `an invalid regex is a dead facet that never hits`() {
        Logger.setLevel(LogLevel.DEBUG)
        val rule = FilterRule(body = "(", sender = "123")
        // The dead body facet sinks the rule: every active facet must hit.
        assertFalse(rule.hits("", "nope", null))
        assertFalse(rule.hits("", "+123", null))
        assertTrue(FilterRule(sender = "123").hits("", "+123", null))
        // Pinned: a compile error can echo the pattern text; the log keeps the class name only.
        val entry = LogBuffer.entries.last()
        assertEquals("SmsFilter", entry.tag)
        assertEquals("filter facet pattern does not compile, the facet never hits: PatternSyntaxException", entry.message)
    }

    @Test
    fun `forward mode with a single dead-regex rule forwards nothing`() {
        // Pinned: a single dead-regex rule never hits, so forward mode forwards nothing
        // (every row would be parked with the no-match reason). The rule is still ACTIVE
        // (a facet is written), so the off-state rule below does not apply to it.
        val filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "[")))
        assertFalse(filter.shouldForward("anything", "+1234", 1))
        assertFalse(filter.shouldForward("anything", "+1234", null))
    }

    @Test
    fun `a filter without an active rule forwards everything in either mode`() {
        // The off state: no active rule (an empty list, or rules with no active facet)
        // forwards everything, in BOTH modes — the forward mode without a rule is the
        // same off state, not "forward nothing".
        for (mode in FilterMode.entries) {
            assertTrue(SmsFilter(mode).shouldForward("anything", "+1234", 1))
            assertTrue(SmsFilter(mode).shouldForward("anything", "+1234", null))
            val inactiveOnly =
                SmsFilter(
                    mode,
                    listOf(
                        FilterRule(),
                        FilterRule(body = "  ", sender = " ", slots = emptyList()),
                    ),
                )
            assertTrue(inactiveOnly.shouldForward("anything", "+1234", 1))
            assertTrue(inactiveOnly.shouldForward("anything", "+1234", null))
        }
    }

    // The caps the editor, the strict import and the lenient parse all enforce.

    @Test
    fun `the facet byte cap counts utf-8 bytes, not chars`() {
        assertFalse(SmsFilter.isFacetTooLong("a".repeat(AppConfig.FILTER_FACET_MAX_BYTES)))
        assertTrue(SmsFilter.isFacetTooLong("a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)))
        // Cyrillic is two bytes per char: the cap hits at half the length.
        assertFalse(SmsFilter.isFacetTooLong("а".repeat(AppConfig.FILTER_FACET_MAX_BYTES / 2)))
        assertTrue(SmsFilter.isFacetTooLong("а".repeat(AppConfig.FILTER_FACET_MAX_BYTES / 2 + 1)))
    }

    @Test
    fun `isInvalidRegex flags only a non-blank uncompilable pattern`() {
        assertFalse(SmsFilter.isInvalidRegex(""))
        assertFalse(SmsFilter.isInvalidRegex("abc"))
        assertTrue(SmsFilter.isInvalidRegex("("))
        assertTrue(SmsFilter.isInvalidRegex("["))
    }

    @Test
    fun `isFacetInvalid is the disjunction of the two per-facet checks`() {
        assertFalse(SmsFilter.isFacetInvalid(""))
        assertFalse(SmsFilter.isFacetInvalid("abc"))
        // Each check alone is enough to flag the field.
        assertTrue(SmsFilter.isFacetInvalid("("))
        assertTrue(SmsFilter.isFacetInvalid("a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)))
    }

    @Test
    fun `the rule-level checks aggregate the regex facets`() {
        assertFalse(FilterRule().hasInvalidRegex)
        assertFalse(FilterRule(body = "abc").hasInvalidRegex)
        assertFalse(FilterRule(slots = listOf(1)).hasInvalidRegex)
        assertTrue(FilterRule(body = "(").hasInvalidRegex)
        assertTrue(FilterRule(sender = "[").hasInvalidRegex)
        assertTrue(FilterRule(body = "(", sender = "[").hasInvalidRegex)
    }

    @Test
    fun `the rule-level byte-cap check flags either over-cap facet`() {
        assertFalse(FilterRule().hasFacetTooLong)
        assertFalse(FilterRule(body = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES)).hasFacetTooLong)
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        assertTrue(FilterRule(body = overCap).hasFacetTooLong)
        assertTrue(FilterRule(sender = overCap).hasFacetTooLong)
        assertTrue(FilterRule(body = overCap, sender = overCap).hasFacetTooLong)
    }

    @Test
    fun `toJson writes the documented shape and fromJson round-trips it`() {
        val filter =
            SmsFilter(
                FilterMode.FORWARD,
                listOf(
                    FilterRule(body = "vip", sender = "123", slots = listOf(1, 3)),
                    FilterRule(slots = listOf(2)),
                ),
            )
        val json = filter.toJson()
        // The mode is a name, the slots are tray numbers, the rule order is the display order.
        val obj = JSONObject(json)
        assertEquals("FORWARD", obj.getString("mode"))
        val rules = obj.getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("vip", rules.getJSONObject(0).getString("body"))
        assertEquals(1, rules.getJSONObject(0).getJSONArray("slots").getInt(0))
        assertEquals(3, rules.getJSONObject(0).getJSONArray("slots").getInt(1))
        assertEquals(filter, SmsFilter.fromJson(json))
    }

    @Test
    fun `fromJson of the default document round-trips the off state`() {
        assertEquals(SmsFilter(), SmsFilter.fromJson(SmsFilter().toJson()))
    }

    // The lenient parse: a corrupt document degrades to the nearest safe level.

    @Test
    fun `fromJson of a blank or malformed document degrades to the filter off`() {
        assertEquals(SmsFilter(), SmsFilter.fromJson(""))
        assertEquals(SmsFilter(), SmsFilter.fromJson("   "))
        assertEquals(SmsFilter(), SmsFilter.fromJson("not json"))
    }

    @Test
    fun `fromJson of an unknown mode degrades to the filter off`() {
        assertEquals(SmsFilter(), SmsFilter.fromJson("""{"mode":"MAYBE","rules":[]}"""))
    }

    @Test
    fun `fromJson of a mode without an active rule keeps the off state as-is`() {
        // The legal off state (the predicate forwards everything for it): the
        // document parses as itself, the mode included — the editor keeps the
        // user's polarity for the next rule.
        assertEquals(
            SmsFilter(FilterMode.FORWARD, emptyList()),
            SmsFilter.fromJson("""{"mode":"FORWARD","rules":[]}"""),
        )
        assertEquals(
            SmsFilter(FilterMode.FORWARD, listOf(FilterRule())),
            SmsFilter.fromJson("""{"mode":"FORWARD","rules":[{"body":"","sender":"","slots":[],"combine":"ANY"}]}"""),
        )
    }

    @Test
    fun `fromJson drops the corrupt rule and keeps the rest`() {
        // A slot outside 1..3 kills the rule, not the filter.
        val json =
            """{"mode":"SKIP","rules":[{"body":"keep","sender":"","slots":[],"combine":"ANY"},
            |{"body":"","sender":"","slots":[5],"combine":"ANY"}]}
            """.trimMargin()
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "keep"))),
            SmsFilter.fromJson(json),
        )
    }

    @Test
    fun `fromJson ignores a combine field present in the rule document`() {
        val json = """{"mode":"SKIP","rules":[{"body":"keep","sender":"","slots":[1],"combine":"ALL"}]}"""
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "keep", slots = listOf(1)))),
            SmsFilter.fromJson(json),
        )
    }

    @Test
    fun `fromJson drops a rule whose facet is over the byte cap`() {
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        val json =
            """{"mode":"SKIP","rules":[{"body":"$overCap","sender":"","slots":[],"combine":"ANY"},
            |{"body":"keep","sender":"","slots":[],"combine":"ANY"}]}
            """.trimMargin()
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "keep"))),
            SmsFilter.fromJson(json),
        )
    }

    @Test
    fun `fromJson drops a non-object rule entry`() {
        assertEquals(SmsFilter(), SmsFilter.fromJson("""{"mode":"SKIP","rules":["oops"]}"""))
    }

    @Test
    fun `fromJson truncates an over-cap rule list to the first 10`() {
        val rules =
            (0 until AppConfig.FILTER_MAX_RULES + 3).joinToString(
                ",",
            ) { i -> """{"body":"b$i","sender":"","slots":[],"combine":"ANY"}""" }
        val json = """{"mode":"SKIP","rules":[$rules]}"""
        val parsed = SmsFilter.fromJson(json)
        assertEquals(AppConfig.FILTER_MAX_RULES, parsed.rules.size)
        assertEquals("b0", parsed.rules.first().body)
        assertEquals("b9", parsed.rules.last().body)
    }

    @Test
    fun `fromJson keeps a non-compiling regex as-is (the fail-open is at evaluation)`() {
        val json = """{"mode":"SKIP","rules":[{"body":"(","sender":"","slots":[],"combine":"ANY"}]}"""
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "("))),
            SmsFilter.fromJson(json),
        )
    }

    // The strict parse: any invalid part rejects the document.

    @Test
    fun `parseStrict round-trips a valid document`() {
        val filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip", slots = listOf(2))))
        assertEquals(filter, SmsFilter.parseStrict(filter.toJson()))
    }

    @Test
    fun `parseStrict accepts the skip mode with no rules (the off state)`() {
        assertEquals(SmsFilter(), SmsFilter.parseStrict("""{"mode":"SKIP","rules":[]}"""))
    }

    @Test
    fun `parseStrict accepts a document without the rules key as the off state`() {
        assertEquals(SmsFilter(), SmsFilter.parseStrict("""{"mode":"SKIP"}"""))
    }

    @Test
    fun `parseStrict rejects a present rules value that is not an array`() {
        for (rules in listOf("\"oops\"", "5", "true", "null", "{}")) {
            Assert.assertThrows(JSONException::class.java) {
                SmsFilter.parseStrict("""{"mode":"SKIP","rules":$rules}""")
            }
        }
    }

    @Test
    fun `parseStrict rejects malformed json`() {
        Assert.assertThrows(JSONException::class.java) { SmsFilter.parseStrict("not json") }
    }

    @Test
    fun `parseStrict rejects an unknown mode`() {
        Assert.assertThrows(JSONException::class.java) { SmsFilter.parseStrict("""{"mode":"MAYBE","rules":[]}""") }
    }

    @Test
    fun `parseStrict accepts a forward mode without an active rule (the off state)`() {
        assertEquals(
            SmsFilter(FilterMode.FORWARD, emptyList()),
            SmsFilter.parseStrict("""{"mode":"FORWARD","rules":[]}"""),
        )
        // A rule with no active facets counts as inactive (the raw blank value is
        // kept, the predicate ignores it).
        assertEquals(
            SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "  "))),
            SmsFilter.parseStrict("""{"mode":"FORWARD","rules":[{"body":"  ","sender":"","slots":[],"combine":"ANY"}]}"""),
        )
    }

    @Test
    fun `parseStrict rejects a non-compiling regex`() {
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"(","sender":"","slots":[],"combine":"ANY"}]}""")
        }
    }

    @Test
    fun `parseStrict rejects an over-cap facet`() {
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"$overCap","sender":"","slots":[],"combine":"ANY"}]}""")
        }
    }

    @Test
    fun `parseStrict rejects a slot outside the 1-3 range`() {
        for (slot in listOf(0, 4, -1)) {
            Assert.assertThrows(JSONException::class.java) {
                SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"","sender":"","slots":[$slot],"combine":"ANY"}]}""")
            }
        }
    }

    @Test
    fun `parseStrict ignores a combine field present in the rule document`() {
        val json = """{"mode":"SKIP","rules":[{"body":"keep","sender":"","slots":[1],"combine":"ANY"}]}"""
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "keep", slots = listOf(1)))),
            SmsFilter.parseStrict(json),
        )
    }

    @Test
    fun `parseStrict rejects a rule list over the cap`() {
        val rules =
            (0 until AppConfig.FILTER_MAX_RULES + 1).joinToString(
                ",",
            ) { i -> """{"body":"b$i","sender":"","slots":[],"combine":"ANY"}""" }
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[$rules]}""")
        }
    }

    @Test
    fun `parseStrict rejects a non-object rule entry`() {
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":["oops"]}""")
        }
    }

    @Test
    fun `fromJson with absent rules key yields empty rules list`() {
        val parsed = SmsFilter.fromJson("""{"mode":"SKIP"}""")
        assertEquals(SmsFilter(FilterMode.SKIP, emptyList()), parsed)
    }

    @Test
    fun `parseStrict with sender over the byte cap throws`() {
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"","sender":"$overCap","slots":[]}]}""")
        }
    }

    @Test
    fun `parseStrict rejects sender regex that does not compile`() {
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"","sender":"(","slots":[]}]}""")
        }
    }

    @Test
    fun `parseStrict with slots that are non-integer throws`() {
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[{"body":"","sender":"","slots":["a"]}]}""")
        }
    }

    @Test
    fun `parseStrict with too many rules throws`() {
        val rules =
            (0 until AppConfig.FILTER_MAX_RULES + 1).joinToString(
                ",",
            ) { i -> """{"body":"b$i","sender":"","slots":[]}""" }
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[$rules]}""")
        }
    }

    @Test
    fun `fromJson drops a rule with slots outside 1 to 3`() {
        val json = """{"mode":"SKIP","rules":[{"body":"","sender":"","slots":[0]}]}"""
        val parsed = SmsFilter.fromJson(json)
        assertEquals(SmsFilter(FilterMode.SKIP, emptyList()), parsed)
    }

    @Test
    fun `fromJson drops a rule with non-integer slots`() {
        val json = """{"mode":"SKIP","rules":[{"body":"","sender":"","slots":["a"]}]}"""
        val parsed = SmsFilter.fromJson(json)
        assertEquals(SmsFilter(FilterMode.SKIP, emptyList()), parsed)
    }

    @Test
    fun `fromJson drops a rule with sender facet over the byte cap`() {
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        val json = """{"mode":"SKIP","rules":[{"body":"","sender":"$overCap","slots":[]}]}"""
        val parsed = SmsFilter.fromJson(json)
        assertEquals(SmsFilter(FilterMode.SKIP, emptyList()), parsed)
    }

    @Test
    fun `FilterRule with empty body and active sender facet still hits`() {
        val rule = FilterRule(body = "", sender = "123")
        assertTrue(rule.hits("", "+123", null))
        assertFalse(rule.hits("", "+999", null))
    }

    @Test
    fun `FilterRule with null slot and non-empty slots does not hit`() {
        val rule = FilterRule(slots = listOf(1, 2))
        assertFalse(rule.hits("", "", null))
        assertTrue(rule.hits("", "", 1))
        assertFalse(rule.hits("", "", 3))
    }

    @Test
    fun `isFacetInvalid with empty string returns false`() {
        assertFalse(SmsFilter.isFacetInvalid(""))
    }

    @Test
    fun `parseStrict with null rule object throws`() {
        Assert.assertThrows(JSONException::class.java) {
            SmsFilter.parseStrict("""{"mode":"SKIP","rules":[null]}""")
        }
    }

    // shouldForward: when every rule is inactive (all facets blank), the filter is off
    // regardless of the mode — everything forwards.
    @Test
    fun `shouldForward returns true when every rule is inactive in SKIP mode`() {
        val rule = FilterRule() // all facets blank -> isActive = false
        val filter = SmsFilter(mode = FilterMode.SKIP, rules = listOf(rule))
        assertTrue(filter.shouldForward("test message", "+123", 1))
    }

    @Test
    fun `shouldForward returns true when every rule is inactive in FORWARD mode`() {
        val rule = FilterRule() // all facets blank -> isActive = false
        val filter = SmsFilter(mode = FilterMode.FORWARD, rules = listOf(rule))
        assertTrue(filter.shouldForward("test message", "+123", 1))
    }

    // fromJson: valid mode but no "rules" key — rawRules is null, so the filter
    // parses with the given mode and an empty rule list (the off state).
    @Test
    fun `fromJson with valid mode but no rules key yields empty rules`() {
        val filter = SmsFilter.fromJson("""{"mode":"SKIP"}""")
        assertEquals(FilterMode.SKIP, filter.mode)
        assertTrue(filter.rules.isEmpty())
    }

    @Test
    fun `fromJson with FORWARD mode and no rules key yields empty rules`() {
        val filter = SmsFilter.fromJson("""{"mode":"FORWARD"}""")
        assertEquals(FilterMode.FORWARD, filter.mode)
        assertTrue(filter.rules.isEmpty())
    }
}
