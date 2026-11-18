package com.mattiadr.burpSpecDrift.model

/**
 * One row of the endpoint table: a single operation (method + path) of a spec.
 * [path] is kept verbatim from the spec; [specBase] is the resolved server URL that
 * applies to this operation (operation → path → root precedence), or null when the
 * spec declares no server. The effective route is [specBase] + [path].
 */
class EndpointRow(
	val method: String,
	val path: String,
	val specBase: String?,
	val alternativeBases: List<String> = emptyList(),
	val operationId: String? = null,
	val summary: String? = null,
	val tags: List<String> = emptyList(),
) {
	/** How many matching requests were seen in the proxy. Populated by the diff later. */
	var hits: Int = 0

	/** The base actually used, once base overrides exist. For now it is just [specBase]. */
	val effectiveBase: String? get() = specBase

	/** The full route: effective base + path, or just the path when no base is known. */
	fun route(): String {
		val base = effectiveBase ?: return path
		return base.trimEnd('/') + path
	}
}
