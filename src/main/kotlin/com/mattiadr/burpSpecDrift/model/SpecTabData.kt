package com.mattiadr.burpSpecDrift.model

import io.swagger.v3.oas.models.OpenAPI
import java.util.UUID

/**
 * Live data for a single SpecDrift tab, kept out of the GUI so it can be persisted via Burp's
 * persistence API. Persistence saves the resolved spec text plus the overrides and hit counts,
 * keyed by [EndpointRow.key]; the spec is re-parsed on restore.
 */
class SpecTabData(
	val defaultName: String,
	val id: String = UUID.randomUUID().toString(),
) {
	/** Name set manually by the user; highest priority. */
	private var userName: String? = null

	/** Title taken from the loaded spec; used when the user has not set a name. */
	private var specTitle: String? = null

	/** The displayed tab name, by priority: user > spec > default number. */
	val name: String get() = userName ?: specTitle ?: defaultName

	/** The user-set name, or null — exposed for persistence. */
	val userNameValue: String? get() = userName

	/** The parsed spec, or null while nothing has been loaded yet. */
	var openAPI: OpenAPI? = null
		private set

	/** The resolved spec serialized as text, persisted and re-parsed on restore. */
	var specText: String? = null
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

	private var _baseOverride: String? = null

	/** The currently selected tab-level base override. */
	var baseOverride: String?
		get() = _baseOverride
		set(value) {
			if (_baseOverride != value) {
				_baseOverride = value
				onChanged?.invoke()
			}
		}

	/** Invoked whenever the available base list changes, so every view can refresh. */
	var onBasesChanged: (() -> Unit)? = null

	/** Invoked whenever persistent state (overrides, names, bases) changes, to trigger a save. */
	var onChanged: (() -> Unit)? = null

	/** All bases available for override: spec-declared plus user-added, de-duplicated. */
	fun availableBases(): List<String> = (specBases + customBases).toList()

	fun customBasesList(): List<String> = customBases.toList()

	fun addCustomBase(base: String) {
		if (customBases.add(base)) {
			onBasesChanged?.invoke()
			onChanged?.invoke()
		}
	}

	/** A removable base: added by the user and not (also) declared by the spec. */
	fun isCustomBase(base: String): Boolean = base in customBases && base !in specBases

	fun removeCustomBase(base: String) {
		if (!customBases.remove(base)) return
		// Drop any override (global or per-route) that pointed at the removed base.
		if (_baseOverride == base) _baseOverride = null
		endpoints.forEach { if (it.routeOverride == base) it.routeOverride = null }
		onBasesChanged?.invoke()
		onChanged?.invoke()
	}

	/** Per-route overrides, keyed by route, for persistence. */
	fun routeOverrides(): Map<String, String> =
		endpoints.filter { it.routeOverride != null }.associate { it.key() to it.routeOverride!! }

	/** Per-route hit counts, keyed by route, for persistence. */
	fun routeHits(): Map<String, Int> =
		endpoints.filter { it.hits > 0 }.associate { it.key() to it.hits }

	/** Called by views after changing a per-route override, to trigger a save. */
	fun notifyChanged() {
		onChanged?.invoke()
	}

	/**
	 * Records a successfully parsed spec: stores it (and its serialized [specText]), extracts
	 * endpoints, and names the tab. Resets per-route state, which is invalidated by a new spec.
	 */
	fun onSpecLoaded(openAPI: OpenAPI, source: String, specText: String) {
		this.openAPI = openAPI
		this.source = source
		this.specText = specText
		this.endpoints = EndpointExtractor.extract(openAPI)
		rebuildSpecBases()
		openAPI.info?.title?.takeIf { it.isNotBlank() }?.let { specTitle = it }
	}

	/** Restores persisted meta (before wiring callbacks); does not fire change events. */
	fun restoreMeta(userName: String?, baseOverride: String?, customBases: List<String>) {
		this.userName = userName
		this._baseOverride = baseOverride
		this.customBases.addAll(customBases)
	}

	/** Re-applies persisted per-route overrides and hit counts after the spec has been loaded. */
	fun applyRouteState(overrides: Map<String, String>, hits: Map<String, Int>) {
		endpoints.forEach { row ->
			overrides[row.key()]?.let { row.routeOverride = it }
			hits[row.key()]?.let { row.hits = it }
		}
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
			onChanged?.invoke()
		}
	}
}
