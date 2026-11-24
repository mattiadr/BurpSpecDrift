package com.mattiadr.burpSpecDrift.spec.openapi

import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema

/**
 * Builds a sample body from an OpenAPI schema (Swagger-UI style) when the spec provides no example.
 * Prefers explicit example/default/enum, then falls back to type defaults, resolving `$ref`
 * against the spec components with a cycle guard.
 */
object OpenApiSchemaExampleGenerator {

	/** Returns a JSON sample for [schema], or null if nothing could be built. */
	fun generateJson(schema: Schema<*>?, openAPI: OpenAPI): String? {
		val value = build(schema, openAPI, HashSet()) ?: return null
		return runCatching { Json.pretty(value) }.getOrNull()
	}

	/**
	 * Returns a single **scalar** sample for [schema] (not JSON) — for a parameter value: the
	 * declared example/default, the first enum option, a sample for a known string format, or a
	 * type default. Returns null when the schema carries no usable scalar hint (a bare string with
	 * no format, an object/array, or an unknown type), leaving the caller to pick a placeholder.
	 */
	fun generateScalar(schema: Schema<*>?, openAPI: OpenAPI): String? = scalar(schema, openAPI, HashSet())

	private fun scalar(schema: Schema<*>?, openAPI: OpenAPI, seen: MutableSet<String>): String? {
		if (schema == null) return null

		schema.`$ref`?.let { ref ->
			val name = ref.substringAfterLast('/')
			if (!seen.add(name)) return null // cycle
			val resolved = scalar(openAPI.components?.schemas?.get(name), openAPI, seen)
			seen.remove(name)
			return resolved
		}

		schema.example?.let { return it.toString() }
		schema.default?.let { return it.toString() }
		schema.getEnum()?.firstOrNull()?.let { return it.toString() }

		return when (schema.type ?: schema.types?.firstOrNull()) {
			"integer", "number" -> "0"
			"boolean" -> "true"
			"string" -> knownFormatSample(schema.format) // null for a bare/unknown format
			else -> null
		}
	}

	private fun build(schema: Schema<*>?, openAPI: OpenAPI, seen: MutableSet<String>): Any? {
		if (schema == null) return null

		schema.`$ref`?.let { ref ->
			val name = ref.substringAfterLast('/')
			if (!seen.add(name)) return null // cycle
			val resolved = build(openAPI.components?.schemas?.get(name), openAPI, seen)
			seen.remove(name)
			return resolved
		}

		schema.example?.let { return it }
		schema.default?.let { return it }
		schema.getEnum()?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("|") }

		schema.allOf?.let { parts ->
			val merged = LinkedHashMap<String, Any?>()
			parts.forEach { (build(it, openAPI, seen) as? Map<*, *>)?.forEach { (k, v) -> merged[k.toString()] = v } }
			if (merged.isNotEmpty()) return merged
		}
		(schema.oneOf ?: schema.anyOf)?.firstOrNull()?.let { return build(it, openAPI, seen) }

		val type = schema.type ?: schema.types?.firstOrNull()
		return when {
			type == "object" || schema.properties != null -> {
				val obj = LinkedHashMap<String, Any?>()
				schema.properties?.forEach { (name, prop) -> obj[name] = build(prop, openAPI, seen) }
				obj
			}
			type == "array" -> listOf(build(schema.items, openAPI, seen))
			type == "integer" || type == "number" -> 0
			type == "boolean" -> true
			type == "string" -> stringSample(schema.format)
			else -> null
		}
	}

	private fun stringSample(format: String?): String = knownFormatSample(format) ?: "string"

	/** Sample for a recognized string [format], or null for an absent/unknown one. */
	private fun knownFormatSample(format: String?): String? = when (format) {
		"date" -> "2020-01-01"
		"date-time" -> "2020-01-01T00:00:00Z"
		"uuid" -> "00000000-0000-0000-0000-000000000000"
		"email" -> "user@example.com"
		"byte" -> "U3dhZ2dlcg=="
		else -> null
	}
}
