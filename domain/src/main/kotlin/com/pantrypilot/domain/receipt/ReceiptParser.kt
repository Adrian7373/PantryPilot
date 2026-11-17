package com.pantrypilot.domain.receipt

import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.quantity.SizeParser
import kotlin.math.abs
import kotlin.math.min

/** One line of recognized text and where it sits on the photo, in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = bottom - top
}

/**
 * OCR returns an item's name and its price as separate pieces of text, because they sit at
 * opposite edges of the paper. This puts pieces that share a baseline back into one row.
 */
object ReceiptLayout {
    fun rows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            val sameRow = row != null &&
                abs(line.centerY - row.map { it.centerY }.average()) < 0.6 * min(line.height, row.minOf { it.height })
            if (sameRow) row!!.add(line) else rows.add(mutableListOf(line))
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }
}

/**
 * A purchased line as printed. [priceCents] is the line total, covering the whole [quantity].
 * [size] is one package's size when the receipt shows it ("1GAL", or the weight of weighed produce).
 */
data class ReceiptLine(val rawName: String, val quantity: Int = 1, val priceCents: Long, val size: Quantity? = null)

/** Picks the purchased items out of a receipt's rows, ignoring headers, tax, totals and payment lines. */
object ReceiptParser {
    private const val AMOUNT = """(\d{1,3}(?:,\d{3})+|\d{1,6})[.,](\d{2})"""
    private const val CURRENCY = """[$€£₱]?"""

    /** Name, then a price at the end of the row, optionally followed by tax flag letters. */
    private val PRICED = Regex("""^(.*?)\s*(-)?\s*${CURRENCY}\s?${AMOUNT}\s?(-)?\s?[A-Z]{0,2}\*?$""")
    private val QUANTITY_ONLY = Regex("""^(\d{1,2})\s?[@xX]\s?${CURRENCY}\d+[.,]\d{2}(?:\s?(?:EA|ea))?$""")
    private val QUANTITY_INLINE = Regex("""(?:^|\s)(\d{1,2})\s?[@xX]\s?${CURRENCY}\d+[.,]\d{2}(?:\s|$)""")
    private val QUANTITY_LEADING = Regex("""^(\d{1,2})\s?[xX]\s+""")
    private val WEIGHT_DETAIL = Regex("""\d+(?:[.,]\d+)?\s?(?:kg|lbs?|g|oz)\b\s*@.*""", RegexOption.IGNORE_CASE)
    private val PRODUCT_CODE = Regex("""\d{4,}""")
    private val NOISE = Regex("""[^\p{L}\p{N}\s&%/'.-]""")

    private val TOTAL = Regex("""\b(SUB\s?TOTAL|TOTAL)\b""")
    private val NOT_AN_ITEM = Regex(
        """\b(TAX|VAT|DISCOUNT|COUPON|SAVINGS?|DEPOSIT|CASH|CHANGE|BALANCE|TENDER|ROUNDING|VISA|MASTERCARD|DEBIT|CREDIT)\b""",
    )

    fun parse(rows: List<String>): List<ReceiptLine> {
        val items = mutableListOf<ReceiptLine>()
        // A name printed on its own row, with its price on the row below (common for weighed produce).
        var pendingName: String? = null

        for (row in rows.map(String::trim)) {
            if (TOTAL.containsMatchIn(row.uppercase())) break // everything below is totals and payment

            val quantityOnly = QUANTITY_ONLY.matchEntire(row)
            if (quantityOnly != null) {
                items.lastOrNull()?.let { items[items.lastIndex] = it.copy(quantity = quantityOnly.groupValues[1].toInt()) }
                pendingName = null
                continue
            }

            val priced = PRICED.matchEntire(row)
            if (priced == null) {
                pendingName = cleanName(row).takeIf { isName(it) && !NOT_AN_ITEM.containsMatchIn(it.uppercase()) }
                continue
            }

            val (namePart, leadingMinus, whole, cents, trailingMinus) = priced.destructured
            val nameAbove = pendingName
            pendingName = null
            if (leadingMinus.isNotEmpty() || trailingMinus.isNotEmpty()) continue // a discount or refund

            var quantity = 1
            val weighed = WEIGHT_DETAIL.find(namePart)?.value?.let(SizeParser::find)
            var name = namePart.replace(WEIGHT_DETAIL, " ")
            (QUANTITY_INLINE.find(name) ?: QUANTITY_LEADING.find(name))?.let { match ->
                quantity = match.groupValues[1].toInt()
                name = name.removeRange(match.range)
            }
            name = cleanName(name)
            if (NOT_AN_ITEM.containsMatchIn(name.uppercase())) continue
            if (!isName(name)) name = nameAbove ?: continue

            items += ReceiptLine(
                rawName = name,
                quantity = quantity.coerceAtLeast(1),
                priceCents = whole.replace(",", "").toLong() * 100 + cents.toLong(),
                size = weighed ?: SizeParser.find(name),
            )
        }
        return items
    }

    private fun cleanName(text: String): String =
        text.replace(PRODUCT_CODE, " ").replace(NOISE, " ").replace(Regex("\\s+"), " ").trim()

    private fun isName(text: String): Boolean = text.count(Char::isLetter) >= 2
}
