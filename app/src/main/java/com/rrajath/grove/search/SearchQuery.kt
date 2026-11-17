package com.rrajath.grove.search

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Orgzly-compatible structured search (PRD §5.5).
 * Space (or `AND`) = AND, `OR` = or (binds looser; both case-insensitive),
 * `.` prefix = NOT, `( … )` groups and nests, `.( … )` negates a group,
 * `"…"` quotes a value or literal text, `o.PROP` = sort (`.o.PROP` reversed),
 * `ad.N` = agenda day-grouping.
 */
data class SearchQuery(
    /**
     * The query flattened to an OR of AND-groups (disjunctive normal form).
     * FTS/SQL narrowing, snippets and file-name matching all work off this
     * shape. Empty when the query has no terms, or when flattening would
     * exceed [QueryParser.MAX_GROUPS]: then [expr] still decides every match
     * and narrowing falls back to a full scan.
     */
    val groups: List<List<Term>>,
    val sortBy: List<SortKey> = emptyList(),
    val agendaDays: Int? = null,
    /** The query as a tree with negation already pushed down to the terms. Null = match everything. */
    val expr: Expr? = Expr.fromGroups(groups),
) {
    val isEmpty: Boolean get() = expr == null && agendaDays == null

    /** True when [expr] has terms but was too large to flatten into [groups]. */
    val isFlatteningSkipped: Boolean get() = groups.isEmpty() && expr != null

    /** Plain full-text terms (for FTS narrowing and match highlighting). */
    val textTerms: List<String>
        get() = expr?.leaves().orEmpty().filter { !it.negated && it.condition is Condition.Text }
            .map { (it.condition as Condition.Text).term }
}

data class Term(val condition: Condition, val negated: Boolean)

/** A boolean query tree in negation normal form: only [Leaf] terms carry a NOT. */
sealed interface Expr {
    data class Leaf(val term: Term) : Expr
    data class And(val children: List<Expr>) : Expr
    data class Or(val children: List<Expr>) : Expr

    fun matches(test: (Term) -> Boolean): Boolean = when (this) {
        is Leaf -> test(term)
        is And -> children.all { it.matches(test) }
        is Or -> children.any { it.matches(test) }
    }

    fun leaves(): List<Term> = when (this) {
        is Leaf -> listOf(term)
        is And -> children.flatMap { it.leaves() }
        is Or -> children.flatMap { it.leaves() }
    }

    companion object {
        /** The tree for an already-flat OR of AND-groups (empty groups are dropped). */
        fun fromGroups(groups: List<List<Term>>): Expr? {
            val nonEmpty = groups.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) return null
            return Or(nonEmpty.map { group -> And(group.map(::Leaf)) })
        }
    }
}

/** The non-negated plain-text terms of one AND-group. */
fun List<Term>.textTerms(): List<String> =
    filter { !it.negated && it.condition is Condition.Text }.map { (it.condition as Condition.Text).term }

sealed class Condition {
    data class Text(val term: String) : Condition()

    /** `i.STATE`: keyword match, case-insensitive; `i.none` = no keyword. */
    data class State(val state: String) : Condition()

    /** `it.todo` / `it.done` / `it.none`: any not-done keyword, any done keyword, or none. */
    data class StateType(val type: Type) : Condition() {
        enum class Type { TODO, DONE, NONE }
    }

    data class Notebook(val name: String) : Condition()
    data class Tag(val tag: String, val ownOnly: Boolean) : Condition()

    /** `p.X`: priority X, counting a note with no priority as the configured
     *  default priority (org's `org-default-priority`); `ps.X` ([setOnly]):
     *  only notes where X is written on the heading. */
    data class Priority(val priority: String, val setOnly: Boolean = false) : Condition()
    data class Scheduled(val period: Period) : Condition()
    data class Deadline(val period: Period) : Condition()

    /** `a.PERIOD` (alias `e.`): a bare active timestamp (event) on a matching
     *  day; `a.overdue` still matches a past event (only the agenda suppresses those). */
    data class Active(val period: Period) : Condition()

    data class Closed(val period: Period) : Condition()
    data class Created(val period: Period) : Condition()

    /** `id.X`: the heading's `:ID:` or `:CUSTOM_ID:` starts with X, ignoring
     *  case; `id.none` (see [isNone]) matches a heading with neither. */
    data class Id(val prefix: String) : Condition() {
        val isNone: Boolean get() = prefix.equals("none", ignoreCase = true)
    }
}

/** `eq`/`ne`/`lt`/`le`/`gt`/`ge` in `s.le.today`, `c.eq.yesterday`, etc. */
enum class CompareOp {
    EQ, NE, LT, LE, GT, GE;

    fun test(date: LocalDate, target: LocalDate): Boolean = when (this) {
        EQ -> date == target
        NE -> date != target
        LT -> date.isBefore(target)
        LE -> !date.isAfter(target)
        GT -> date.isAfter(target)
        GE -> !date.isBefore(target)
    }

    /**
     * Against a moment: a [Span] with no end is one instant and compares
     * exactly; a span `[start, end)` matches when any moment inside it does,
     * so `eq` means the moment falls inside it and `ne` that it doesn't.
     */
    fun test(span: Span, moment: LocalDateTime): Boolean {
        val (start, end) = span
        if (end == null) return when (this) {
            EQ -> start == moment
            NE -> start != moment
            LT -> start.isBefore(moment)
            LE -> !start.isAfter(moment)
            GT -> start.isAfter(moment)
            GE -> !start.isBefore(moment)
        }
        val inside = !moment.isBefore(start) && moment.isBefore(end)
        return when (this) {
            EQ -> inside
            NE -> !inside
            LT -> start.isBefore(moment)
            LE -> !start.isAfter(moment)
            GT, GE -> end.isAfter(moment)
        }
    }

    companion object {
        fun parse(token: String): CompareOp? = entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}

/** The time a timestamp covers: [start] alone when [end] is null, else `[start, end)`. */
data class Span(val start: LocalDateTime, val end: LocalDateTime?)

/**
 * A date token, Orgzly style: `[OP.]TIME`. TIME names either one day (`today`,
 * `tomorrow`, `yesterday`, a signed `Nd`/`Nw`/`Nm`/`Ny` offset from today, or
 * an ISO `yyyy-mm-dd` date) or one moment (`now`, a signed `Nh` offset from
 * now; see [isMoment]), and the timestamp is compared against it with [op].
 * Without an op each prefix applies its own default (see `QueryMatcher`):
 * `le` for s./d./cr., `eq` for c./a. Grove additionally keeps two op-less
 * specials: `overdue` and `none`/`no`/`nodate`.
 */
data class Period(val raw: String, val op: CompareOp? = null) {
    /** `s.none` (aliases `no`, `nodate`): matches when the timestamp itself is
     *  absent, the opposite of every other period (which requires one). */
    val isNoDate: Boolean get() = op == null && raw.lowercase() in NO_DATE

    /** `s.overdue`/`d.overdue`/etc.: the timestamp is strictly before today. */
    val isOverdue: Boolean get() = op == null && raw.equals("overdue", ignoreCase = true)

    /** `now` and `Nh` name a moment rather than a day: they compare against
     *  the time of day too (see [targetMoment]). */
    val isMoment: Boolean
        get() = raw.lowercase().let { it == "now" || SIGNED.matchEntire(it)?.groupValues?.get(3) == "h" }

    /** The day this token names, or null when it names none (`overdue`,
     *  `none`, `Nh`, junk). `now` names today. */
    fun target(today: LocalDate): LocalDate? {
        when (raw.lowercase()) {
            "today", "tod", "now" -> return today
            "tomorrow", "tom", "tmrw" -> return today.plusDays(1)
            "yesterday" -> return today.minusDays(1)
        }
        SIGNED.matchEntire(raw.lowercase())?.let { m ->
            val n = signedCount(m)
            return when (m.groupValues[3]) {
                "d" -> today.plusDays(n)
                "w" -> today.plusWeeks(n)
                "m" -> today.plusMonths(n)
                "y" -> today.plusYears(n)
                else -> null
            }
        }
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    /** The moment a [isMoment] token names, counted from [now] (to the
     *  minute, the precision of an org timestamp); null for a day token. */
    fun targetMoment(now: LocalDateTime): LocalDateTime? {
        val base = now.truncatedTo(ChronoUnit.MINUTES)
        val lower = raw.lowercase()
        if (lower == "now") return base
        val m = SIGNED.matchEntire(lower)?.takeIf { it.groupValues[3] == "h" } ?: return null
        return base.plusHours(signedCount(m))
    }

    private fun signedCount(m: MatchResult): Long =
        m.groupValues[2].toLong().let { if (m.groupValues[1] == "-") -it else it }

    companion object {
        private val SIGNED = Regex("""([+-]?)(\d+)([hdwmy])""")
        private val NO_DATE = setOf("none", "no", "nodate")

        /** `today` → default op; `le.today` → explicit op. */
        fun parse(value: String): Period {
            val dot = value.indexOf('.')
            if (dot > 0) {
                CompareOp.parse(value.substring(0, dot))?.let { op ->
                    val rest = value.substring(dot + 1)
                    if (rest.isNotEmpty()) return Period(rest, op)
                }
            }
            return Period(value)
        }
    }
}

/** What `o.PROP` sorts by; Orgzly's property names plus Grove's older `a`/`active`. */
enum class SortField(vararg val aliases: String) {
    NOTEBOOK("b", "book", "notebook"),
    TITLE("t", "title"),
    SCHEDULED("s", "sched", "scheduled"),
    DEADLINE("d", "dead", "deadline"),
    EVENT("e", "event", "a", "active"),
    CLOSED("c", "close", "closed"),
    CREATED("cr", "created"),
    PRIORITY("p", "pri", "prio", "priority"),
    STATE("st", "state");

    companion object {
        fun parse(name: String): SortField? = entries.firstOrNull { name.lowercase() in it.aliases }
    }
}

/** One `o.PROP` (ascending) or `.o.PROP` (descending) directive. */
data class SortKey(val field: SortField, val descending: Boolean = false)

object QueryParser {

    /**
     * Flattening multiplies out every AND of ORs, so `(a OR b) (c OR d) …`
     * doubles per group. Past this many AND-groups the flat form is skipped
     * (see [SearchQuery.groups]); typed queries never get near it.
     */
    const val MAX_GROUPS = 64

    fun parse(input: String): SearchQuery {
        val parser = Parser(tokenize(input))
        val expr = parser.parseExpr()
        return SearchQuery(
            groups = expr?.let { dnf(it) }.orEmpty(),
            sortBy = parser.sortBy,
            agendaDays = parser.agendaDays,
            expr = expr,
        )
    }

    private sealed interface Token {
        /** [literal]: the word started with a quote (after an optional `.`),
         *  so it is always plain text, never a prefix like `i.` or `o.`. */
        data class Word(val text: String, val literal: Boolean = false) : Token
        data object Open : Token
        data object Close : Token

        /** A `.` written directly before `(`. */
        data object Not : Token
        data object Or : Token
        data object And : Token
    }

    /**
     * Whitespace separates words; `(` and `)` are always tokens of their own.
     * `"…"` keeps spaces, brackets and keywords inside one word (`b."My
     * Notebook"`, `"or"`, `"f(x)"`); an unclosed quote runs to the end.
     */
    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        var quoted = false
        var literal = false
        var hadQuote = false
        fun flush() {
            if (word.isEmpty() && !hadQuote) return
            val text = word.toString()
            tokens += when {
                hadQuote -> Token.Word(text, literal)
                text.equals("OR", ignoreCase = true) -> Token.Or
                text.equals("AND", ignoreCase = true) -> Token.And
                else -> Token.Word(text)
            }
            word.clear()
            literal = false
            hadQuote = false
        }
        for (ch in input) {
            when {
                ch == '"' -> {
                    if (!quoted && (word.isEmpty() || word.toString() == ".")) literal = true
                    quoted = !quoted
                    hadQuote = true
                }
                quoted -> word.append(ch)
                ch.isWhitespace() -> flush()
                ch == '(' -> {
                    if (word.toString() == "." && !hadQuote) {
                        word.clear()
                        tokens += Token.Not
                    } else {
                        flush()
                    }
                    tokens += Token.Open
                }
                ch == ')' -> {
                    flush()
                    tokens += Token.Close
                }
                else -> word.append(ch)
            }
        }
        flush()
        return tokens
    }

    /**
     * Recursive descent over:
     * ```
     * expr  := and ("OR" and)*
     * and   := unary (["AND"] unary)*
     * unary := "." "(" expr ")" | "(" expr ")" | word
     * ```
     * Lenient by design: a missing `)` closes at the end, a stray `)` and an
     * empty `()` are ignored. `o.`/`ad.` words are global modifiers wherever
     * they appear and contribute no term.
     */
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private var depth = 0
        val sortBy = mutableListOf<SortKey>()
        var agendaDays: Int? = null

        private fun peek(): Token? = tokens.getOrNull(pos)

        fun parseExpr(): Expr? {
            val parts = mutableListOf<Expr>()
            parseAnd()?.let { parts += it }
            while (peek() == Token.Or) {
                pos++
                parseAnd()?.let { parts += it }
            }
            return when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> Expr.Or(parts)
            }
        }

        private fun parseAnd(): Expr? {
            val parts = mutableListOf<Expr>()
            while (true) {
                when (peek()) {
                    null, Token.Or -> break
                    Token.Close -> if (depth > 0) break else pos++
                    Token.And -> pos++
                    else -> parseUnary()?.let { parts += it }
                }
            }
            return when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> Expr.And(parts)
            }
        }

        private fun parseUnary(): Expr? = when (val token = tokens[pos++]) {
            // The tokenizer only emits Not directly before Open.
            Token.Not -> parseUnary()?.let(::negate)
            Token.Open -> {
                depth++
                val inner = parseExpr()
                if (peek() == Token.Close) pos++
                depth--
                inner
            }
            is Token.Word -> if (token.literal) literal(token.text) else word(token.text)
            // Close/Or/And are consumed by parseAnd before reaching here.
            else -> null
        }

        /** A quoted word: text as written, `.` still negates it; `""` is nothing. */
        private fun literal(token: String): Expr? {
            val negated = token.startsWith(".")
            val body = if (negated) token.drop(1) else token
            if (body.isEmpty()) return null
            return Expr.Leaf(Term(Condition.Text(body), negated))
        }

        private fun word(token: String): Expr? {
            val negated = token.startsWith(".") && token.length > 1 && !token[1].isDigit()
            val body = if (negated) token.drop(1) else token
            val lower = body.lowercase()
            return when {
                lower.startsWith("o.") -> {
                    SortField.parse(lower.drop(2))?.let { sortBy += SortKey(it, descending = negated) }
                    null
                }
                lower.startsWith("ad.") -> {
                    agendaDays = body.drop(3).toIntOrNull()
                    null
                }
                else -> Expr.Leaf(Term(parseCondition(body), negated))
            }
        }
    }

    /** De Morgan: pushes a NOT down to the terms, keeping the tree in negation normal form. */
    private fun negate(expr: Expr): Expr = when (expr) {
        is Expr.Leaf -> Expr.Leaf(expr.term.copy(negated = !expr.term.negated))
        is Expr.And -> Expr.Or(expr.children.map(::negate))
        is Expr.Or -> Expr.And(expr.children.map(::negate))
    }

    /** Disjunctive normal form (an OR of AND-groups), or null past [MAX_GROUPS]. */
    private fun dnf(expr: Expr): List<List<Term>>? {
        when (expr) {
            is Expr.Leaf -> return listOf(listOf(expr.term))
            is Expr.Or -> {
                val out = mutableListOf<List<Term>>()
                for (child in expr.children) {
                    out += dnf(child) ?: return null
                    if (out.size > MAX_GROUPS) return null
                }
                return out
            }
            is Expr.And -> {
                var acc: List<List<Term>> = listOf(emptyList())
                for (child in expr.children) {
                    val groups = dnf(child) ?: return null
                    if (acc.size.toLong() * groups.size > MAX_GROUPS) return null
                    acc = acc.flatMap { left -> groups.map { right -> left + right } }
                }
                return acc
            }
        }
    }

    private fun parseCondition(body: String): Condition {
        val dot = body.indexOf('.')
        if (dot in 1..2) {
            val prefix = body.substring(0, dot).lowercase()
            val value = body.substring(dot + 1)
            if (value.isNotEmpty()) {
                when (prefix) {
                    "i" -> return Condition.State(value)
                    "it" -> stateType(value)?.let { return Condition.StateType(it) }
                    "b" -> return Condition.Notebook(value)
                    "t" -> return Condition.Tag(value, ownOnly = false)
                    "tn" -> return Condition.Tag(value, ownOnly = true)
                    "p" -> return Condition.Priority(value)
                    "ps" -> return Condition.Priority(value, setOnly = true)
                    "s" -> return Condition.Scheduled(Period.parse(value))
                    "d" -> return Condition.Deadline(Period.parse(value))
                    "a", "e" -> return Condition.Active(Period.parse(value))
                    "c" -> return Condition.Closed(Period.parse(value))
                    "cr" -> return Condition.Created(Period.parse(value))
                    "id" -> return Condition.Id(value)
                }
            }
        }
        return Condition.Text(body)
    }

    private fun stateType(value: String): Condition.StateType.Type? = when (value.lowercase()) {
        "todo" -> Condition.StateType.Type.TODO
        "done" -> Condition.StateType.Type.DONE
        "none" -> Condition.StateType.Type.NONE
        else -> null
    }
}
