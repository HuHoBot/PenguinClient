package cn.huohuas001.bot.web

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class EditorJsonTest {
    @Test fun nestedConfigPreservesTypesAndLiteralUserText() {
        val values = EditorJson.objectValue("""{"enabled":true,"port":5678,"commands":[{"key":"<script>alert(1)</script>"}],"unset":null}""")
        assertEquals(true, values["enabled"])
        assertEquals(BigDecimal(5678), values["port"])
        assertNull(values["unset"])
        assertEquals(values, EditorJson.objectValue(EditorJson.encode(values)))
    }

    @Test fun malformedOrLenientJsonIsRejectedInsteadOfApplied() {
        listOf(
            "", "[]", "null", "{'enabled':true}", "{enabled:true}",
            "{\"enabled\":true,}", "{\"items\":[1,]}", "{\"enabled\":True}",
            "{\"number\":NaN}", "{\"number\":01}", "{\"number\":1e9999}",
            "{\"number\":1e-9999}", "{\"key\":1,\"key\":2}", "{\"key\":null,\"key\":2}",
            "{\"@type\":\"java.lang.Runtime\"}", "{\"__proto__\":{\"x\":1}}",
            "{\"enabled\":true}{}", "{\"enabled\":true}//comment", "{\"enabled\":", "/* comment */{}"
        ).forEach { input -> assertFails("Accepted malformed JSON: $input") { EditorJson.objectValue(input) } }
    }

    @Test fun nestingIsBoundedBeforeConfigurationTraversal() {
        val input = "{\"value\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        assertFails { EditorJson.objectValue(input) }
    }
}
