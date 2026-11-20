package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.AppConfig
import java.util.Locale

// The SQL inputs of a list search, precomputed by the caller: the body LIKE pattern
// (already Unicode-lowercased and wildcard-escaped, compared against the pre-folded
// searchBody through LIKE ... ESCAPE '\'), the phone LIKE pattern (lowercased and
// reduced to letters/digits to mirror the stripped searchPhone facet, so sender search
// is separator-insensitive) and the facet inputs — E.164-normalized contact numbers
// (the digits-only fold when a number does not normalize) whose display name matched
// the query (compared against the pre-folded searchNumber) and the SIM slot ids whose
// display name matched. The repository binds a never-matching sentinel for an empty
// list (an empty IN (...) is a SQL syntax error).
data class SmsSearch(
    val noSearch: Boolean,
    val likePattern: String,
    val phoneLikePattern: String,
    val contactNumbers: List<String>,
    val simIds: List<Int>,
) {
    companion object {
        // A LIKE pattern that matches no row: the stripped searchPhone facet holds
        // letters/digits only, so this control character can never occur in it. Bound
        // when the query has no alphanumerics, so such a query cannot degrade into the
        // match-all "%%".
        private const val NO_MATCH_PHONE_PATTERN = "\u0001"

        fun none(): SmsSearch =
            SmsSearch(
                noSearch = true,
                likePattern = "",
                phoneLikePattern = "",
                contactNumbers = emptyList(),
                simIds = emptyList(),
            )

        fun forQuery(
            query: String,
            contactNumbers: List<String>,
            simIds: List<Int>,
        ): SmsSearch {
            val lowered = query.lowercase(Locale.ROOT)
            // Mirrors the stripped searchPhone facet (Sms.kt): the sender matches
            // regardless of separators; a query without alphanumerics binds the
            // never-matching pattern instead of the match-all "%%".
            val stripped = lowered.filter { it.isLetterOrDigit() }
            return SmsSearch(
                noSearch = false,
                likePattern = "%${escapeLike(lowered)}%",
                phoneLikePattern = if (stripped.isEmpty()) NO_MATCH_PHONE_PATTERN else "%$stripped%",
                // Capped: an unbounded IN list can exceed SQLite's variable limit on
                // older devices (999 before 3.32) and fail the whole query.
                contactNumbers = contactNumbers.take(AppConfig.SMS_SEARCH_MAX_CONTACT_NUMBERS),
                simIds = simIds,
            )
        }

        // Escapes the LIKE wildcards so a user query matches literally (case folding is
        // already done by the pre-folded searchBody column at insert, so only the LIKE
        // wildcards need escaping, not case).
        fun escapeLike(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}
