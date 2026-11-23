package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.EndpointRow

/** How a single hit count is filtered. */
enum class HitFilter {
	ANY,
	ZERO,
	POSITIVE;

	fun matches(count: Int): Boolean = when (this) {
		ANY -> true
		ZERO -> count == 0
		POSITIVE -> count > 0
	}
}

/**
 * A snapshot of the endpoint-table filter. Each column constrains the rows independently (they are
 * AND-ed together); within a multi-select column an empty set means "no constraint" and a non-empty
 * set matches rows whose value is one of the selected options (OR). Edited one column at a time from
 * the per-column header popups.
 */
data class EndpointFilter(
	val methods: Set<String> = emptySet(),
	val bases: Set<String> = emptySet(),
	val auth: Set<String> = emptySet(),
	val proxyHits: HitFilter = HitFilter.ANY,
	val repeaterHits: HitFilter = HitFilter.ANY,
	val otherHits: HitFilter = HitFilter.ANY,
) {
	val isActive: Boolean
		get() = methods.isNotEmpty() || bases.isNotEmpty() || auth.isNotEmpty() ||
			proxyHits != HitFilter.ANY || repeaterHits != HitFilter.ANY || otherHits != HitFilter.ANY

	/** Whether [row] passes every active criterion, resolving its base with [globalOverride]. */
	fun matches(row: EndpointRow, globalOverride: String?): Boolean {
		if (methods.isNotEmpty() && row.method !in methods) return false
		if (bases.isNotEmpty()) {
			val base = row.effectiveBase(globalOverride) ?: NONE
			if (base !in bases) return false
		}
		if (auth.isNotEmpty()) {
			val names = row.security.ifEmpty { listOf(NONE) }
			if (names.none { it in auth }) return false
		}
		if (!proxyHits.matches(row.proxyHits)) return false
		if (!repeaterHits.matches(row.repeaterHits)) return false
		if (!otherHits.matches(row.otherHits)) return false
		return true
	}

	companion object {
		/** Option label standing for "no base" / "no auth". */
		const val NONE = "<none>"
	}
}
