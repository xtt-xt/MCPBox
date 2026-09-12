// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON codec. Lenient so that sloppy AI clients still work. */
val J: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }

fun JsonObject.strOr(key: String, def: String): String = str(key) ?: def

fun JsonObject.intOr(key: String, def: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: def

fun JsonObject.longOr(key: String, def: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: def

fun JsonObject.boolOr(key: String, def: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: def

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** Build a JsonObject from Kotlin values, keeping nested maps/lists. */
fun jo(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { it.first to anyToJson(it.second!!) }
)

fun jarr(items: Iterable<Any?>): JsonArray = JsonArray(items.map { anyToJson(it) })

@Suppress("UNCHECKED_CAST")
fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value.toDouble())
    is Map<*, *> -> JsonObject((value as Map<String, Any?>).entries.filter { it.value != null }
        .associate { it.key to anyToJson(it.value) })
    is Iterable<*> -> JsonArray(value.map { anyToJson(it) })
    is Array<*> -> JsonArray(value.map { anyToJson(it) })
    else -> JsonPrimitive(value.toString())
}
