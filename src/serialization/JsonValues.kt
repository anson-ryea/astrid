package serialization

import kotlinx.serialization.json.*

internal fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.intValue(): Int? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

internal fun JsonElement?.longValue(): Long? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

internal fun JsonElement?.doubleValue(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

internal fun JsonElement?.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun JsonObject.nonBlankString(name: String): String =
    this[name].stringValue()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("'$name' must be a non-blank string")
