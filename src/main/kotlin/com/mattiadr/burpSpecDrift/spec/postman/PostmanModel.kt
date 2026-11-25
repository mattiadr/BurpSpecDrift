package com.mattiadr.burpSpecDrift.spec.postman

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Partial model of a Postman Collection v2.1 (only the fields SpecDrift needs). Field names follow
 * the published schema (https://schema.postman.com/json/collection/v2.1.0/collection.json); union
 * fields that the schema allows to be "string or object/array" are kept as raw [JsonElement] and
 * resolved in [PostmanImporter]. Parsed with `ignoreUnknownKeys`, so extra fields are dropped and a
 * few commonly-exported-but-unschematized ones (e.g. response `name`, `body.options`) are read
 * best-effort.
 */
@Serializable
data class PostmanCollection(
	val info: PostmanInfo? = null,
	val item: List<PostmanItem> = emptyList(),
	val variable: List<PostmanVariable> = emptyList(),
	val auth: PostmanAuth? = null,
)

@Serializable
data class PostmanInfo(
	val name: String? = null,
	val schema: String? = null,
)

/** A folder (has [item]) or a request (has [request]); never both. Folder-level [auth] is inherited. */
@Serializable
data class PostmanItem(
	val name: String? = null,
	val item: List<PostmanItem>? = null,
	val request: JsonElement? = null,
	val response: List<PostmanResponse> = emptyList(),
	val auth: PostmanAuth? = null,
)

@Serializable
data class PostmanRequest(
	val method: String? = null,
	val header: JsonElement? = null,
	val url: JsonElement? = null,
	val body: PostmanBody? = null,
	val auth: PostmanAuth? = null,
	val description: JsonElement? = null,
)

/** `mode` is one of raw / urlencoded / formdata / file / graphql. [options] is schema-free. */
@Serializable
data class PostmanBody(
	val mode: String? = null,
	val raw: String? = null,
	val urlencoded: List<PostmanField> = emptyList(),
	val formdata: List<PostmanField> = emptyList(),
	val graphql: JsonElement? = null,
	val options: JsonObject? = null,
	val disabled: Boolean = false,
)

/** A urlencoded or formdata field. [type] (text / file) applies to formdata only. */
@Serializable
data class PostmanField(
	val key: String? = null,
	val value: String? = null,
	val type: String? = null,
	val disabled: Boolean = false,
	val description: JsonElement? = null,
)

@Serializable
data class PostmanHeader(
	val key: String? = null,
	val value: String? = null,
	val disabled: Boolean = false,
	val description: JsonElement? = null,
)

@Serializable
data class PostmanQuery(
	val key: String? = null,
	val value: String? = null,
	val disabled: Boolean = false,
	val description: JsonElement? = null,
)

@Serializable
data class PostmanVariable(
	val key: String? = null,
	val value: JsonElement? = null,
	val description: JsonElement? = null,
)

/** [type] names the mechanism (bearer / basic / apikey / oauth2 / noauth / …); the matching array holds its params. */
@Serializable
data class PostmanAuth(
	val type: String? = null,
	val apikey: List<PostmanAuthParam> = emptyList(),
	val basic: List<PostmanAuthParam> = emptyList(),
	val bearer: List<PostmanAuthParam> = emptyList(),
	val oauth2: List<PostmanAuthParam> = emptyList(),
)

@Serializable
data class PostmanAuthParam(
	val key: String? = null,
	val value: JsonElement? = null,
	val type: String? = null,
)

/** A saved example response. `name` is not in the schema but is present in real exports. */
@Serializable
data class PostmanResponse(
	val name: String? = null,
	val status: String? = null,
	val code: Int? = null,
	val header: JsonElement? = null,
	val body: String? = null,
)
