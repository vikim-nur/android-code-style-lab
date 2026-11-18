package kg.nur.quality

import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfigProperty
import org.ec4j.core.model.PropertyType

private fun positiveInt(name: String, description: String, default: Int) = EditorConfigProperty(
    type = PropertyType.LowerCasingPropertyType(
        name, description, PropertyType.PropertyValueParser.POSITIVE_INT_VALUE_PARSER,
        setOf(default.toString()),
    ),
    defaultValue = default,
)

val MODIFIER_MIN_CALLS = positiveInt("nur_modifier_chain_min_calls", "Minimum Modifier calls before wrapping", 2)
val CALL_MIN_ARGUMENTS = positiveInt("nur_call_arguments_min_count", "Minimum parenthesized call arguments before wrapping", 4)
val COMPACT_INTENT_MAX_WIDTH = positiveInt("nur_compact_intent_max_line_length", "Maximum width of a compact intent line", 120)
