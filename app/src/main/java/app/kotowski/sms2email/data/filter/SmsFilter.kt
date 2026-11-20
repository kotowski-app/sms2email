package app.kotowski.sms2email.data.filter

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.util.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// The global polarity of the message filter: one for the whole filter, not per rule.
// SKIP is the blocklist baseline (a hit skips the message, everything else forwards),
// FORWARD the allowlist (a hit forwards the message, everything else is skipped).
enum class FilterMode { SKIP, FORWARD }

// One OR'd condition set of the filter: up to three facets (the body regex, the
// sender regex matched against the stored phone number, the tray slot numbers) of
// which every active one must hit. Rules are polarity-free — they only answer
// "does this message hit?"; the mode decides what a hit does.
data class FilterRule(
    val body: String = "",
    val sender: String = "",
    val slots: List<Int> = emptyList(),
) {
    // A facet left blank is inactive; a rule with zero active facets never hits.
    val isActive: Boolean
        get() = body.isNotBlank() || sender.isNotBlank() || slots.isNotEmpty()

    // The rule's two facet checks, aggregated over the regex facets. The editor's
    // save gate and the field's live error both derive from these, so the red
    // border and the save block cannot drift apart.
    val hasInvalidRegex: Boolean
        get() = SmsFilter.isInvalidRegex(body) || SmsFilter.isInvalidRegex(sender)

    val hasFacetTooLong: Boolean
        get() = SmsFilter.isFacetTooLong(body) || SmsFilter.isFacetTooLong(sender)

    // Whether the message hits: every active facet must hit (a rule with no active
    // facet never hits). The regexes are compiled per evaluation (SMS rates make it
    // free; the immutable config snapshot stays trivially shareable, nothing
    // compiled is persisted) and matched exactly as written — case-sensitively,
    // partially (no anchors added), the pattern's own inline flags (e.g. (?i))
    // still apply.
    // An uncompilable pattern is a dead facet: it never hits, with one debug log line
    // per evaluation (fail-open — in SKIP mode a dead facet only unblocks, the safe
    // direction; the editor's save gate keeps a fresh bad pattern from being stored, so
    // this only fires for pre-existing or hand-edited config).
    fun hits(
        messageBody: String,
        sender: String,
        slot: Int?,
    ): Boolean {
        val facetHits =
            buildList {
                if (body.isNotBlank()) add(facetHits(body, messageBody))
                // The rule's sender facet (this@FilterRule.sender — the message's sender
                // parameter shadows it) matched against the stored phone number.
                if (this@FilterRule.sender.isNotBlank()) add(facetHits(this@FilterRule.sender, sender))
                if (slots.isNotEmpty()) add(slot != null && slot in slots)
            }
        return facetHits.isNotEmpty() && facetHits.all { it }
    }

    private fun facetHits(
        pattern: String,
        value: String,
    ): Boolean {
        val compiled = runCatching { Regex(pattern) }
        if (compiled.isFailure) {
            // Debug level: this runs per SMS x rule x facet, so a stored bad pattern must
            // not flood the buffer at warning level on every evaluation.
            // No e.message: a compile error echoes the user's pattern text.
            Logger.d(
                "SmsFilter",
                "filter facet pattern does not compile, the facet never hits: ${compiled.exceptionOrNull()?.javaClass?.simpleName}",
            )
            return false
        }
        return compiled.getOrThrow().containsMatchIn(value)
    }
}

// The user-configurable message filter: the mode's polarity over the OR'd rules
// (any active rule hits -> the mode's action fires). A filter without an active
// rule is the off state in EITHER mode: everything forwards, nothing is parked —
// (SKIP, []) is the product default, (FORWARD, []) the same off state reached
// when the user deletes the last rule.
data class SmsFilter(
    val mode: FilterMode = FilterMode.SKIP,
    val rules: List<FilterRule> = emptyList(),
) {
    fun shouldForward(
        messageBody: String,
        sender: String,
        slot: Int?,
    ): Boolean {
        // No active rule (in either mode) = the off state: forward everything.
        if (rules.none { it.isActive }) return true
        val hit = rules.any { it.hits(messageBody, sender, slot) }
        return if (mode == FilterMode.SKIP) !hit else hit
    }

    // The persisted shape of the whole filter as one JSON document string (the
    // single prefs key and the export's field value). Enums as names, slots as
    // tray numbers 1..3, rule order = display order, no per-rule ids.
    fun toJson(): String {
        val rules = JSONArray()
        this.rules.forEach { rule ->
            rules.put(
                JSONObject()
                    .put("body", rule.body)
                    .put("sender", rule.sender)
                    .put("slots", JSONArray(rule.slots)),
            )
        }
        return JSONObject()
            .put("mode", mode.name)
            .put("rules", rules)
            .toString()
    }

    companion object {
        // The facet text's UTF-8 byte cap check (bytes, not chars: a Cyrillic value
        // hits the cap at half the length). The editor's save gate, the strict import
        // and the lenient parse all enforce the same bound.
        fun isFacetTooLong(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size > AppConfig.FILTER_FACET_MAX_BYTES

        // The live error state of a facet field while typing (the
        // EmailUtil.isInvalidEmail precedent): a non-blank value whose regex does not
        // compile.
        fun isInvalidRegex(value: String): Boolean = value.isNotBlank() && runCatching { Regex(value) }.isFailure

        // One facet field's error: the uncompilable pattern or the over-cap text —
        // the same two checks the rule-level aggregations run per facet, so the
        // field's live error and the save gate's checks are one predicate.
        fun isFacetInvalid(value: String): Boolean = isInvalidRegex(value) || isFacetTooLong(value)

        // The lenient parse of the prefs seed: a corrupt document degrades to the
        // nearest safe level, pinned per corruption kind, with one log line —
        // malformed JSON or an unknown mode -> filter off (a broken doc = filter
        // off = everything forwards, the safe direction for a forwarding app); a
        // slot value outside 1..3, or a facet value over the byte cap -> that rule
        // off (one corrupt value kills the rule, not the filter — no partial-value
        // salvage); a non-compiling regex is kept as-is (the predicate's fail-open
        // already makes the facet never hit at evaluation, so the parse drops
        // nothing for it); an over-cap rule list is truncated to the first 10 (a
        // hand-crafted huge doc cannot tax per-SMS evaluation). A document without
        // an active rule (in either mode) is the legal off state (the predicate
        // forwards everything for it), so it parses as itself, the mode included.
        fun fromJson(json: String): SmsFilter {
            if (json.isBlank()) return SmsFilter()
            return try {
                val obj = JSONObject(json)
                val mode = FilterMode.entries.firstOrNull { it.name == obj.optString("mode") }
                if (mode == null) {
                    degrade("unknown mode '${obj.optString("mode")}', the filter is off")
                } else {
                    val rawRules = obj.optJSONArray("rules")
                    val rules =
                        if (rawRules == null) {
                            emptyList()
                        } else {
                            (0 until rawRules.length())
                                .take(AppConfig.FILTER_MAX_RULES)
                                .mapNotNull { i -> parseRuleLenient(rawRules.optJSONObject(i)) }
                        }
                    SmsFilter(mode, rules)
                }
            } catch (e: Exception) {
                degrade("malformed filter config, the filter is off: ${e.message}")
            }
        }

        private fun degrade(reason: String): SmsFilter {
            Logger.w("SmsFilter", "filter config degraded: $reason")
            return SmsFilter()
        }

        // One rule of the lenient parse: a non-object entry, a slot outside 1..3,
        // or an over-cap facet kills the rule (null), never the filter; a
        // non-compiling regex is kept (fail-open at evaluation). Unknown per-rule
        // fields are ignored, so such documents keep parsing.
        private fun parseRuleLenient(json: JSONObject?): FilterRule? {
            val obj = json ?: return null
            val body = obj.optString("body")
            val sender = obj.optString("sender")
            if (isFacetTooLong(body) || isFacetTooLong(sender)) return null
            val slots =
                try {
                    obj
                        .optJSONArray("slots")
                        ?.let { arr -> (0 until arr.length()).map { i -> arr.getInt(i) } }
                        ?: emptyList()
                } catch (e: Exception) {
                    return null
                }
            if (slots.any { it !in 1..3 }) return null
            return FilterRule(body, sender, slots)
        }

        // The strict parse of the settings import: any invalid part (an unknown
        // mode, a regex that does not compile, a facet value over the byte cap, a
        // slot outside 1..3, a present rules value that is not an array, more rules
        // than the cap) rejects the file with a JSONException, like a bad
        // dedup_level name. An absent rules key is the off state (no rules), and a
        // document without an active rule (in either mode) is the legal off state.
        // Unknown per-rule fields are ignored, not rejected, so such exports keep
        // importing.
        fun parseStrict(json: String): SmsFilter {
            val obj = JSONObject(json)
            val mode =
                FilterMode.entries.firstOrNull { it.name == obj.optString("mode") }
                    ?: throw JSONException("Invalid filter_config: unknown mode")
            val rawRules = obj.optJSONArray("rules")
            if (obj.has("rules") && rawRules == null) {
                throw JSONException("Invalid filter_config: rules is not an array")
            }
            if (rawRules != null && rawRules.length() > AppConfig.FILTER_MAX_RULES) {
                throw JSONException("Invalid filter_config: too many rules")
            }
            val rules =
                if (rawRules == null) {
                    emptyList()
                } else {
                    (0 until rawRules.length())
                        .map { i -> parseRuleStrict(rawRules.optJSONObject(i)) }
                }
            return SmsFilter(mode, rules)
        }

        private fun parseRuleStrict(json: JSONObject?): FilterRule {
            val obj = json ?: throw JSONException("Invalid filter_config: rule is not an object")
            val body = obj.optString("body")
            val sender = obj.optString("sender")
            if (isFacetTooLong(body)) throw JSONException("Invalid filter_config: body over the byte cap")
            if (isFacetTooLong(sender)) throw JSONException("Invalid filter_config: sender over the byte cap")
            if (body.isNotBlank()) compileOrThrow(body)
            if (sender.isNotBlank()) compileOrThrow(sender)
            val slots =
                try {
                    obj
                        .optJSONArray("slots")
                        ?.let { arr -> (0 until arr.length()).map { i -> arr.getInt(i) } }
                        ?: emptyList()
                } catch (e: JSONException) {
                    throw JSONException("Invalid filter_config: slots are not ints")
                }
            if (slots.any { it !in 1..3 }) throw JSONException("Invalid filter_config: slot outside 1..3")
            return FilterRule(body, sender, slots)
        }

        private fun compileOrThrow(pattern: String) {
            runCatching { Regex(pattern) }
                .onFailure { throw JSONException("Invalid filter_config: ${it.message}") }
        }
    }
}
