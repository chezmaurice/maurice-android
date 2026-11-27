package eu.chezmaurice.core.domains

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Reading the server's lists as iOS reads them (`compactMap` over the rows):
// a row that cannot be read is left out, the others stay. Without this one
// row with a missing name emptied the whole list of domains, models, tool
// families or suggestions — Android stricter than iOS.

/** A list whose unreadable rows are dropped; anything but a list is still an error. */
class TolerantList<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val plain = ListSerializer(element)
    override val descriptor: SerialDescriptor = plain.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return plain.deserialize(decoder)
        return json.json.decodeEach(element, json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: List<T>) = plain.serialize(encoder, value)
}

/** Each element of [array] that decodes; throws when [array] is not a list. */
fun <T> Json.decodeEach(element: KSerializer<T>, array: JsonElement): List<T> {
    if (array !is JsonArray) throw SerializationException("expected a list")
    return array.mapNotNull { runCatching { decodeFromJsonElement(element, it) }.getOrNull() }
}

/**
 * A count the server may write `3`, `3.0` or `"3"`: what Swift's `as? Int`
 * takes (a whole number) and the string besides. Anything else is 0, the
 * field's default on both sides, rather than the whole row lost.
 */
object LenientInt : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("eu.chezmaurice.LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int = wholeNumber(decoder) ?: 0
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** As [LenientInt], null when it is not a whole number. */
object LenientIntOrNull : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("eu.chezmaurice.LenientIntOrNull", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? = wholeNumber(decoder)
    override fun serialize(encoder: Encoder, value: Int?) = if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
}

/** A flag the server may write `true`, `1` or `"true"` (Swift's `as? Bool` takes 1 and 0); false otherwise. */
object LenientBool : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("eu.chezmaurice.LenientBool", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val p = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: return decoder.decodeBoolean()
        return p.flag() ?: false
    }
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** `true`/`false`, their strings, or the numbers 1 and 0 (what an `NSNumber` bridges to `Bool`). */
fun JsonPrimitive.flag(): Boolean? = when {
    isString -> booleanOrNull
    content == "1" -> true
    content == "0" -> false
    else -> booleanOrNull
}

private fun wholeNumber(decoder: Decoder): Int? {
    val p = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: return decoder.decodeInt()
    p.content.toIntOrNull()?.let { return it }
    if (!p.isString && p.booleanOrNull != null) return null
    val d = p.doubleOrNull ?: return null
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) <= Int.MAX_VALUE) d.toInt() else null
}
