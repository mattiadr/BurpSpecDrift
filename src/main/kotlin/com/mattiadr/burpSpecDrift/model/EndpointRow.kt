package com.mattiadr.burpSpecDrift.model

import kotlinx.serialization.Serializable

/** Which source the effective base of a route comes from (drives the cell styling). */
enum class BaseSource {
	/** No base at all: not declared by the spec and no override. */
	NONE,

	/** Declared by the spec, no override. */
	SPEC,

	/** Taken from the tab-level (global) override. */
	GLOBAL_OVERRIDE,

	/** Taken from this route's own override. */
	ROUTE_OVERRIDE,
}

/**
 * One row of the endpoint table: a single operation (method + path) of a spec.
 * [path] is kept verbatim from the spec; [specBase] is the resolved server URL that
 * applies to this operation (operation → path → root precedence), or null when the
 * spec declares no server. The effective base layers overrides on top: route > global > spec.
 */
@Serializable
class EndpointRow(
	val method: String,
	val path: String,
	val specBase: String?,
	val alternativeBases: List<String> = emptyList(),
	val operationId: String? = null,
	val summary: String? = null,
	val description: String? = null,
	val tags: List<String> = emptyList(),
	val deprecated: Boolean = false,
	val security: List<String> = emptyList(),
	val parameters: List<Param> = emptyList(),
	val requestBody: Body? = null,
	val responses: List<ResponseInfo> = emptyList(),
	/** This route's own base override, or null to fall back to the global/spec base. */
	var routeOverride: String? = null,
	/** Matching requests seen in the proxy. Populated by the diff later. */
	var proxyHits: Int = 0,
	/** Matching requests sent from Repeater. Populated by the diff later. */
	var repeaterHits: Int = 0,
	/** Matching requests seen from any other Burp tool. Populated by the diff later. */
	var otherHits: Int = 0,
) {
	/** The base this route would use without its own override (global override or spec base). */
	fun defaultBase(globalOverride: String?): String? = globalOverride ?: specBase

	/** The base actually used, layering route > global > spec. */
	fun effectiveBase(globalOverride: String?): String? = when {
		routeOverride != null -> routeOverride
		globalOverride != null -> globalOverride
		else -> specBase
	}

	fun baseSource(globalOverride: String?): BaseSource = when {
		routeOverride != null -> BaseSource.ROUTE_OVERRIDE
		globalOverride != null -> BaseSource.GLOBAL_OVERRIDE
		specBase != null -> BaseSource.SPEC
		else -> BaseSource.NONE
	}

	/** The full route: effective base + path, or just the path when no base is known. */
	fun route(globalOverride: String?): String {
		val base = effectiveBase(globalOverride) ?: return path
		return base.trimEnd('/') + path
	}
}
