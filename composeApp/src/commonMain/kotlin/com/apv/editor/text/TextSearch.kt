package com.apv.editor.text

/**
 * Plain and regex search over a whole buffer. Pure Kotlin (no Compose, no JVM I/O) so it can live
 * in commonMain next to the syntax pipeline and be unit-tested there.
 */
object TextSearch {

    /** Upper bound on returned matches, so a one-character query in a huge buffer stays cheap to draw and count. */
    const val DEFAULT_LIMIT = 10_000

    /**
     * Returns the character ranges (inclusive [IntRange]s, in document order, non-overlapping) where
     * [query] occurs in [text]. An empty query or an invalid regular expression yields no matches.
     * Zero-length regex matches (e.g. `a*` against "b") are skipped.
     */
    fun findAll(
        text: String,
        query: String,
        caseSensitive: Boolean = false,
        regex: Boolean = false,
        limit: Int = DEFAULT_LIMIT,
    ): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<IntRange>()
        if (regex) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = try {
                Regex(query, options)
            } catch (_: Throwable) {
                return emptyList()
            }
            for (match in pattern.findAll(text)) {
                if (match.range.isEmpty()) continue
                out.add(match.range)
                if (out.size >= limit) break
            }
        } else {
            var from = 0
            while (from <= text.length - query.length) {
                val at = text.indexOf(query, startIndex = from, ignoreCase = !caseSensitive)
                if (at < 0) break
                out.add(at until (at + query.length))
                if (out.size >= limit) break
                from = at + query.length
            }
        }
        return out
    }

    /** True when [query] is usable as given: non-regex queries always are, regex ones only if they compile. */
    fun isValid(query: String, regex: Boolean): Boolean {
        if (!regex || query.isEmpty()) return true
        return try {
            Regex(query)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
