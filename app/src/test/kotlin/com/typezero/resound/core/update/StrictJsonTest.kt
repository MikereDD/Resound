package com.typezero.resound.core.update

import org.junit.Assert.*
import org.junit.Test

/** Shared syntax tests: no platform JSON tokenizer is used by the strict parser. */
class StrictJsonTest {
    private fun rejects(vararg fixtures: String) {
        fixtures.forEachIndexed { index, text ->
            try {
                StrictJson.requireObject(text)
                fail("accepted malformed fixture #$index")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().startsWith("Invalid strict JSON"))
            }
        }
    }

    @Test fun numericGrammarUsesAsciiDigitsOnly() {
        val arabicOne = '\u0661'
        rejects("{\"n\":1.$arabicOne}", "{\"n\":1e$arabicOne}", "{\"n\":1$arabicOne}")
    }

    @Test fun unicodeEscapeHexDigitsMustAlsoBeAscii() {
        rejects("{\"s\":\"\\u" + "\u0660".repeat(4) + "\"}",
            "{\"s\":\"\\u" + "\uFF10".repeat(4) + "\"}")
    }

    @Test fun rejectsUnpairedRawAndEscapedSurrogates() {
        rejects("{\"s\":\"" + '\uD800' + "\"}", "{\"s\":\"" + '\uDC00' + "\"}",
            "{\"s\":\"\\uD800\"}", "{\"s\":\"\\uDC00\"}", "{\"s\":\"\\uD800\\u0041\"}")
    }

    @Test fun duplicateChecksUseDecodedKeysAtEveryObjectDepth() {
        rejects("{\"a\":1,\"\\u0061\":2}", "{\"outer\":{\"x\":1,\"x\":2}}",
            "{\"items\":[{\"x\":1,\"\\u0078\":2}]}")
    }

    @Test fun trailingValuesAndLenientSyntaxAreRejected() {
        rejects("", "[]", "null", "{}{}", "{} true", "{} //comment", "{/*comment*/\"a\":1}",
            "{'a':1}", "{a:1}", "{\"a\"=1}", "{\"a\":NaN}", "{\"a\":Infinity}",
            "{\"a\":+1}", "{\"a\":01}", "{\"a\":1.}", "{\"a\":1e}", "{\"a\":1,}",
            "{\"a\":[1,]}", "{\"a\":[,1]}", "{\"a\":\"bad\\x20\"}", "\u00A0{}")
    }

    @Test fun acceptsValidEscapesNumbersAndSeparateObjectScopes() {
        StrictJson.requireObject(" \r\n\t{\"a\":{\"x\":1},\"b\":{\"x\":2}," +
            "\"numbers\":[true,false,null,-0,1.25,-2e+3,9223372036854775807]," +
            "\"escaped\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\uD834\\uDD1E\",\"raw\":\"\uD834\uDD1E\"} \n")
    }

    @Test fun nestingLimitRejectsAbuseWithoutRejectingOrdinaryArrays() {
        StrictJson.requireObject("{\"x\":" + "[".repeat(32) + "null" + "]".repeat(32) + "}")
        rejects("{\"x\":" + "[".repeat(80) + "null" + "]".repeat(80) + "}")
    }

    @Test fun primitiveTypesDoNotDependOnPlatformTokenizers() {
        val root = StrictJson.requireObject("{\"int\":2,\"long\":2147483648,\"fraction\":2.0," +
            "\"exponent\":1e0,\"negativeZero\":-0,\"yes\":true,\"no\":false,\"nil\":null}")
        assertTrue(root.get("int") is Int)
        assertTrue(root.get("long") is Long)
        listOf("fraction", "exponent", "negativeZero").forEach { assertTrue(root.get(it) is Double) }
        assertEquals(java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(root.get("negativeZero") as Double))
        assertEquals(true, root.get("yes"))
        assertEquals(false, root.get("no"))
        assertSame(org.json.JSONObject.NULL, root.get("nil"))
    }

    @Test fun integerRangeIsExactAndUnrepresentableNumbersFailClosed() {
        val root = StrictJson.requireObject("{\"min\":-9223372036854775808,\"max\":9223372036854775807}")
        assertEquals(Long.MIN_VALUE, root.get("min"))
        assertEquals(Long.MAX_VALUE, root.get("max"))
        rejects("{\"n\":9223372036854775808}", "{\"n\":-9223372036854775809}", "{\"n\":1e309}")
    }
}
