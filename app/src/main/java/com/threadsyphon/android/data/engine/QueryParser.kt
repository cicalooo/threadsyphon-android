package com.threadsyphon.android.data.engine

import com.threadsyphon.android.data.model.CatalogThread

/**
 * Simplified port of Windows query.py: OR of AND-groups with
 * title/tag/body contains, NOT, and basic field filters.
 */
data class Clause(
    val kind: String,
    val value: String,
    val negated: Boolean = false,
)

data class QueryAst(
    val groups: List<List<Clause>>,
) {
    val isEmpty: Boolean get() = groups.isEmpty() || groups.all { it.isEmpty() }
}

private val TAG_RE = Regex("""^/[a-z0-9]{1,20}/$""", RegexOption.IGNORE_CASE)

fun parseQuery(text: String): QueryAst {
    var raw = text.trim()
    if (raw.startsWith("=")) raw = raw.drop(1).trim()
    if (raw.isEmpty()) return QueryAst(listOf(emptyList()))

    val groups = mutableListOf(mutableListOf<Clause>())
    var pendingNot = false
    var i = 0
    while (i < raw.length) {
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length) break

        when {
            raw.startsWith("OR", i) && (i + 2 >= raw.length || !raw[i + 2].isLetterOrDigit()) -> {
                if (pendingNot || groups.last().isEmpty()) error("OR needs a term on both sides.")
                groups.add(mutableListOf())
                i += 2
            }
            raw.startsWith("NOT", i) && (i + 3 >= raw.length || !raw[i + 3].isLetterOrDigit()) -> {
                pendingNot = true
                i += 3
            }
            raw[i] == '-' -> {
                pendingNot = true
                i++
            }
            else -> {
                val (token, next) = nextToken(raw, i)
                i = next
                val negated = pendingNot
                pendingNot = false
                when {
                    token.startsWith("FIELD:") -> {
                        val payload = token.removePrefix("FIELD:")
                        val (field, value) = payload.split(":", limit = 2).let {
                            it[0] to it.getOrElse(1) { "" }
                        }
                        groups.last().add(fieldClause(field, value, negated))
                    }
                    TAG_RE.matches(token) -> {
                        groups.last().add(Clause("tag", token.trim('/').lowercase(), negated))
                    }
                    else -> groups.last().add(Clause("text", token.lowercase(), negated))
                }
            }
        }
    }
    if (pendingNot) error("Query operator needs a following term.")
    return QueryAst(groups)
}

private fun nextToken(s: String, start: Int): Pair<String, Int> {
    var i = start
    if (i >= s.length) return "" to i
    if (s[i] == '"' || s[i] == '\'') {
        val q = s[i]
        val j = s.indexOf(q, i + 1).let { if (it < 0) error("Unclosed quote") else it }
        return s.substring(i + 1, j) to (j + 1)
    }
    // field:value
    var j = i
    while (j < s.length && !s[j].isWhitespace()) j++
    val word = s.substring(i, j)
    val fields = setOf(
        "title", "sub", "body", "com", "id", "no", "board", "tag",
        "min_images", "min_replies", "max_images", "max_replies",
    )
    if (':' in word) {
        val field = word.substringBefore(':').lowercase()
        val rest = word.substringAfter(':')
        if (field in fields) {
            if (rest.isEmpty()) error("Missing value after $field:")
            return "FIELD:$field:$rest" to j
        }
    }
    return word to j
}

private fun fieldClause(field: String, value: String, negated: Boolean): Clause {
    return when (field) {
        "title", "sub" -> Clause("title", value.lowercase(), negated)
        "body", "com" -> Clause("body", value.lowercase(), negated)
        "tag" -> Clause("tag", value.trim('/').lowercase(), negated)
        "board" -> Clause("board", value.lowercase(), negated)
        "id", "no" -> Clause("id", value, negated)
        "min_images" -> Clause("min_images", value, negated)
        "max_images" -> Clause("max_images", value, negated)
        "min_replies" -> Clause("min_replies", value, negated)
        "max_replies" -> Clause("max_replies", value, negated)
        else -> Clause("text", value.lowercase(), negated)
    }
}

fun matches(thread: CatalogThread, query: QueryAst): Boolean {
    if (query.isEmpty) return true
    return query.groups.any { group ->
        group.isNotEmpty() && group.all { clause ->
            val hit = matchClause(thread, clause)
            if (clause.negated) !hit else hit
        }
    }
}

private fun matchClause(t: CatalogThread, c: Clause): Boolean {
    val title = t.title.lowercase()
    val body = t.body.lowercase()
    return when (c.kind) {
        "text" -> title.contains(c.value) || body.contains(c.value)
        "title" -> title.contains(c.value)
        "body" -> body.contains(c.value)
        "tag" -> Regex("""/(?:${Regex.escape(c.value)})/""", RegexOption.IGNORE_CASE).containsMatchIn(t.title)
        "board" -> t.board.equals(c.value, ignoreCase = true)
        "id" -> t.no.toString() == c.value
        "min_images" -> t.images >= (c.value.toIntOrNull() ?: 0)
        "max_images" -> t.images <= (c.value.toIntOrNull() ?: Int.MAX_VALUE)
        "min_replies" -> t.replies >= (c.value.toIntOrNull() ?: 0)
        "max_replies" -> t.replies <= (c.value.toIntOrNull() ?: Int.MAX_VALUE)
        else -> false
    }
}

fun filterCatalog(threads: List<CatalogThread>, queryText: String): List<CatalogThread> {
    val q = parseQuery(queryText)
    return threads.filter { matches(it, q) }
}
