package com.mattiadr.burpSpecDrift.model

import kotlinx.serialization.Serializable

/**
 * Normalized, format-agnostic detail fields for an endpoint. Kept shallow on purpose: content
 * types + an optional text example instead of full JSON Schema trees.
 */

/** A request parameter. [location] is path / query / header / cookie. */
@Serializable
data class Param(
	val name: String,
	val location: String,
	val required: Boolean,
	val type: String? = null,
	val description: String? = null,
	val example: String? = null,
)

/** One accepted/returned media type and its optional text example. */
@Serializable
data class ContentExample(
	val mediaType: String,
	val example: String? = null,
)

/** A request body: whether required and the accepted contents (media type + example). */
@Serializable
data class Body(
	val required: Boolean,
	val contents: List<ContentExample> = emptyList(),
)

/** A declared response: status code (or "default"), description, and the returned contents. */
@Serializable
data class ResponseInfo(
	val statusCode: String,
	val description: String? = null,
	val contents: List<ContentExample> = emptyList(),
)
