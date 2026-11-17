package com.pantrypilot.domain.match

/** A shelf-life rule reduced to the words that identify it. */
data class RuleCandidate(val id: String, val terms: Set<String>) {
    constructor(id: String, vararg texts: String) : this(id, texts.flatMapTo(mutableSetOf(), RuleMatcher::tokenize))
}

/**
 * Suggests the shelf-life rule that best fits a product description, by shared words.
 * It is a suggestion for the user to confirm, not a decision.
 */
object RuleMatcher {
    fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 3 }
            .mapTo(mutableSetOf(), ::singular)

    /** Returns the id of the candidate sharing the most words with [text], or null if none share any. */
    fun bestMatch(text: String, candidates: List<RuleCandidate>): String? {
        val words = tokenize(text)
        return candidates
            .map { it to it.terms.count(words::contains) }
            .filter { (_, shared) -> shared > 0 }
            // Most shared words wins. On a tie, prefer the rule that is more fully described by them.
            .maxWithOrNull(
                compareBy<Pair<RuleCandidate, Int>> { (_, shared) -> shared }
                    .thenBy { (candidate, shared) -> shared.toDouble() / candidate.terms.size },
            )
            ?.first?.id
    }

    /** The winning rule, and for each abbreviated word the full rule word it stood for. */
    data class FuzzyMatch(val id: String, val resolved: Map<String, String>)

    /**
     * Like [bestMatch], but also accepts abbreviations: a word may be the start of a rule word
     * ("tom" for "tomato") or its consonants ("spn" for "spinach"). Used for receipt text.
     */
    fun bestFuzzyMatch(words: List<String>, candidates: List<RuleCandidate>): FuzzyMatch? {
        data class Scored(val candidate: RuleCandidate, val score: Double, val resolved: Map<String, String>)

        return candidates
            .map { candidate ->
                var score = 0.0
                val resolved = mutableMapOf<String, String>()
                for (word in words.distinct()) {
                    val (term, similarity) = candidate.terms
                        .map { it to similarity(word, it) }
                        .maxByOrNull { it.second } ?: continue
                    score += similarity
                    if (similarity > 0.0 && similarity < 1.0) resolved[word] = term
                }
                Scored(candidate, score, resolved)
            }
            .filter { it.score >= WEAKEST_ACCEPTED }
            .maxWithOrNull(compareBy<Scored> { it.score }.thenBy { it.score / it.candidate.terms.size })
            ?.let { FuzzyMatch(it.candidate.id, it.resolved) }
    }

    private const val WEAKEST_ACCEPTED = 0.6

    private fun similarity(word: String, term: String): Double = when {
        word == term -> 1.0
        word.length < 3 -> 0.0
        term.startsWith(word) -> 0.8
        // Vowels dropped, as tills do: same first letter, no other vowels, letters in order.
        word[0] == term[0] && word.drop(1).none { it in "aeiou" } && isSubsequence(word, term) -> 0.6
        else -> 0.0
    }

    private fun isSubsequence(short: String, long: String): Boolean {
        var i = 0
        for (c in long) if (i < short.length && short[i] == c) i++
        return i == short.length
    }

    fun singular(word: String): String = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("oes") -> word.dropLast(2)
        word.endsWith("ss") -> word
        word.endsWith("s") && word.length > 3 -> word.dropLast(1)
        else -> word
    }
}
