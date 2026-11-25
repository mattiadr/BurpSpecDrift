package com.mattiadr.burpSpecDrift.model

import kotlinx.serialization.Serializable

/**
 * Format-agnostic result of importing a spec: the info shown in the summary plus the flattened
 * endpoints. Produced by an importer (OpenAPI now, Postman/Bruno later) and persisted as-is.
 */
@Serializable
data class ImportedSpec(
	val title: String? = null,
	val version: String? = null,
	/** Human-readable source format label for the summary, e.g. "OpenAPI 3.1.0" / "Postman 2.1.0". */
	val format: String? = null,
	val endpoints: List<EndpointRow> = emptyList(),
)

/** The persisted form of one tab: its metadata plus the imported spec (null when empty). */
@Serializable
data class PersistedTab(
	val id: String,
	val defaultName: String,
	val userName: String? = null,
	val source: String? = null,
	val baseOverride: String? = null,
	val customBases: List<String> = emptyList(),
	val spec: ImportedSpec? = null,
	/** How far into the proxy history this tab has already counted (see docs/hit-counting.md). */
	val lastProxyId: Int = 0,
)
