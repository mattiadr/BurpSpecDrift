package com.mattiadr.burpSpecDrift.spec.postman

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves Postman `{{variable}}` tokens using the collection-level variables. Unknown tokens are
 * left intact (an unresolved base becomes null, which the tab's base-override UI covers). Kept as a
 * single component so environment-file variables can be merged in later without touching the importer.
 */
class VariableResolver(variables: List<PostmanVariable>) {

	private val values: Map<String, String> =
		variables.mapNotNull { v -> v.key?.takeIf { it.isNotBlank() }?.let { it to stringify(v.value) } }.toMap()

	/** Replaces every known `{{name}}` with its value; unknown tokens are returned unchanged. */
	fun resolve(text: String): String {
		if ("{{" !in text) return text
		return TOKEN.replace(text) { match -> values[match.groupValues[1].trim()] ?: match.value }
	}

	private fun stringify(value: JsonElement?): String = when (value) {
		is JsonPrimitive -> if (value is JsonNull) "" else value.content
		else -> ""
	}

	companion object {
		private val TOKEN = Regex("""\{\{([^{}]+)}}""")
	}
}
