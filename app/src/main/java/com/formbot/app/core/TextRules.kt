package com.formbot.app.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Нормализация текста, сопоставление вариантов, ответы «по правилам» без ИИ. */
object TextRules {

    private val LOOKALIKES = mapOf(
        '’' to '\'', '‘' to '\'', '`' to '\'',
        'с' to 'c', 'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p',
        'х' to 'x', 'к' to 'k', 'м' to 'm', 'т' to 't',
    )
    private val INVISIBLE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]")
    private val SPACES = Regex("\\s+")

    /** Как norm() в настольной версии: регистр, невидимые символы, похожие буквы. */
    fun norm(text: String?): String {
        var t = Normalizer.normalize(text ?: "", Normalizer.Form.NFKC).lowercase()
        t = t.replace(' ', ' ').replace(INVISIBLE, "")
        t = buildString(t.length) { for (ch in t) append(LOOKALIKES[ch] ?: ch) }
        return t.replace(SPACES, " ").trim()
    }

    /** Точное совпадение; затем без пробелов; нечёткое — только если результат единственный. */
    fun matchLabelIndex(labels: List<String>, wanted: String?): Int {
        val target = norm(wanted)
        if (target.isEmpty()) return -1
        val normed = labels.map { norm(it) }
        val exact = normed.indices.filter { normed[it] == target }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) return -1
        val squeezed = target.replace(" ", "")
        val loose = normed.indices.filter { normed[it].replace(" ", "") == squeezed }
        if (loose.size == 1) return loose[0]
        if (loose.size > 1) return -1
        if (target.length >= 3) {
            val partial = normed.indices.filter {
                val lab = normed[it]
                lab.isNotEmpty() && (target in lab || (lab.length >= 3 && lab in target))
            }
            if (partial.size == 1) return partial[0]
        }
        return -1
    }

    fun fragmentMatches(fragment: String, questionLower: String): Boolean {
        val f = fragment.trim()
        if (f.isEmpty()) return false
        if (f.lowercase().startsWith("re:")) {
            return runCatching {
                Regex(f.substring(3), RegexOption.IGNORE_CASE).containsMatchIn(questionLower)
            }.getOrDefault(false)
        }
        return f.lowercase() in questionLower
    }

    // ---------- инструкции «Напиши "слово"» ----------

    private val INSTR_QUOTED = Regex(
        "(?:напиши(?:те)?|введи(?:те)?|впиши(?:те)?|пропиши(?:те)?|ответь(?:те)?|набери(?:те)?)" +
            "\\s*[:,\\-–—]?\\s*(?:пожалуйста\\s*,?\\s*)?(?:слово|фразу|текст|ответ|сообщение)?\\s*[:,\\-–—]?\\s*" +
            "[«\"“„']([^«»\"“”„']{1,200})[»\"”“']",
        RegexOption.IGNORE_CASE,
    )

    fun answerByInstruction(question: String): String? =
        INSTR_QUOTED.find(question)?.groupValues?.get(1)?.trim()

    // ---------- день недели ----------

    val WEEKDAYS = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    fun weekdayToday(): String = WEEKDAYS[LocalDate.now().dayOfWeek.value - 1]

    fun monthName(date: LocalDate): String =
        date.month.getDisplayName(TextStyle.FULL, Locale("ru"))

    // ---------- сложность вопроса и отговорки ИИ ----------

    private val HARD_RE = Regex(
        "предел|\\blim\\b|производн|интеграл|дифференц|уравнени|неравенств|логарифм|" +
            "\\blog|\\bln\\b|\\bsin|\\bcos|\\btg\\b|\\bctg\\b|синус|косинус|тангенс|корень|корн[яеи]|√|" +
            "вероятност|комбинатор|перестановк|сочетани|размещени|сколькими способами|" +
            "матриц|определител|вектор|прогресси|последовательност|бесконечн|сходит|" +
            "функци|график|степен|\\^|\\bmod\\b|остаток от делени|делится|простое число|" +
            "докаж|почему|объясни|загадк|логическ|задач|если .+ то ",
        RegexOption.IGNORE_CASE,
    )
    private val UNSURE_START = Regex(
        "^(?:зависит|нужн[аоы]?(?![\\p{L}])|необходим|недостаточно|уточни|не могу|невозможно|" +
            "нельзя (?:определить|сказать|ответить)|неизвестно|не знаю|не указан|нет данных|" +
            "вопрос (?:некорректен|неполный)|it depends|need more|cannot)",
        RegexOption.IGNORE_CASE,
    )
    private val UNSURE_ANYWHERE = Regex(
        "уточнени|уточните|уточнить|не хватает|недостаточно (?:данных|информации)|" +
            "не указан[аоы]? (?:сам[аоы]?|выражени|функци|услови)|без (?:выражения|условия|контекста)|" +
            "хотел(?:а)? бы|не могу (?:ответить|определить)|please (?:clarify|specify)",
        RegexOption.IGNORE_CASE,
    )

    fun isHardQuestion(q: String): Boolean = q.length > 160 || HARD_RE.containsMatchIn(q)

    fun looksUnsure(answer: String?): Boolean {
        val a = answer?.trim().orEmpty()
        return a.isEmpty() || a.endsWith("?") ||
            UNSURE_START.containsMatchIn(a) || UNSURE_ANYWHERE.containsMatchIn(a)
    }

    // ---------- сравнение ответов при голосовании ----------

    private val WORD_NUMBERS = linkedMapOf(
        "ноль" to "0", "нуль" to "0", "один" to "1", "одна" to "1",
        "два" to "2", "две" to "2", "три" to "3", "четыре" to "4",
        "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8",
        "девять" to "9", "десять" to "10",
    )
    private val INF_RE = Regex("[+]?∞|плюс бесконечност\\p{L}*|бесконечност\\p{L}*|infinity|(?<![\\p{L}])inf(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private fun wordBoundary(word: String) = Regex("(?<![\\p{L}\\d])$word(?![\\p{L}\\d])")

    /** «3x²» = «3x^2», «∞» = «бесконечность», «восемь» = «8». */
    fun answerKey(answer: String?): String {
        var t = answer.orEmpty().trim().lowercase()
        t = INF_RE.replace(t, "inf")
        for ((w, d) in WORD_NUMBERS) t = wordBoundary(w).replace(t, d)
        t = norm(t)
        t = Regex("(?<=\\d),(?=\\d)").replace(t, ".")
        t = Regex("[\\s^*·×.,;:!?\"'«»()\\[\\]{}]+$").replace(t, "")
        t = Regex("[\\s^*·\"'«»]+").replace(t, "")
        val number = t.toDoubleOrNull()
        return if (number != null) formatNumber(number) else t
    }

    fun formatNumber(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')

    // ---------- арифметика без ИИ ----------

    private val WORD_OPS = listOf(
        Regex("умножить на|умноженное на|помножить на") to "*",
        Regex("разделить на|поделить на|делить на") to "/",
        Regex("плюс") to "+",
        Regex("минус") to "-",
    )
    private val MATH_TRIGGER = Regex(
        "сколько будет|сколько получится|(?<![\\p{L}])реши(?:те)?(?![\\p{L}])|вычисли(?:те)?|" +
            "посчитай(?:те)?|(?<![\\p{L}])пример(?![\\p{L}])|чему равн"
    )

    /** Явные примеры: «Сколько будет 7 умножить на 8?» → 56. «Сезон 2024-2025» не трогает. */
    fun solveMath(question: String): String? {
        var t = question.trim().lowercase().replace(Regex("\\s*\\*\\s*$"), "").replace(",", ".")
        var hadWordOp = false
        for ((re, sym) in WORD_OPS) {
            if (re.containsMatchIn(t)) hadWordOp = true
            t = re.replace(t, " $sym ")
        }
        for ((w, d) in WORD_NUMBERS) t = wordBoundary(w).replace(t, d)
        t = t.replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
        t = Regex("(?<=\\d)\\s*[xх]\\s*(?=\\d)").replace(t, "*")
        t = Regex("(?<=\\d)\\s*:\\s*(?=\\d)").replace(t, "/")
        t = Regex("\\(\\s*\\d+\\s*-\\s*\\d+\\s*\\)").replace(t, " ")
        val pure = Regex("[\\d.\\s+\\-*/()=?]+").matches(t)
        if (!(pure || hadWordOp || '=' in t || MATH_TRIGGER.containsMatchIn(t))) return null

        val candidates = Regex("[\\d.\\s+\\-*/()]+").findAll(t)
            .map { it.value.trim(' ', '+', '-', '*', '/', '.') }
            .filter { Regex("\\d").containsMatchIn(it) && Regex("\\d\\s*[+\\-*/]\\s*[\\d(]").containsMatchIn(it) }
            .sortedByDescending { it.length }
        for (expr in candidates) {
            val v = runCatching { Expr(expr).parse() }.getOrNull() ?: continue
            if (v.isNaN() || v.isInfinite()) continue
            return formatNumber(v)
        }
        return null
    }

    /** Маленький разборщик + - * / ^ и скобок (вместо eval). */
    private class Expr(private val s: String) {
        private var i = 0
        fun parse(): Double {
            val v = sum()
            skip()
            require(i == s.length) { "мусор в выражении" }
            return v
        }
        private fun skip() { while (i < s.length && s[i] == ' ') i++ }
        private fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '+' -> { i++; v += product() }
                    '-' -> { i++; v -= product() }
                    else -> return v
                }
            }
        }
        private fun product(): Double {
            var v = power()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '*' -> {
                        if (i + 1 < s.length && s[i + 1] == '*') { i += 2; v = pow(v, unary()) }
                        else { i++; v *= power() }
                    }
                    '/' -> { i++; v /= power() }
                    else -> return v
                }
            }
        }
        private fun power(): Double {
            val base = unary()
            skip()
            if (i < s.length && s[i] == '^') { i++; return pow(base, power()) }
            return base
        }
        private fun pow(a: Double, b: Double): Double {
            require(kotlin.math.abs(b) <= 10) { "слишком большая степень" }
            return Math.pow(a, b)
        }
        private fun unary(): Double {
            skip()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }
        private fun atom(): Double {
            skip()
            require(i < s.length) { "неожиданный конец" }
            if (s[i] == '(') {
                i++
                val v = sum()
                skip()
                require(i < s.length && s[i] == ')') { "нет закрывающей скобки" }
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "ожидалось число" }
            return s.substring(start, i).toDouble()
        }
    }
}
