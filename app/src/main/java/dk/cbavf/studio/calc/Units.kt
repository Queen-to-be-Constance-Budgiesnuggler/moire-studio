package dk.cbavf.studio.calc

/** What a number measures. A plain number is [None]; each other kind has a base unit its values are kept in. */
enum class Dimension(val label: String) {
    None("plain number"), Length("length"), Angle("angle"), Time("time"), Frequency("frequency"),
    Mass("mass"), Temperature("temperature"), Data("data size"),
}

/**
 * A unit: a value v in it is (v + [offset]) × [factor] in its dimension's base unit (m, rad, s, Hz, kg, K, B).
 * Only temperatures have an offset.
 */
data class UnitDef(val name: String, val dim: Dimension, val factor: Double, val offset: Double = 0.0) {
    fun toBase(v: Double): Double = (v + offset) * factor
    fun fromBase(b: Double): Double = b / factor - offset
}

/** The calculator's units, by name and alias. Names are case-sensitive (m is metres, M is nothing). */
object Units {
    private val defs = listOf(
        UnitDef("m", Dimension.Length, 1.0),
        UnitDef("mm", Dimension.Length, 0.001),
        UnitDef("cm", Dimension.Length, 0.01),
        UnitDef("km", Dimension.Length, 1000.0),
        UnitDef("inch", Dimension.Length, 0.0254),
        UnitDef("ft", Dimension.Length, 0.3048),
        UnitDef("yd", Dimension.Length, 0.9144),
        UnitDef("mi", Dimension.Length, 1609.344),
        UnitDef("pt", Dimension.Length, 0.0254 / 72),
        UnitDef("rad", Dimension.Angle, 1.0),
        UnitDef("deg", Dimension.Angle, Math.PI / 180),
        UnitDef("turn", Dimension.Angle, 2 * Math.PI),
        UnitDef("s", Dimension.Time, 1.0),
        UnitDef("ms", Dimension.Time, 0.001),
        UnitDef("min", Dimension.Time, 60.0),
        UnitDef("h", Dimension.Time, 3600.0),
        UnitDef("day", Dimension.Time, 86400.0),
        UnitDef("Hz", Dimension.Frequency, 1.0),
        UnitDef("kHz", Dimension.Frequency, 1000.0),
        UnitDef("bpm", Dimension.Frequency, 1.0 / 60),
        UnitDef("kg", Dimension.Mass, 1.0),
        UnitDef("g", Dimension.Mass, 0.001),
        UnitDef("lb", Dimension.Mass, 0.45359237),
        UnitDef("oz", Dimension.Mass, 0.028349523125),
        UnitDef("K", Dimension.Temperature, 1.0),
        UnitDef("°C", Dimension.Temperature, 1.0, 273.15),
        UnitDef("°F", Dimension.Temperature, 5.0 / 9, 459.67),
        UnitDef("B", Dimension.Data, 1.0),
        UnitDef("bit", Dimension.Data, 0.125),
        UnitDef("KB", Dimension.Data, 1e3),
        UnitDef("MB", Dimension.Data, 1e6),
        UnitDef("GB", Dimension.Data, 1e9),
        UnitDef("KiB", Dimension.Data, 1024.0),
        UnitDef("MiB", Dimension.Data, 1024.0 * 1024),
        UnitDef("GiB", Dimension.Data, 1024.0 * 1024 * 1024),
    )

    private val aliases = mapOf(
        "inches" to "inch", "°" to "deg", "degC" to "°C", "C" to "°C", "degF" to "°F", "F" to "°F",
        "sec" to "s", "hr" to "h", "days" to "day", "turns" to "turn", "bits" to "bit",
    )

    private val byName = defs.associateBy { it.name }

    fun find(name: String): UnitDef? = byName[name] ?: aliases[name]?.let { byName[it] }

    /** The unit a [dim] value is kept in, used to show it when it has no unit of its own. */
    fun base(dim: Dimension): UnitDef? = defs.firstOrNull { it.dim == dim && it.factor == 1.0 && it.offset == 0.0 }

    val all: List<UnitDef> get() = defs
}
