package com.mattiadr.burpSpecDrift.persistence

import burp.api.montoya.persistence.PersistedList
import burp.api.montoya.persistence.PersistedObject
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.SpecTabData
import com.mattiadr.burpSpecDrift.spec.SpecParser

/**
 * Maps the SpecDrift tabs to/from Burp's project-file persistence. Each tab is stored as a child
 * [PersistedObject] keyed by its id; an ordered id list keeps the tab order. The resolved spec is
 * stored as text and re-parsed on restore, while overrides and hit counts are keyed by route.
 *
 * Writes are split so the (large) spec text is only persisted when it actually changes (on load):
 * [saveTab] with includeSpec=false updates only the small fields.
 */
class SpecDriftPersistence {

	private val root: PersistedObject get() = AppContext.api.persistence().extensionData()

	/** Rebuilds the tab data from the project file, re-parsing each stored spec. */
	fun restore(): List<SpecTabData> {
		val order = root.getStringList(KEY_ORDER) ?: return emptyList()
		return order.mapNotNull { id -> restoreTab(id, root.getChildObject(id) ?: return@mapNotNull null) }
	}

	private fun restoreTab(id: String, po: PersistedObject): SpecTabData? {
		val defaultName = po.getString(KEY_DEFAULT_NAME) ?: return null
		val data = SpecTabData(defaultName, id)
		data.restoreMeta(
			userName = po.getString(KEY_USER_NAME),
			baseOverride = po.getString(KEY_BASE_OVERRIDE),
			customBases = po.getStringList(KEY_CUSTOM_BASES)?.toList() ?: emptyList(),
		)

		val specText = po.getString(KEY_SPEC_TEXT)
		if (specText != null) {
			val openAPI = SpecParser.parseContent(specText).openAPI
			if (openAPI != null) {
				data.onSpecLoaded(openAPI, po.getString(KEY_SOURCE) ?: "", specText)
				data.applyRouteState(readStringMap(po, KEY_ROUTE_OVERRIDES), readIntMap(po, KEY_ROUTE_HITS))
			}
		}
		return data
	}

	/** Persists the order of the current tabs. */
	fun saveOrder(tabs: List<SpecTabData>) {
		root.setStringList(KEY_ORDER, stringList(tabs.map { it.id }))
	}

	/** Persists a tab. [includeSpec] also writes the (large) spec text and source. */
	fun saveTab(data: SpecTabData, includeSpec: Boolean) {
		val po = root.getChildObject(data.id) ?: PersistedObject.persistedObject()
		po.setString(KEY_DEFAULT_NAME, data.defaultName)
		setOrDelete(po, KEY_USER_NAME, data.userNameValue)
		setOrDelete(po, KEY_BASE_OVERRIDE, data.baseOverride)
		po.setStringList(KEY_CUSTOM_BASES, stringList(data.customBasesList()))
		po.setChildObject(KEY_ROUTE_OVERRIDES, stringMap(data.routeOverrides()))
		po.setChildObject(KEY_ROUTE_HITS, intMap(data.routeHits()))
		if (includeSpec) {
			setOrDelete(po, KEY_SPEC_TEXT, data.specText)
			setOrDelete(po, KEY_SOURCE, data.source)
		}
		root.setChildObject(data.id, po)
	}

	/** Persists only the hit counts of a tab (used by the periodic flush). */
	fun saveHits(data: SpecTabData) {
		val po = root.getChildObject(data.id) ?: return
		po.setChildObject(KEY_ROUTE_HITS, intMap(data.routeHits()))
		root.setChildObject(data.id, po)
	}

	fun removeTab(data: SpecTabData) {
		root.deleteChildObject(data.id)
	}

	private fun setOrDelete(po: PersistedObject, key: String, value: String?) {
		if (value != null) po.setString(key, value) else po.deleteString(key)
	}

	private fun stringList(values: List<String>): PersistedList<String> =
		PersistedList.persistedStringList().apply { addAll(values) }

	private fun stringMap(map: Map<String, String>): PersistedObject =
		PersistedObject.persistedObject().apply { map.forEach { (k, v) -> setString(k, v) } }

	private fun intMap(map: Map<String, Int>): PersistedObject =
		PersistedObject.persistedObject().apply { map.forEach { (k, v) -> setInteger(k, v) } }

	private fun readStringMap(po: PersistedObject, key: String): Map<String, String> {
		val child = po.getChildObject(key) ?: return emptyMap()
		return child.stringKeys().associateWith { child.getString(it) }
	}

	private fun readIntMap(po: PersistedObject, key: String): Map<String, Int> {
		val child = po.getChildObject(key) ?: return emptyMap()
		return child.integerKeys().associateWith { child.getInteger(it) }
	}

	companion object {
		private const val KEY_ORDER = "tabOrder"
		private const val KEY_DEFAULT_NAME = "defaultName"
		private const val KEY_USER_NAME = "userName"
		private const val KEY_SPEC_TEXT = "specText"
		private const val KEY_SOURCE = "source"
		private const val KEY_BASE_OVERRIDE = "baseOverride"
		private const val KEY_CUSTOM_BASES = "customBases"
		private const val KEY_ROUTE_OVERRIDES = "routeOverrides"
		private const val KEY_ROUTE_HITS = "routeHits"
	}
}
