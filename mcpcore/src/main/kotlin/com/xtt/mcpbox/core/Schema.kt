// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import kotlinx.serialization.json.JsonObject

/** Small helpers to build JSON-Schema fragments for the MCP tools. */
object Schema {

    fun obj(props: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject = jo(
        "type" to "object",
        "properties" to props,
        "required" to required,
        "additionalProperties" to false
    )

    fun str(desc: String, default: String? = null, enum: List<String>? = null): JsonObject = jo(
        "type" to "string",
        "description" to desc,
        "default" to default,
        "enum" to enum
    )

    fun int(desc: String, default: Int? = null, min: Int? = null, max: Int? = null): JsonObject = jo(
        "type" to "integer",
        "description" to desc,
        "default" to default,
        "minimum" to min,
        "maximum" to max
    )

    fun bool(desc: String, default: Boolean? = null): JsonObject = jo(
        "type" to "boolean",
        "description" to desc,
        "default" to default
    )
}
