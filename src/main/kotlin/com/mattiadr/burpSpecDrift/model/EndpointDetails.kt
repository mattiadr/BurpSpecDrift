package com.mattiadr.burpSpecDrift.model

import kotlinx.serialization.Serializable

/**
 * Normalized, format-agnostic detail fields for an endpoint. Kept shallow on purpose: content
 * types + an optional text example instead of full JSON Schema trees.
 */

/**
 * One security scheme the operation can require, resolved against the spec's
 * `components.securitySchemes`. [type] is the OpenAPI scheme type (http, apiKey, oauth2, …);
 * [detail] is a short human-readable summary (e.g. "bearer (JWT)", "in header: X-API-Key"),
 * null when the scheme is only referenced but not defined. [scopes] applies to oauth2 /
 * openIdConnect, empty otherwise.
 */
@Serializable
data class SecurityScheme(
	val name: String,
	val type: String? = null,
	val detail: String? = null,
	val scopes: List<String> = emptyList(),
)

/**
 * One security requirement: the set of [schemes] that together (AND) satisfy auth. An operation
 * is authorized when any one of its requirements (OR) is met; an empty requirement list means the
 * operation declares no auth. See the Auth column (names only) vs. the detail view (full structure).
 */
@Serializable
data class SecurityRequirement(
	val schemes: List<SecurityScheme> = emptyList(),
)

/** A request parameter. [location] is path / query / header / cookie. */
@Serializable
data class Param(
	val name: String,
	val location: String,
	val required: Boolean,
	val type: String? = null,
	val description: String? = null,
	/** The value sent when this parameter is used; resolved from the spec at import time. */
	val example: String = "",
)

/**
 * One accepted/returned media type and its optional text example. [name] distinguishes several
 * examples that share a media type (e.g. collapsed Postman/Bruno requests), shown in the body
 * dropdown; null for OpenAPI, where each media type is unique within a body.
 */
@Serializable
data class ContentExample(
	val mediaType: String,
	val example: String? = null,
	val name: String? = null,
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
