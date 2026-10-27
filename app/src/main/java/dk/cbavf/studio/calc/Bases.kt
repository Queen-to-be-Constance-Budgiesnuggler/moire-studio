package dk.cbavf.studio.calc

/** A way to write a whole number: decimal, or hex, binary and octal with their 0x, 0b and 0o prefixes. */
enum class NumberBase(val label: String, val radix: Int, val prefix: String) {
    Dec("dec", 10, ""), Hex("hex", 16, "0x"), Bin("bin", 2, "0b"), Oct("oct", 8, "0o");

    /** [n] in this base, minus sign first ("-0xff"). */
    fun format(n: Long): String = (if (n < 0) "-" else "") + prefix + java.lang.Long.toString(kotlin.math.abs(n), radix)

    companion object {
        /** Whole numbers are exact in a Double up to 2^53; past that a base conversion would show digits that are not there. */
        const val MAX_EXACT = 9007199254740992.0

        fun byLabel(label: String): NumberBase? = entries.firstOrNull { it.label == label }

        /** [x] as a whole number, or null when it has a fraction or is too big to be exact. */
        fun whole(x: Double): Long? = if (x.isFinite() && x == kotlin.math.floor(x) && kotlin.math.abs(x) <= MAX_EXACT) x.toLong() else null

        /** [x] written in every base, or null when it is not a whole number. */
        fun all(x: Double): List<Pair<NumberBase, String>>? = whole(x)?.let { n -> entries.map { it to it.format(n) } }

        /** Reads digits in [radix] ("ff", "1010", "1_0000"); null when a digit does not belong or it is too long. */
        fun parse(digits: String, radix: Int): Long? {
            val clean = digits.replace("_", "")
            if (clean.isEmpty()) return null
            val n = clean.toLongOrNull(radix) ?: return null
            return n.takeIf { it <= MAX_EXACT.toLong() }
        }
    }
}
