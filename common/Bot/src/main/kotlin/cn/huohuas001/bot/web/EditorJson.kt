package cn.huohuas001.bot.web

import com.google.gson.GsonBuilder
import java.math.BigDecimal

internal object EditorJson {
    private val gson = GsonBuilder().serializeNulls().create()
    fun encode(value: Any?): String = gson.toJson(value)

    /** Strict RFC 8259 parsing: Gson 2.10 accepts mixed-case literals even in strict reader mode. */
    fun objectValue(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.finish()
        require(value is Map<*, *>) { "请求必须是 JSON 对象" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private class Parser(private val text: String) {
        private var position = 0
        private var nodes = 0

        fun value(depth: Int): Any? {
            require(depth <= 32 && ++nodes <= 20_000) { "JSON 结构过大或嵌套过深" }
            whitespace()
            require(position < text.length) { "JSON 值不完整" }
            return when (text[position]) {
                '{' -> {
                    position++
                    val result = LinkedHashMap<String, Any?>()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val key = string()
                            require(!result.containsKey(key)) { "JSON 键不能重复" }
                            require(key != "@type" && key != "__proto__" && key != "constructor" && key != "prototype") {
                                "JSON 键不允许用于类型或原型定义"
                            }
                            require(take(':')) { "JSON 键后缺少冒号" }
                            result[key] = value(depth + 1)
                        } while (take(','))
                        require(take('}')) { "JSON 对象未结束" }
                    }
                    result
                }
                '[' -> {
                    position++
                    val result = ArrayList<Any?>()
                    if (!take(']')) {
                        do { result.add(value(depth + 1)) } while (take(','))
                        require(take(']')) { "JSON 数组未结束" }
                    }
                    result
                }
                '"' -> string()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); null }
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("无效 JSON 值")
            }
        }

        fun finish() { whitespace(); require(position == text.length) { "JSON 文档包含多余内容" } }

        private fun whitespace() {
            while (position < text.length && (text[position] == ' ' || text[position] == '\t' || text[position] == '\r' || text[position] == '\n')) position++
        }

        private fun take(character: Char): Boolean {
            whitespace()
            if (position >= text.length || text[position] != character) return false
            position++
            return true
        }

        private fun literal(literal: String) {
            require(text.regionMatches(position, literal, 0, literal.length)) { "无效 JSON 字面量" }
            position += literal.length
        }

        private fun string(): String {
            require(position < text.length && text[position++] == '"') { "JSON 字符串必须使用双引号" }
            val result = StringBuilder()
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return result.toString()
                require(character.code >= 32) { "JSON 字符串不能包含未转义控制字符" }
                if (character != '\\') { result.append(character); continue }
                require(position < text.length) { "JSON 转义不完整" }
                result.append(when (val escape = text[position++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(position + 4 <= text.length) { "JSON Unicode 转义不完整" }
                        var code = 0
                        repeat(4) {
                            val digit = Character.digit(text[position++], 16)
                            require(digit >= 0) { "无效 JSON Unicode 转义" }
                            code = code * 16 + digit
                        }
                        code.toChar()
                    }
                    else -> throw IllegalArgumentException("无效 JSON 转义")
                })
            }
            throw IllegalArgumentException("JSON 字符串未结束")
        }

        private fun number(): BigDecimal {
            val start = position
            if (text[position] == '-') position++
            require(position < text.length) { "JSON 数值不完整" }
            if (text[position] == '0') position++ else {
                require(text[position] in '1'..'9') { "无效 JSON 整数" }
                digits()
            }
            if (position < text.length && text[position] == '.') { position++; digits() }
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                digits()
            }
            require(position - start <= 256) { "JSON 数值过长" }
            val number = BigDecimal(text.substring(start, position))
            require(number.precision() <= 128 && number.scale() in -128..128) { "数值超出范围" }
            return number
        }

        private fun digits() {
            val start = position
            while (position < text.length && text[position] in '0'..'9') position++
            require(position > start) { "JSON 数值缺少数字" }
        }
    }
}
