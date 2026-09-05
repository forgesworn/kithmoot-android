package dev.forgesworn.kithmoot.protocol

/**
 * Display names.
 *
 * A name in this protocol is **self-asserted**: whoever holds a participant
 * key types whatever they like, and nothing anywhere checks it. That is the
 * point - joining a room should cost a name and nothing else - but it means a
 * name is attacker-controlled text arriving off a relay, and every reader has
 * to treat it as such.
 *
 * Two rules follow, and they live in different places:
 *
 *   1. **Sanitise it.** Everything below. A name may not carry characters
 *      that let it take a second row, hide part of itself, or reverse the
 *      direction the rest of the line renders in, and it may not be long
 *      enough to push everything else off the row.
 *
 *   2. **Never render it alone.** The caller's job, because it is about what
 *      a name sits next to: a short pubkey always renders beside it, so two
 *      people called "Robin" stay distinguishable and an impersonation is
 *      visible.
 *
 * Ported from `src/display-name.ts` in the TypeScript reference and held to
 * the same `rosterEvent/display-name-hostile` vector, so the two clients
 * defuse a hostile name identically rather than each in their own way.
 */
object DisplayName {

    /**
     * How long a name may be, in characters.
     *
     * Counted in code points rather than UTF-16 units, so one emoji costs
     * one - a cap in UTF-16 units would let an astral-plane name be twice as
     * wide as an ASCII one, and could cut a surrogate pair in half.
     */
    const val MAX_LENGTH = 32

    /**
     * Every Unicode "other" character: controls, format characters,
     * surrogates, private use and unassigned code points.
     *
     * That single class covers the whole family of tricks a name is used
     * for: a newline taking a second row, a bidirectional override making
     * the rest of the line render backwards, a zero-width space hiding the
     * difference between two names, a byte-order mark padding one invisibly.
     * Naming the class rather than listing the code points is deliberate:
     * the list grows with Unicode, and a filter that has to be updated to
     * stay correct is one that will one day be out of date.
     *
     * Unicode categories work on both the JVM and Android without flags.
     * Android rejects Java's `(?U)` inline flag.
     */
    private val INVISIBLE = Regex("\\p{C}")

    /** ECMAScript whitespace, explicit so Java and Android agree with the wire reference. */
    private val WHITESPACE = Regex("[\\p{Zs}\\t\\n\\x0B\\f\\r\\u2028\\u2029\\uFEFF]+")

    /**
     * Make a name safe to put next to somebody else's, or return null if
     * there is nothing left worth showing.
     */
    fun sanitise(raw: String?): String? {
        if (raw == null) return null

        // Whitespace first, invisibles second, and the order matters: a
        // newline is itself a control character, so stripping controls first
        // would turn "Robin\nadmin" into one word rather than two - hiding
        // the smuggled line break instead of defusing it.
        val collapsed = raw
            .replace(WHITESPACE, " ")
            .replace(INVISIBLE, "")
            .replace(WHITESPACE, " ")
            .trim()
        if (collapsed.isEmpty()) return null

        // Counted and cut in code points: cutting in UTF-16 units would
        // leave a lone surrogate on the wire.
        val points = collapsed.codePoints().toArray()
        if (points.size <= MAX_LENGTH) return collapsed
        val capped = String(points, 0, MAX_LENGTH).trim()
        return capped.ifEmpty { null }
    }
}
