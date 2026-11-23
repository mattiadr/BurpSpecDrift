package com.mattiadr.burpSpecDrift.model

import java.util.UUID

/**
 * Live data for a single SpecDrift tab. Persistence stores the parsed rows directly (as an
 * [ImportedSpec], with overrides and hit counts inline on each [EndpointRow]) plus the tab
 * metadata — see [toPersisted] / [fromPersisted]. No raw spec text is kept.
 */
class SpecTabData(
	val defaultName: String,
	val id: String = UUID.randomUUID().toString(),
) {
	/** Name set manually by the user; highest priority. */
	private var userName: String? = null

	/** The displayed tab name, by priority: user > spec title > default number. */
	val name: String get() = userName ?: importedSpec?.title ?: defaultName

	/** The imported spec (info + endpoints), or null while nothing has been loaded. */
	var importedSpec: ImportedSpec? = null
		private set

	/** Where the spec came from (file path, URL, or "Sent from Burp"); null until loaded. */
	var source: String? = null
		private set

	val endpoints: List<EndpointRow> get() = importedSpec?.endpoints ?: emptyList()

	val hasLoadedSpec: Boolean get() = importedSpec != null

	/**
	 * How far into the proxy history this tab has already counted. Mutated only on the traffic
	 * tracker's single thread; read on the EDT when persisting. See docs/hit-counting.md.
	 */
	@Volatile
	var lastProxyId: Int = 0

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

	/** Invoked whenever persistent state changes, to trigger a save. */
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

	/** Called by views after changing a per-route override, to trigger a save. */
	fun notifyChanged() {
		onChanged?.invoke()
	}

	/** Records a successfully imported spec (replacing any previous one) and names the tab. */
	fun onSpecLoaded(imported: ImportedSpec, source: String) {
		this.importedSpec = imported
		this.source = source
		// New endpoints start with zero counts, so re-scan the whole proxy history for this tab.
		this.lastProxyId = 0
		rebuildSpecBases()
	}

	/**
	 * Applies a user edit. Ignored when the text is blank or unchanged from the current displayed
	 * name, so double-clicking without really changing it does not count as user-set.
	 */
	fun renameByUser(newName: String) {
		if (newName.isNotBlank() && newName != name) {
			userName = newName
			onChanged?.invoke()
		}
	}

	fun toPersisted(): PersistedTab = PersistedTab(
		id = id,
		defaultName = defaultName,
		userName = userName,
		source = source,
		baseOverride = _baseOverride,
		customBases = customBasesList(),
		spec = importedSpec,
		lastProxyId = lastProxyId,
	)

	private fun rebuildSpecBases() {
		specBases.clear()
		endpoints.forEach { row ->
			row.specBase?.let { specBases.add(it) }
			specBases.addAll(row.alternativeBases)
		}
	}

	companion object {
		/** Rebuilds a tab from its persisted form (no re-parse: rows are stored directly). */
		fun fromPersisted(persisted: PersistedTab): SpecTabData = SpecTabData(persisted.defaultName, persisted.id).apply {
			userName = persisted.userName
			source = persisted.source
			_baseOverride = persisted.baseOverride
			customBases.addAll(persisted.customBases)
			lastProxyId = persisted.lastProxyId
			persisted.spec?.let {
				importedSpec = it
				rebuildSpecBases()
			}
		}
	}
}
