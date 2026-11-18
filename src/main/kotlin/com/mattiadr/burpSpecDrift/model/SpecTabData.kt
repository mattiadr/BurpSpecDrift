package com.mattiadr.burpSpecDrift.model

import io.swagger.v3.oas.models.OpenAPI

/**
 * Live data for a single SpecDrift tab, kept out of the GUI so it can later be
 * persisted via Burp's persistence API.
 */
class SpecTabData(
	var name: String,
) {
	/** True while [name] is still the auto-assigned default (not renamed by the user). */
	var nameIsDefault: Boolean = true

	/** The parsed spec, or null while nothing has been loaded yet. */
	var openAPI: OpenAPI? = null

	/** Where the spec came from (file path, URL, or "Sent from Burp"); null until loaded. */
	var source: String? = null

	val hasLoadedSpec: Boolean get() = openAPI != null

	/** Renames the tab from a spec title, but only while the default name is still in place. */
	fun applySpecTitleToName(title: String?) {
		if (nameIsDefault && !title.isNullOrBlank()) {
			name = title
			nameIsDefault = false
		}
	}

	/** Marks the name as manually set by the user. */
	fun rename(newName: String) {
		name = newName
		nameIsDefault = false
	}
}
