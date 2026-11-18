package com.mattiadr.burpSpecDrift.model

import io.swagger.v3.oas.models.OpenAPI

/**
 * Live data for a single SpecDrift tab, kept out of the GUI so it can later be
 * persisted via Burp's persistence API.
 */
class SpecTabData(
	private val defaultName: String,
) {
	/** Name set manually by the user; highest priority. */
	private var userName: String? = null

	/** Title taken from the loaded spec; used when the user has not set a name. */
	private var specTitle: String? = null

	/** The displayed tab name, by priority: user > spec > default number. */
	val name: String get() = userName ?: specTitle ?: defaultName

	/** The parsed spec, or null while nothing has been loaded yet. */
	var openAPI: OpenAPI? = null
		private set

	/** The flattened endpoints of [openAPI], or empty while nothing has been loaded. */
	var endpoints: List<EndpointRow> = emptyList()
		private set

	/** Where the spec came from (file path, URL, or "Sent from Burp"); null until loaded. */
	var source: String? = null
		private set

	val hasLoadedSpec: Boolean get() = openAPI != null

	/** Bases declared by the spec (across root/path/operation servers), rebuilt on each load. */
	private val specBases = linkedSetOf<String>()

	/** Bases added manually by the user; kept across reloads. */
	private val customBases = linkedSetOf<String>()

	/** The currently selected tab-level base override. Not yet applied to routes. */
	var baseOverride: String? = null

	/** All bases available for override: spec-declared plus user-added, de-duplicated. */
	fun availableBases(): List<String> = (specBases + customBases).toList()

	fun addCustomBase(base: String) {
		customBases.add(base)
	}

	/** A removable base: added by the user and not (also) declared by the spec. */
	fun isCustomBase(base: String): Boolean = base in customBases && base !in specBases

	fun removeCustomBase(base: String) {
		customBases.remove(base)
		if (baseOverride == base) baseOverride = null
	}

	/** Records a successfully parsed spec: stores it, extracts endpoints, and names the tab. */
	fun onSpecLoaded(openAPI: OpenAPI, source: String) {
		this.openAPI = openAPI
		this.source = source
		this.endpoints = EndpointExtractor.extract(openAPI)
		rebuildSpecBases()
		openAPI.info?.title?.takeIf { it.isNotBlank() }?.let { specTitle = it }
	}

	private fun rebuildSpecBases() {
		specBases.clear()
		endpoints.forEach { row ->
			row.specBase?.let { specBases.add(it) }
			specBases.addAll(row.alternativeBases)
		}
	}

	/**
	 * Applies a user edit. Ignored when the text is blank or unchanged from the current
	 * displayed name, so double-clicking without really changing it does not count as user-set.
	 */
	fun renameByUser(newName: String) {
		if (newName.isNotBlank() && newName != name) {
			userName = newName
		}
	}
}
