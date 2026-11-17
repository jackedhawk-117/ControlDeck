package com.phonecontroller.app.profile

import com.phonecontroller.app.input.GyroActivationMode
import com.phonecontroller.app.input.GyroCellAction
import com.phonecontroller.app.input.GyroConfig
import com.phonecontroller.app.input.GyroSensorSource
import com.phonecontroller.app.input.TouchpadConfig
import java.util.UUID

/**
 * Self-contained, portable JSON serializer and parser for [GameProfile].
 *
 * Implements a lightweight JSON AST and recursive-descent parser so profile
 * persistence and import/export work identically on Android and in JVM unit tests
 * without external JSON dependencies or Android stub issues.
 */
object ProfileJson {

    const val CURRENT_SCHEMA_VERSION = 1

    /** Import limits: keep malformed or hostile files from bloating state. */
    private const val MAX_CELLS = 128
    private const val MAX_NAME_LEN = 64
    private const val MAX_DESC_LEN = 256
    private const val MAX_ID_LEN = 128
    private const val MAX_LABEL_LEN = 48
    private const val MAX_KEYS_PER_CELL = 16
    private const val MIN_CELL_DP = 24
    private const val MAX_CELL_DP = 320
    private const val MAX_EVDEV_CODE = 0x2FF

    sealed class JsonValue {
        data class ObjectVal(val fields: Map<String, JsonValue>) : JsonValue()
        data class ArrayVal(val items: List<JsonValue>) : JsonValue()
        data class StringVal(val value: String) : JsonValue()
        data class NumberVal(val value: Double) : JsonValue()
        data class BooleanVal(val value: Boolean) : JsonValue()
        object NullVal : JsonValue()

        fun asString(): String? = (this as? StringVal)?.value
        fun asInt(default: Int = 0): Int = (this as? NumberVal)?.value?.toInt() ?: default
        fun asFloat(default: Float = 0f): Float = (this as? NumberVal)?.value?.toFloat() ?: default
        fun asBoolean(default: Boolean = false): Boolean = (this as? BooleanVal)?.value ?: default
        fun asObject(): Map<String, JsonValue>? = (this as? ObjectVal)?.fields
        fun asArray(): List<JsonValue>? = (this as? ArrayVal)?.items
    }

    // -------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------

    fun serialize(profile: GameProfile): String {
        val sb = StringBuilder()
        writeProfile(sb, profile, indent = 0)
        return sb.toString()
    }

    fun serializeList(profiles: List<GameProfile>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        profiles.forEachIndexed { index, profile ->
            writeProfile(sb, profile, indent = 2)
            if (index < profiles.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun writeProfile(sb: StringBuilder, p: GameProfile, indent: Int) {
        val pad = " ".repeat(indent)
        val pad2 = " ".repeat(indent + 2)
        val pad4 = " ".repeat(indent + 4)

        sb.append(pad).append("{\n")
        sb.append(pad2).append("\"schemaVersion\": ").append(p.schemaVersion).append(",\n")
        sb.append(pad2).append("\"id\": \"").append(escape(p.id)).append("\",\n")
        sb.append(pad2).append("\"name\": \"").append(escape(p.name)).append("\",\n")
        sb.append(pad2).append("\"description\": \"").append(escape(p.description)).append("\",\n")
        sb.append(pad2).append("\"rows\": ").append(p.rows).append(",\n")
        sb.append(pad2).append("\"cols\": ").append(p.cols).append(",\n")
        sb.append(pad2).append("\"buttonSpacingDp\": ").append(p.buttonSpacingDp).append(",\n")
        sb.append(pad2).append("\"cornerRadiusDp\": ").append(p.cornerRadiusDp).append(",\n")
        sb.append(pad2).append("\"opacity\": ").append(p.opacity).append(",\n")

        // Touchpad
        sb.append(pad2).append("\"touchpad\": {\n")
        sb.append(pad4).append("\"sensitivityX\": ").append(p.touchpad.sensitivityX).append(",\n")
        sb.append(pad4).append("\"sensitivityY\": ").append(p.touchpad.sensitivityY).append(",\n")
        sb.append(pad4).append("\"acceleration\": ").append(p.touchpad.acceleration).append(",\n")
        sb.append(pad4).append("\"smoothing\": ").append(p.touchpad.smoothing).append(",\n")
        sb.append(pad4).append("\"invertX\": ").append(p.touchpad.invertX).append(",\n")
        sb.append(pad4).append("\"invertY\": ").append(p.touchpad.invertY).append(",\n")
        sb.append(pad4).append("\"widthFraction\": ").append(p.touchpad.widthFraction).append(",\n")
        sb.append(pad4).append("\"tapToClick\": ").append(p.touchpad.tapToClick).append("\n")
        sb.append(pad2).append("},\n")

        // Gyro aiming (additive optional object; absent means disabled default)
        val g = p.gyro
        sb.append(pad2).append("\"gyro\": {\n")
        sb.append(pad4).append("\"enabled\": ").append(g.enabled).append(",\n")
        sb.append(pad4).append("\"activationMode\": \"").append(g.activationMode.name).append("\",\n")
        sb.append(pad4).append("\"sensorSource\": \"").append(g.sensorSource.name).append("\",\n")
        sb.append(pad4).append("\"sensitivityX\": ").append(g.sensitivityX).append(",\n")
        sb.append(pad4).append("\"sensitivityY\": ").append(g.sensitivityY).append(",\n")
        sb.append(pad4).append("\"invertX\": ").append(g.invertX).append(",\n")
        sb.append(pad4).append("\"invertY\": ").append(g.invertY).append(",\n")
        sb.append(pad4).append("\"enableYaw\": ").append(g.enableYaw).append(",\n")
        sb.append(pad4).append("\"enablePitch\": ").append(g.enablePitch).append(",\n")
        sb.append(pad4).append("\"deadzoneDps\": ").append(g.deadzoneDps).append(",\n")
        sb.append(pad4).append("\"smoothing\": ").append(g.smoothing).append(",\n")
        sb.append(pad4).append("\"maxPixelsPerTick\": ").append(g.maxPixelsPerTick).append(",\n")
        sb.append(pad4).append("\"adsMultiplier\": ").append(g.adsMultiplier).append(",\n")
        sb.append(pad4).append("\"recenterOnRelease\": ").append(g.recenterOnRelease).append(",\n")
        sb.append(pad4).append("\"biasX\": ").append(g.biasX).append(",\n")
        sb.append(pad4).append("\"biasY\": ").append(g.biasY).append(",\n")
        sb.append(pad4).append("\"biasZ\": ").append(g.biasZ).append(",\n")
        sb.append(pad4).append("\"regionXPct\": ").append(g.regionXPct).append(",\n")
        sb.append(pad4).append("\"regionYPct\": ").append(g.regionYPct).append(",\n")
        sb.append(pad4).append("\"regionWidthPct\": ").append(g.regionWidthPct).append(",\n")
        sb.append(pad4).append("\"regionHeightPct\": ").append(g.regionHeightPct).append("\n")
        sb.append(pad2).append("},\n")

        // Cells
        sb.append(pad2).append("\"cells\": [\n")
        p.cells.forEachIndexed { i, c ->
            sb.append(pad4).append("{\n")
            sb.append(pad4).append("  \"id\": \"").append(escape(c.id)).append("\",\n")
            sb.append(pad4).append("  \"row\": ").append(c.row).append(",\n")
            sb.append(pad4).append("  \"col\": ").append(c.col).append(",\n")
            sb.append(pad4).append("  \"rowSpan\": ").append(c.rowSpan).append(",\n")
            sb.append(pad4).append("  \"colSpan\": ").append(c.colSpan).append(",\n")
            sb.append(pad4).append("  \"label\": \"").append(escape(c.label)).append("\",\n")
            sb.append(pad4).append("  \"type\": \"").append(c.type.name).append("\",\n")
            sb.append(pad4).append("  \"keys\": [").append(c.keys.joinToString(", ")).append("],\n")
            sb.append(pad4).append("  \"mouseButtons\": [").append(c.mouseButtons.joinToString(", ")).append("],\n")
            sb.append(pad4).append("  \"isToggle\": ").append(c.isToggle).append(",\n")
            sb.append(pad4).append("  \"joystickSprint\": ").append(c.joystickSprint).append(",\n")
            sb.append(pad4).append("  \"gyroAction\": \"").append(c.gyroAction.name).append("\",\n")
            if (c.macro.steps.isNotEmpty()) {
                sb.append(pad4).append("  \"macro\": {\"repeatCount\": ")
                    .append(c.macro.repeatCount).append(", \"steps\": [")
                c.macro.steps.forEachIndexed { index, step ->
                    sb.append("{\"type\": \"").append(step.type.name)
                        .append("\", \"keys\": [").append(step.keys.joinToString(", "))
                        .append("], \"holdMs\": ").append(step.holdMs)
                        .append(", \"delayMs\": ").append(step.delayMs).append("}")
                    if (index < c.macro.steps.size - 1) sb.append(", ")
                }
                sb.append("]},\n")
            }
            sb.append(pad4).append("  \"xPct\": ").append(c.xPct).append(",\n")
            sb.append(pad4).append("  \"yPct\": ").append(c.yPct).append(",\n")
            sb.append(pad4).append("  \"widthDp\": ").append(c.widthDp).append(",\n")
            sb.append(pad4).append("  \"heightDp\": ").append(c.heightDp).append(",\n")
            sb.append(pad4).append("  \"shape\": \"").append(c.shape.name).append("\",\n")
            sb.append(pad4).append("  \"opacity\": ").append(c.opacity)
            if (c.customColor != null) {
                sb.append(",\n").append(pad4).append("  \"customColor\": ").append(c.customColor)
            }
            sb.append("\n").append(pad4).append("}")
            if (i < p.cells.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(pad2).append("]\n")
        sb.append(pad).append("}")
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // -------------------------------------------------------------------
    // Deserialization
    // -------------------------------------------------------------------

    fun parse(jsonStr: String): GameProfile {
        val root = Parser(jsonStr).parse()
        val obj = root.asObject() ?: throw IllegalArgumentException("Expected root JSON object")
        return decodeProfile(obj)
    }

    fun parseList(jsonStr: String): List<GameProfile> {
        val root = Parser(jsonStr).parse()
        val decoded = when (root) {
            is JsonValue.ArrayVal -> root.items.mapNotNull {
                (it as? JsonValue.ObjectVal)?.fields?.let(::decodeProfile)
            }
            is JsonValue.ObjectVal -> listOf(decodeProfile(root.fields))
            else -> emptyList()
        }
        val seenIds = HashSet<String>()
        return decoded.map { profile ->
            if (seenIds.add(profile.id)) profile
            else profile.copy(id = UUID.randomUUID().toString())
        }
    }

    private fun decodeProfile(map: Map<String, JsonValue>): GameProfile {
        val version = (map["schemaVersion"]?.asInt(CURRENT_SCHEMA_VERSION) ?: CURRENT_SCHEMA_VERSION)
            .coerceIn(1, CURRENT_SCHEMA_VERSION)
        val id = sanitizeId(map["id"]?.asString()) { UUID.randomUUID().toString() }
        val name = cleanText(map["name"]?.asString(), MAX_NAME_LEN).ifBlank { "Untitled Profile" }
        val desc = cleanText(map["description"]?.asString(), MAX_DESC_LEN)
        val rows = (map["rows"]?.asInt(3) ?: 3).coerceIn(2, 6)
        val cols = (map["cols"]?.asInt(4) ?: 4).coerceIn(2, 8)
        val spacing = (map["buttonSpacingDp"]?.asInt(8) ?: 8).coerceIn(0, 48)
        val corner = (map["cornerRadiusDp"]?.asInt(18) ?: 18).coerceIn(0, 64)
        val opacity = finiteOrDefault(map["opacity"]?.asFloat(1.0f) ?: 1.0f, 1.0f)
            .coerceIn(0f, 1f)

        val tpObj = map["touchpad"]?.asObject()
        val touchpad = if (tpObj != null) {
            TouchpadConfig(
                sensitivityX = tpObj["sensitivityX"]?.asFloat(0.80f) ?: 0.80f,
                sensitivityY = tpObj["sensitivityY"]?.asFloat(0.80f) ?: 0.80f,
                acceleration = tpObj["acceleration"]?.asFloat(0.15f) ?: 0.15f,
                smoothing = tpObj["smoothing"]?.asFloat(0.35f) ?: 0.35f,
                invertX = tpObj["invertX"]?.asBoolean(false) ?: false,
                invertY = tpObj["invertY"]?.asBoolean(false) ?: false,
                widthFraction = tpObj["widthFraction"]?.asFloat(0.52f) ?: 0.52f,
                tapToClick = tpObj["tapToClick"]?.asBoolean(false) ?: false,
            ).sanitized()
        } else {
            TouchpadConfig()
        }

        val gyroObj = map["gyro"]?.asObject()
        val gyro = if (gyroObj != null) {
            GyroConfig(
                enabled = gyroObj["enabled"]?.asBoolean(false) ?: false,
                activationMode = enumOrDefault(
                    gyroObj["activationMode"]?.asString(),
                    GyroActivationMode.HOLD,
                ),
                sensorSource = enumOrDefault(
                    gyroObj["sensorSource"]?.asString(),
                    GyroSensorSource.FUSED,
                ),
                sensitivityX = gyroObj["sensitivityX"]?.asFloat(3.0f) ?: 3.0f,
                sensitivityY = gyroObj["sensitivityY"]?.asFloat(3.0f) ?: 3.0f,
                invertX = gyroObj["invertX"]?.asBoolean(false) ?: false,
                invertY = gyroObj["invertY"]?.asBoolean(false) ?: false,
                enableYaw = gyroObj["enableYaw"]?.asBoolean(true) ?: true,
                enablePitch = gyroObj["enablePitch"]?.asBoolean(true) ?: true,
                deadzoneDps = gyroObj["deadzoneDps"]?.asFloat(1.0f) ?: 1.0f,
                smoothing = gyroObj["smoothing"]?.asFloat(0.20f) ?: 0.20f,
                maxPixelsPerTick = gyroObj["maxPixelsPerTick"]?.asFloat(200f) ?: 200f,
                adsMultiplier = gyroObj["adsMultiplier"]?.asFloat(0.65f) ?: 0.65f,
                recenterOnRelease = gyroObj["recenterOnRelease"]?.asBoolean(true) ?: true,
                biasX = gyroObj["biasX"]?.asFloat(0f) ?: 0f,
                biasY = gyroObj["biasY"]?.asFloat(0f) ?: 0f,
                biasZ = gyroObj["biasZ"]?.asFloat(0f) ?: 0f,
                regionXPct = gyroObj["regionXPct"]?.asFloat(0.30f) ?: 0.30f,
                regionYPct = gyroObj["regionYPct"]?.asFloat(0.30f) ?: 0.30f,
                regionWidthPct = gyroObj["regionWidthPct"]?.asFloat(0.40f) ?: 0.40f,
                regionHeightPct = gyroObj["regionHeightPct"]?.asFloat(0.40f) ?: 0.40f,
            ).sanitized()
        } else {
            GyroConfig()
        }

        val usedCellIds = HashSet<String>()
        val cells = map["cells"]?.asArray()?.take(MAX_CELLS)?.mapNotNull { item ->
            val cMap = item.asObject() ?: return@mapNotNull null
            val cTypeStr = cMap["type"]?.asString() ?: "KEY"
            val type = runCatching { CellType.valueOf(cTypeStr) }.getOrDefault(CellType.KEY)
            val shapeStr = cMap["shape"]?.asString() ?: "ROUNDED_RECT"
            val shape = runCatching { ButtonShape.valueOf(shapeStr) }.getOrDefault(ButtonShape.ROUNDED_RECT)

            // Imported ids are used as Compose pointerInput keys and InputTracker
            // action ids, so blank/oversized/duplicate values must not survive.
            var cellId = sanitizeId(cMap["id"]?.asString()) { UUID.randomUUID().toString() }
            while (!usedCellIds.add(cellId)) {
                cellId = UUID.randomUUID().toString()
            }

            val macroObj = cMap["macro"]?.asObject()
            val macro = if (macroObj != null) {
                val steps = macroObj["steps"]?.asArray()
                    ?.take(MacroLimits.MAX_STEPS)
                    ?.mapNotNull { item ->
                        val m = item.asObject() ?: return@mapNotNull null
                        val type = runCatching {
                            MacroStepType.valueOf(m["type"]?.asString() ?: "TAP")
                        }.getOrDefault(MacroStepType.TAP)
                        val keys = m["keys"]?.asArray()
                            ?.map { it.asInt() }
                            ?.filter { it in 1..MacroLimits.MAX_EVDEV_CODE }
                            ?.distinct()
                            ?.take(MacroLimits.MAX_KEYS_PER_STEP)
                            ?: emptyList()
                        MacroStep(
                            type = type,
                            keys = keys,
                            holdMs = m["holdMs"]?.asInt(MacroLimits.DEFAULT_HOLD_MS)
                                ?: MacroLimits.DEFAULT_HOLD_MS,
                            delayMs = m["delayMs"]?.asInt(MacroLimits.DEFAULT_DELAY_MS)
                                ?: MacroLimits.DEFAULT_DELAY_MS,
                        )
                    }
                    ?: emptyList()
                MacroConfig(
                    steps = steps,
                    repeatCount = macroObj["repeatCount"]?.asInt(1) ?: 1,
                ).sanitized()
            } else {
                MacroConfig()
            }

            CellConfig(
                id = cellId,
                row = (cMap["row"]?.asInt(0) ?: 0).coerceIn(0, 5),
                col = (cMap["col"]?.asInt(0) ?: 0).coerceIn(0, 7),
                rowSpan = (cMap["rowSpan"]?.asInt(1) ?: 1).coerceIn(1, 6),
                colSpan = (cMap["colSpan"]?.asInt(1) ?: 1).coerceIn(1, 8),
                label = cleanText(cMap["label"]?.asString(), MAX_LABEL_LEN),
                type = type,
                keys = cMap["keys"]?.asArray()
                    ?.map { it.asInt() }
                    ?.filter { it in 1..MAX_EVDEV_CODE }
                    ?.distinct()
                    ?.take(MAX_KEYS_PER_CELL)
                    ?: emptyList(),
                mouseButtons = cMap["mouseButtons"]?.asArray()
                    ?.map { it.asInt() }
                    ?.filter { it in 1..5 }
                    ?.distinct()
                    ?.take(5)
                    ?: emptyList(),
                isToggle = cMap["isToggle"]?.asBoolean(false) ?: false,
                joystickSprint = cMap["joystickSprint"]?.asBoolean(true) ?: true,
                gyroAction = enumOrDefault(
                    cMap["gyroAction"]?.asString(),
                    GyroCellAction.ACTIVATE,
                ),
                macro = macro,
                customColor = (cMap["customColor"] as? JsonValue.NumberVal)?.value
                    ?.takeIf { it.isFinite() && it >= 0.0 && it <= 0xFFFF_FFFFL.toDouble() }
                    ?.toLong(),
                xPct = finiteOrDefault(cMap["xPct"]?.asFloat(0f) ?: 0f, 0f)
                    .coerceIn(0f, 1f),
                yPct = finiteOrDefault(cMap["yPct"]?.asFloat(0f) ?: 0f, 0f)
                    .coerceIn(0f, 1f),
                widthDp = (cMap["widthDp"]?.asInt(68) ?: 68).coerceIn(MIN_CELL_DP, MAX_CELL_DP),
                heightDp = (cMap["heightDp"]?.asInt(54) ?: 54).coerceIn(MIN_CELL_DP, MAX_CELL_DP),
                shape = shape,
                opacity = finiteOrDefault(cMap["opacity"]?.asFloat(1f) ?: 1f, 1f)
                    .coerceIn(0.05f, 1f),
            )
        } ?: emptyList()

        return GameProfile(
            id = id,
            name = name,
            description = desc,
            schemaVersion = version,
            rows = rows,
            cols = cols,
            cells = cells,
            touchpad = touchpad,
            gyro = gyro,
            buttonSpacingDp = spacing,
            cornerRadiusDp = corner,
            opacity = opacity,
        )
    }

    private fun finiteOrDefault(value: Float, default: Float): Float =
        if (value.isFinite()) value else default

    /** Parse an enum name defensively; imports may be hand-edited. */
    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        if (raw == null) default else runCatching { enumValueOf<T>(raw) }.getOrDefault(default)

    private fun sanitizeId(raw: String?, fallback: () -> String): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed.length > MAX_ID_LEN) fallback() else trimmed
    }

    /** Remove control characters, collapse whitespace, and bound the length. */
    private fun cleanText(raw: String?, maxLen: Int): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(minOf(raw.length, maxLen))
        var lastWasSpace = false
        for (ch in raw) {
            val normalized = when {
                ch == '\n' || ch == '\r' || ch == '\t' -> ' '
                ch.isISOControl() -> continue
                else -> ch
            }
            if (normalized == ' ') {
                if (lastWasSpace) continue
                lastWasSpace = true
            } else {
                lastWasSpace = false
            }
            sb.append(normalized)
            if (sb.length >= maxLen) break
        }
        return sb.toString().trim()
    }

    // -------------------------------------------------------------------
    // Tokenizer & Parser
    // -------------------------------------------------------------------

    private class Parser(private val src: String) {
        private var idx = 0

        fun parse(): JsonValue {
            skipWhitespace()
            if (idx >= src.length) throw IllegalArgumentException("Empty JSON")
            val v = parseValue()
            skipWhitespace()
            if (idx != src.length) {
                throw IllegalArgumentException("Trailing data after JSON value at offset $idx")
            }
            return v
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (idx >= src.length) throw IllegalArgumentException("Unexpected EOF at $idx")
            return when (val c = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$c' at offset $idx")
            }
        }

        private fun parseObject(): JsonValue.ObjectVal {
            idx++ // skip '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (idx < src.length && src[idx] == '}') {
                idx++
                return JsonValue.ObjectVal(map)
            }
            while (idx < src.length) {
                skipWhitespace()
                if (src[idx] != '"') throw IllegalArgumentException("Expected string key at offset $idx")
                val key = parseString().value
                skipWhitespace()
                if (idx >= src.length || src[idx] != ':') throw IllegalArgumentException("Expected ':' at offset $idx")
                idx++ // skip ':'
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (idx >= src.length) throw IllegalArgumentException("Unterminated object at $idx")
                if (src[idx] == ',') {
                    idx++
                    continue
                } else if (src[idx] == '}') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at offset $idx")
                }
            }
            return JsonValue.ObjectVal(map)
        }

        private fun parseArray(): JsonValue.ArrayVal {
            idx++ // skip '['
            val list = ArrayList<JsonValue>()
            skipWhitespace()
            if (idx < src.length && src[idx] == ']') {
                idx++
                return JsonValue.ArrayVal(list)
            }
            while (idx < src.length) {
                list.add(parseValue())
                skipWhitespace()
                if (idx >= src.length) throw IllegalArgumentException("Unterminated array at $idx")
                if (src[idx] == ',') {
                    idx++
                    continue
                } else if (src[idx] == ']') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at offset $idx")
                }
            }
            return JsonValue.ArrayVal(list)
        }

        private fun parseString(): JsonValue.StringVal {
            idx++ // skip opening quote
            val sb = StringBuilder()
            while (idx < src.length) {
                when (val c = src[idx++]) {
                    '"' -> return JsonValue.StringVal(sb.toString())
                    '\\' -> {
                        if (idx >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[idx++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (idx + 4 > src.length) throw IllegalArgumentException("Malformed unicode escape")
                                val hex = src.substring(idx, idx + 4)
                                idx += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): JsonValue.NumberVal {
            val start = idx
            if (src[idx] == '-') idx++
            while (idx < src.length && src[idx] in '0'..'9') idx++
            if (idx < src.length && src[idx] == '.') {
                idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            if (idx < src.length && (src[idx] == 'e' || src[idx] == 'E')) {
                idx++
                if (idx < src.length && (src[idx] == '+' || src[idx] == '-')) idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            val numStr = src.substring(start, idx)
            val d = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $numStr")
            return JsonValue.NumberVal(d)
        }

        private fun parseBoolean(): JsonValue.BooleanVal {
            return if (src.startsWith("true", idx)) {
                idx += 4
                JsonValue.BooleanVal(true)
            } else if (src.startsWith("false", idx)) {
                idx += 5
                JsonValue.BooleanVal(false)
            } else {
                throw IllegalArgumentException("Expected boolean at offset $idx")
            }
        }

        private fun parseNull(): JsonValue.NullVal {
            if (src.startsWith("null", idx)) {
                idx += 4
                return JsonValue.NullVal
            }
            throw IllegalArgumentException("Expected null at offset $idx")
        }

        private fun skipWhitespace() {
            while (idx < src.length && src[idx] <= ' ') idx++
        }
    }
}
