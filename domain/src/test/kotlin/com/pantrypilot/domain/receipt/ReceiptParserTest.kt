package com.pantrypilot.domain.receipt

import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.Quantity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptParserTest {
    @Test
    fun `layout joins a name and its price that share a baseline`() {
        val lines = listOf(
            OcrLine("2.99 F", left = 800, top = 102, right = 900, bottom = 132),
            OcrLine("WHL MLK 1GAL", left = 20, top = 100, right = 300, bottom = 130),
            OcrLine("FRESH MART", left = 300, top = 20, right = 600, bottom = 60),
            OcrLine("ORG BBY SPN 5OZ", left = 20, top = 140, right = 350, bottom = 170),
            OcrLine("3.49", left = 820, top = 143, right = 900, bottom = 171),
        )
        assertEquals(
            listOf("FRESH MART", "WHL MLK 1GAL 2.99 F", "ORG BBY SPN 5OZ 3.49"),
            ReceiptLayout.rows(lines),
        )
    }

    @Test
    fun `parses items and ignores header, tax, totals and payment`() {
        val rows = listOf(
            "FRESH MART",
            "123 MAIN STREET",
            "11/17/2026 14:32",
            "WHL MLK 1GAL 2.99 F",
            "0004011 BANANAS 0.88",
            "ORG BBY SPN 5OZ $3.49",
            "TAX 0.42",
            "SUBTOTAL 7.36",
            "TOTAL 7.78",
            "VISA 7.78",
        )
        assertEquals(
            listOf(
                ReceiptLine("WHL MLK 1GAL", 1, 299, Quantity(1.0, MeasureUnit.GALLON)),
                ReceiptLine("BANANAS", 1, 88),
                ReceiptLine("ORG BBY SPN 5OZ", 1, 349, Quantity(5.0, MeasureUnit.OUNCE)),
            ),
            ReceiptParser.parse(rows),
        )
    }

    @Test
    fun `reads quantities written inline, leading, and on the following row`() {
        val rows = listOf(
            "YOGURT 2 @ 1.50 3.00",
            "2 x PASTA SCE 5.98",
            "EGGS LRG 12CT 6.98",
            "2 @ 3.49",
        )
        assertEquals(
            listOf(
                ReceiptLine("YOGURT", 2, 300),
                ReceiptLine("PASTA SCE", 2, 598),
                ReceiptLine("EGGS LRG 12CT", 2, 698, Quantity(12.0, MeasureUnit.EACH)),
            ),
            ReceiptParser.parse(rows),
        )
    }

    @Test
    fun `weighed produce takes its name from the row above and its size from the weight`() {
        val rows = listOf("BANANAS", "0.68 kg @ 1.29/kg 0.88", "TOMATOES", "1.2 lb @ 2.49/lb 2.99")
        assertEquals(
            listOf(
                ReceiptLine("BANANAS", 1, 88, Quantity(0.68, MeasureUnit.KILOGRAM)),
                ReceiptLine("TOMATOES", 1, 299, Quantity(1.2, MeasureUnit.POUND)),
            ),
            ReceiptParser.parse(rows),
        )
    }

    @Test
    fun `skips discounts and handles comma decimals and thousands`() {
        val rows = listOf("GRND BF 1,249.50", "MEMBER DISCOUNT -2.00", "BREAD 3,25", "COUPON 1.00-")
        assertEquals(
            listOf(ReceiptLine("GRND BF", 1, 124950), ReceiptLine("BREAD", 1, 325)),
            ReceiptParser.parse(rows),
        )
    }
}
