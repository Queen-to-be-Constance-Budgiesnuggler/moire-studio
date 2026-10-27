package dk.cbavf.studio.calc

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * A number with what it measures. [value] is in the dimension's base unit (m, rad, s, Hz, kg, K, B); [unit] is the unit it is
 * shown in when nothing else is asked for (the one it was written with).
 */
data class Quantity(val value: Double, val dim: Dimension = Dimension.None, val unit: UnitDef? = null)

/** Something the calculator cannot read or work out; the message is shown as it is. */
class CalcError(message: String) : Exception(message)

/** What a line asks for its result to be shown in: a unit ("in km") or a base ("in hex"). */
sealed interface Target {
    data class InUnit(val unit: UnitDef) : Target
    data class InBase(val base: NumberBase) : Target
}

/** A line read by the parser: an optional "name =", the expression, and an optional "in …". */
data class Statement(val assign: String?, val expr: Node, val target: Target?)

/** The expression tree. */
sealed interface Node {
    data class Num(val v: Double) : Node
    data class Name(val name: String) : Node
    data class Neg(val x: Node) : Node
    data class Bin(val op: String, val a: Node, val b: Node) : Node
    data class Call(val name: String, val args: List<Node>) : Node
    data class WithUnit(val x: Node, val unit: UnitDef) : Node
}

/** A worked-out line: the [text] to show, the shown [number] (in the asked-for unit) and the variable it set, if any. */
data class CalcResult(val quantity: Quantity, val text: String, val number: Double, val assigned: String?)

sealed interface CalcOutcome {
    data class Ok(val result: CalcResult) : CalcOutcome
    data class Err(val message: String) : CalcOutcome
}

/**
 * The calculator: reads a line like `r = 2.5 cm * 3 in inch`, `0xff & 0b1010 in bin` or `sin(30 deg)`, and works it out.
 *
 * Operators, loosest first: `|`, `&`, `<<` `>>`, `+` `-`, `*` `/` `%`, a unit after a number (`5 km`), unary minus, `^`
 * (right to left). So `-40 °C` is minus forty degrees and `-2^2` is −4. Bitwise operators take whole numbers.
 */
object Calc {
    val CONSTANTS = mapOf("pi" to Math.PI, "π" to Math.PI, "tau" to 2 * Math.PI, "e" to Math.E)

    val FUNCTIONS = listOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "cbrt", "exp", "ln", "log", "log2",
        "abs", "floor", "ceil", "round", "min", "max", "pow", "hypot",
    )

    /** Names a line may not set. */
    private val RESERVED = CONSTANTS.keys + FUNCTIONS + setOf("ans", "in", "to") + NumberBase.entries.map { it.label }

    /** Reads and works out [input] with the variables [vars] (`ans` among them, if set). */
    fun run(input: String, vars: Map<String, Quantity> = emptyMap()): CalcOutcome = try {
        CalcOutcome.Ok(evaluate(parse(input), vars))
    } catch (e: CalcError) {
        CalcOutcome.Err(e.message ?: "Can't work that out")
    }

    fun parse(input: String): Statement = Parser(Lexer(input).tokens()).statement()

    fun evaluate(s: Statement, vars: Map<String, Quantity> = emptyMap()): CalcResult {
        if (s.assign != null && s.assign in RESERVED) throw CalcError("${s.assign} is a built-in name: pick another")
        val q = Evaluator(vars).eval(s.expr)
        if (q.value.isNaN()) throw CalcError("Not a real number")
        if (q.value.isInfinite()) throw CalcError("The result is too big")
        val (text, number) = show(q, s.target)
        return CalcResult(q, if (s.assign != null) "${s.assign} = $text" else text, number, s.assign)
    }

    /** [q] as text and as the number shown, in [target] or, with none, in its own unit. */
    fun show(q: Quantity, target: Target?): Pair<String, Double> = when (target) {
        is Target.InBase -> {
            if (q.dim != Dimension.None) throw CalcError("Only plain numbers can be shown in ${target.base.label}")
            val n = NumberBase.whole(q.value) ?: throw CalcError("Only whole numbers can be shown in ${target.base.label}")
            target.base.format(n) to q.value
        }
        is Target.InUnit -> {
            val u = target.unit
            if (q.dim == Dimension.None) throw CalcError("A plain number has no unit to convert: write one, like 5 km in mi")
            if (q.dim != u.dim) throw CalcError("Can't turn a ${q.dim.label} into ${u.name} (a ${u.dim.label})")
            val v = u.fromBase(q.value)
            "${format(v)} ${u.name}" to v
        }
        null -> {
            val u = q.unit ?: Units.base(q.dim)
            if (q.dim == Dimension.None || u == null) format(q.value) to q.value
            else {
                val v = u.fromBase(q.value)
                "${format(v)} ${u.name}" to v
            }
        }
    }

    /** A number for people: up to 12 significant digits, no trailing zeros, powers of ten only for very big or small ones. */
    fun format(x: Double): String {
        if (x.isNaN()) return "not a number"
        if (x.isInfinite()) return if (x > 0) "∞" else "-∞"
        if (x == 0.0) return "0"
        val a = abs(x)
        // Whole numbers keep every digit (a 15-digit id or a big hex value must not be rounded).
        if (a < 1e15 && x == Math.floor(x)) return x.toLong().toString()
        val rounded = BigDecimal(x).round(MathContext(12)).stripTrailingZeros()
        if (a >= 1e15 || a < 1e-9) {
            val s = String.format(java.util.Locale.US, "%.11e", rounded.toDouble())
            val mantissa = s.substringBefore('e').trimEnd('0').trimEnd('.')
            val exp = s.substringAfter('e').toInt()
            return "${mantissa}e$exp"
        }
        return rounded.toPlainString()
    }
}

// --- Lexer -----------------------------------------------------------------------------------

internal sealed interface Token {
    data class Num(val v: Double) : Token
    data class Ident(val name: String) : Token
    data class Op(val s: String) : Token
    data object End : Token
}

internal class Lexer(private val src: String) {
    private var i = 0

    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val t = next()
            out += t
            if (t == Token.End) return out
        }
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '°' || c == 'π'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun next(): Token {
        while (i < src.length && src[i].isWhitespace()) i++
        if (i >= src.length) return Token.End
        val c = src[i]
        return when {
            c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> number()
            isIdentStart(c) -> {
                val start = i
                i++
                // π stands alone, so "2π" style names don't swallow what follows.
                if (c != 'π') while (i < src.length && isIdentPart(src[i])) i++
                Token.Ident(src.substring(start, i))
            }
            src.startsWith("<<", i) -> { i += 2; Token.Op("<<") }
            src.startsWith(">>", i) -> { i += 2; Token.Op(">>") }
            c == '×' || c == '·' -> { i++; Token.Op("*") }
            c == '÷' -> { i++; Token.Op("/") }
            c == '−' -> { i++; Token.Op("-") }
            c == '*' && src.startsWith("**", i) -> { i += 2; Token.Op("^") }
            c in "+-*/%^(),=&|" -> { i++; Token.Op(c.toString()) }
            else -> throw CalcError("Can't read \"$c\"")
        }
    }

    private fun number(): Token {
        val start = i
        if (src[i] == '0' && i + 1 < src.length && src[i + 1].lowercaseChar() in "xbo") {
            val radix = when (src[i + 1].lowercaseChar()) { 'x' -> 16; 'b' -> 2; else -> 8 }
            i += 2
            val digitsStart = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            val digits = src.substring(digitsStart, i)
            val n = NumberBase.parse(digits, radix) ?: throw CalcError("\"${src.substring(start, i)}\" is not a whole number in base $radix")
            return Token.Num(n.toDouble())
        }
        while (i < src.length && (src[i].isDigit() || src[i] == '_')) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && (src[i].isDigit() || src[i] == '_')) i++
        }
        // An exponent only when digits follow: "2e3" is 2000, "2e" is 2 and the name e.
        if (i < src.length && src[i].lowercaseChar() == 'e') {
            var j = i + 1
            if (j < src.length && src[j] in "+-") j++
            if (j < src.length && src[j].isDigit()) {
                i = j
                while (i < src.length && src[i].isDigit()) i++
            }
        }
        val text = src.substring(start, i).replace("_", "")
        return Token.Num(text.toDoubleOrNull() ?: throw CalcError("\"$text\" is not a number"))
    }
}

// --- Parser ----------------------------------------------------------------------------------

internal class Parser(private val tokens: List<Token>) {
    private var p = 0

    private fun peek(k: Int = 0): Token = tokens[minOf(p + k, tokens.size - 1)]
    private fun take(): Token = tokens[p].also { if (p < tokens.size - 1) p++ }
    private fun isOp(s: String, k: Int = 0) = (peek(k) as? Token.Op)?.s == s
    private fun isWord(w: String, k: Int = 0) = (peek(k) as? Token.Ident)?.name == w

    private fun expect(s: String) {
        if (!isOp(s)) throw CalcError("Expected \"$s\"" + describe(peek()))
        take()
    }

    private fun describe(t: Token): String = when (t) {
        Token.End -> " at the end"
        is Token.Num -> " before ${Calc.format(t.v)}"
        is Token.Ident -> " before ${t.name}"
        is Token.Op -> " before \"${t.s}\""
    }

    fun statement(): Statement {
        if (peek() == Token.End) throw CalcError("Type a sum, like 2 + 2")
        val assign = (peek() as? Token.Ident)?.name?.takeIf { isOp("=", 1) }
        if (assign != null) { take(); take() }
        val expr = expr()
        var target: Target? = null
        if (isWord("in") || isWord("to")) {
            take()
            val t = take() as? Token.Ident ?: throw CalcError("Say what to show it in, like \"in hex\" or \"in cm\"")
            target = NumberBase.byLabel(t.name)?.let { Target.InBase(it) }
                ?: Units.find(t.name)?.let { Target.InUnit(it) }
                ?: throw CalcError("Unknown unit: ${t.name}")
        }
        if (peek() != Token.End) {
            val t = peek()
            throw CalcError(if (t is Token.Op && t.s == ")") "One \")\" too many" else "Unexpected " + describe(t).removePrefix(" before "))
        }
        return Statement(assign, expr, target)
    }

    private fun expr(): Node = bitOr()

    private fun bitOr(): Node {
        var a = bitAnd()
        while (isOp("|")) { take(); a = Node.Bin("|", a, bitAnd()) }
        return a
    }

    private fun bitAnd(): Node {
        var a = shift()
        while (isOp("&")) { take(); a = Node.Bin("&", a, shift()) }
        return a
    }

    private fun shift(): Node {
        var a = additive()
        while (isOp("<<") || isOp(">>")) { val op = (take() as Token.Op).s; a = Node.Bin(op, a, additive()) }
        return a
    }

    private fun additive(): Node {
        var a = term()
        while (isOp("+") || isOp("-")) { val op = (take() as Token.Op).s; a = Node.Bin(op, a, term()) }
        return a
    }

    private fun term(): Node {
        var a = withUnit()
        while (isOp("*") || isOp("/") || isOp("%")) { val op = (take() as Token.Op).s; a = Node.Bin(op, a, withUnit()) }
        return a
    }

    /** A unit straight after a value: `5 km`, `(1 + 2) cm`, `-40 °C`. */
    private fun withUnit(): Node {
        val x = unary()
        val t = peek() as? Token.Ident ?: return x
        if (t.name == "in" || t.name == "to") return x
        val unit = Units.find(t.name) ?: throw CalcError("Unknown unit: ${t.name}. Use * to multiply.")
        take()
        return Node.WithUnit(x, unit)
    }

    private fun unary(): Node = when {
        isOp("-") -> { take(); Node.Neg(unary()) }
        isOp("+") -> { take(); unary() }
        else -> power()
    }

    private fun power(): Node {
        val base = primary()
        if (!isOp("^")) return base
        take()
        return Node.Bin("^", base, unary())
    }

    private fun primary(): Node = when (val t = take()) {
        is Token.Num -> Node.Num(t.v)
        is Token.Ident -> if (isOp("(")) {
            take()
            val args = ArrayList<Node>()
            if (!isOp(")")) {
                args += expr()
                while (isOp(",")) { take(); args += expr() }
            }
            expect(")")
            Node.Call(t.name, args)
        } else Node.Name(t.name)
        is Token.Op -> when (t.s) {
            "(" -> expr().also { expect(")") }
            else -> throw CalcError("Unexpected \"${t.s}\"")
        }
        Token.End -> throw CalcError("The sum stops too soon")
    }
}

// --- Evaluator -------------------------------------------------------------------------------

internal class Evaluator(private val vars: Map<String, Quantity>) {
    private val none = Dimension.None

    fun eval(n: Node): Quantity = when (n) {
        is Node.Num -> Quantity(n.v)
        is Node.Name -> vars[n.name] ?: Calc.CONSTANTS[n.name]?.let { Quantity(it) }
            ?: throw CalcError(if (n.name == "ans") "No answer yet: ans is the last result" else "Unknown name: ${n.name}")
        is Node.Neg -> eval(n.x).let { noTemperature(it, "-"); it.copy(value = -it.value) }
        is Node.WithUnit -> {
            val q = eval(n.x)
            if (q.dim != none) throw CalcError("That already has a unit (${q.unit?.name ?: q.dim.label})")
            Quantity(n.unit.toBase(q.value), n.unit.dim, n.unit)
        }
        is Node.Bin -> binary(n.op, eval(n.a), eval(n.b))
        is Node.Call -> call(n.name, n.args.map { eval(it) })
    }

    private fun noTemperature(q: Quantity, op: String) {
        if (q.dim == Dimension.Temperature) throw CalcError("Temperatures can only be converted (\"in °F\"), not used with $op")
    }

    private fun binary(op: String, a: Quantity, b: Quantity): Quantity {
        noTemperature(a, op)
        noTemperature(b, op)
        return when (op) {
            "+", "-" -> {
                if (a.dim != b.dim) throw CalcError("Can't ${if (op == "+") "add" else "subtract"} a ${b.dim.label} ${if (op == "+") "to" else "from"} a ${a.dim.label}")
                Quantity(if (op == "+") a.value + b.value else a.value - b.value, a.dim, a.unit ?: b.unit)
            }
            "*" -> when {
                a.dim == none -> Quantity(a.value * b.value, b.dim, b.unit)
                b.dim == none -> Quantity(a.value * b.value, a.dim, a.unit)
                else -> throw CalcError("Can't multiply a ${a.dim.label} by a ${b.dim.label}")
            }
            "/" -> {
                if (b.value == 0.0) throw CalcError("Division by zero")
                val v = a.value / b.value
                when {
                    b.dim == none -> Quantity(v, a.dim, a.unit)
                    a.dim == b.dim -> Quantity(v)
                    a.dim == none && b.dim == Dimension.Time -> Quantity(v, Dimension.Frequency)
                    a.dim == none && b.dim == Dimension.Frequency -> Quantity(v, Dimension.Time)
                    else -> throw CalcError("Can't divide a ${a.dim.label} by a ${b.dim.label}")
                }
            }
            "%" -> {
                if (b.dim != none && b.dim != a.dim) throw CalcError("Can't take a ${a.dim.label} modulo a ${b.dim.label}")
                if (b.value == 0.0) throw CalcError("Division by zero")
                Quantity(a.value.mod(b.value), a.dim, a.unit)
            }
            "^" -> {
                plain(a, "^")
                plain(b, "^")
                Quantity(Math.pow(a.value, b.value))
            }
            "&", "|", "<<", ">>" -> {
                val x = whole(a, op)
                val y = whole(b, op)
                Quantity(
                    when (op) {
                        "&" -> x and y
                        "|" -> x or y
                        "<<" -> { if (y !in 0L..62L) throw CalcError("Shift by 0 to 62 places"); x shl y.toInt() }
                        else -> { if (y !in 0L..62L) throw CalcError("Shift by 0 to 62 places"); x shr y.toInt() }
                    }.toDouble(),
                )
            }
            else -> throw CalcError("Unknown operator $op")
        }
    }

    private fun plain(q: Quantity, what: String) {
        if (q.dim != none) throw CalcError("$what takes plain numbers, not a ${q.dim.label}")
    }

    private fun whole(q: Quantity, op: String): Long {
        plain(q, op)
        return NumberBase.whole(q.value) ?: throw CalcError("$op takes whole numbers")
    }

    private fun call(name: String, args: List<Quantity>): Quantity {
        fun arity(n: Int) { if (args.size != n) throw CalcError("$name takes ${if (n == 1) "one number" else "$n numbers"}") }
        fun one(): Double { arity(1); plain(args[0], name); return args[0].value }
        args.forEach { noTemperature(it, name) }
        val angle = Units.find("rad")
        return when (name) {
            "sin", "cos", "tan" -> {
                arity(1)
                val x = args[0]
                if (x.dim != none && x.dim != Dimension.Angle) throw CalcError("$name takes an angle or a plain number")
                Quantity(when (name) { "sin" -> Math.sin(x.value); "cos" -> Math.cos(x.value); else -> Math.tan(x.value) })
            }
            "asin" -> Quantity(Math.asin(one()), Dimension.Angle, angle)
            "acos" -> Quantity(Math.acos(one()), Dimension.Angle, angle)
            "atan" -> Quantity(Math.atan(one()), Dimension.Angle, angle)
            "atan2" -> {
                arity(2)
                if (args[0].dim != args[1].dim) throw CalcError("atan2 takes two values of the same kind")
                Quantity(Math.atan2(args[0].value, args[1].value), Dimension.Angle, angle)
            }
            "sqrt" -> Quantity(Math.sqrt(one()))
            "cbrt" -> Quantity(Math.cbrt(one()))
            "exp" -> Quantity(Math.exp(one()))
            "ln" -> Quantity(Math.log(one()))
            "log" -> when (args.size) {
                1 -> Quantity(Math.log10(one()))
                2 -> { plain(args[0], name); plain(args[1], name); Quantity(Math.log(args[0].value) / Math.log(args[1].value)) }
                else -> throw CalcError("log takes a number, and a base if you like: log(8, 2)")
            }
            "log2" -> Quantity(Math.log(one()) / Math.log(2.0))
            "abs", "floor", "ceil", "round" -> {
                arity(1)
                val x = args[0]
                // Rounding works on the number as it is shown, so 2.6 km rounds to 3 km, not to whole metres.
                val u = x.unit ?: Units.base(x.dim)
                val shown = u?.fromBase(x.value) ?: x.value
                val r = when (name) { "abs" -> abs(shown); "floor" -> Math.floor(shown); "ceil" -> Math.ceil(shown); else -> Math.floor(shown + 0.5) }
                x.copy(value = u?.toBase(r) ?: r)
            }
            "min", "max" -> {
                if (args.isEmpty()) throw CalcError("$name takes one or more numbers")
                if (args.any { it.dim != args[0].dim }) throw CalcError("$name takes values of the same kind")
                if (name == "min") args.minBy { it.value } else args.maxBy { it.value }
            }
            "pow" -> { arity(2); plain(args[0], name); plain(args[1], name); Quantity(Math.pow(args[0].value, args[1].value)) }
            "hypot" -> {
                arity(2)
                if (args[0].dim != args[1].dim) throw CalcError("hypot takes two values of the same kind")
                Quantity(Math.hypot(args[0].value, args[1].value), args[0].dim, args[0].unit ?: args[1].unit)
            }
            else -> throw CalcError("Unknown function: $name")
        }
    }
}
