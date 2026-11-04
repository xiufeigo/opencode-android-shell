package com.xiufeigo.opencodeshell.web.host

/**
 * 极简 JSON 解析器（**纯 Kotlin**：不依赖 `android.*`，也不依赖 `org.json`）。
 *
 * ## 为什么自己写一个
 *
 * 更新检查要读的只有 GitHub Release 的那几个字段（`tag_name` / `html_url` /
 * `draft` / `prerelease` / `assets[].name` / `assets[].browser_download_url`），
 * 但**解析这一步必须能在 JVM 单测里跑**，否则"远端 JSON 长这样"这条契约就只能
 * 靠真机点一次更新按钮来验。
 *
 * 而两条现成路都走不通：
 *  - `org.json`：Android 运行时自带，但 `webapp` 模块**没开**
 *    `unitTests.isReturnDefaultValues`（`app` 模块开了，见 `app/build.gradle.kts:65`），
 *    JVM 单测里 `org.json.*` 全是 `not mocked` 空壳，测不了。
 *  - 加 `testImplementation("org.json:json:…")`：能测，但那样**主源码**与
 *    **测试**跑的是两套实现（主源码吃 Android 自带的 `org.json`，测试吃 Maven 那个），
 *    反而容易出现"测试绿、真机炸"的缝。
 *
 * 所以这里给一份**主源码与单测共用**的最小实现：只支持 JSON 规范里
 * object / array / string / number / true / false / null，够用且没有二义性。
 *
 * ⚠️ 解析失败一律抛 [ParseException]，由调用方决定怎么降级（更新检查里就是
 * "静默当作没有更新"）。
 */
object JsonLite {

    /** JSON 语法错误（调用方负责降级，别让它冒到 UI 线程）。 */
    class ParseException(message: String) : RuntimeException(message)

    /**
     * 解析一个 **JSON 对象**。
     *
     * @return 顶层键值表；`null` 值（JSON `null`）保留为 `null`，不丢键。
     * @throws ParseException 语法错误或顶层不是对象。
     */
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw ParseException("顶层之后还有多余内容（offset=${parser.index}）")
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw ParseException("顶层不是 JSON 对象")
    }

    /** 解析器：递归下降 + 一个游标。 */
    private class Parser(private val text: String) {
        var index: Int = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw ParseException("内容意外结束")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                else ->
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw ParseException("意料之外的字符 '$c'（offset=$index）")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw ParseException("对象键必须是字符串（offset=$index）")
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return out }
                    else -> throw ParseException("对象里期望 ',' 或 '}'（offset=$index）")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return out }
                    else -> throw ParseException("数组里期望 ',' 或 ']'（offset=$index）")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("字符串没有闭合")
                when (val c = text[index]) {
                    '"' -> { index++; return sb.toString() }
                    '\\' -> {
                        index++
                        if (atEnd()) throw ParseException("转义符后面没内容")
                        when (val e = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = text.substring(index + 1, minOf(index + 5, text.length))
                                if (hex.length < 4) throw ParseException("\\u 转义不完整")
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw ParseException("未知转义 '\\$e'")
                        }
                        index++
                    }
                    else -> {
                        sb.append(c)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            var isDouble = false
            if (!atEnd() && text[index] == '.') {
                isDouble = true
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                isDouble = true
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (isDouble) {
                raw.toDoubleOrNull() ?: throw ParseException("非法数字 '$raw'")
            } else {
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw ParseException("非法数字 '$raw'")
            }
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) {
                throw ParseException("期望 '$literal'（offset=$index）")
            }
            index += literal.length
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (atEnd() || text[index] != ch) {
                throw ParseException("期望 '$ch'（offset=$index）")
            }
            index++
        }

        private fun peek(): Char =
            if (atEnd()) '\u0000' else text[index]
    }
}
