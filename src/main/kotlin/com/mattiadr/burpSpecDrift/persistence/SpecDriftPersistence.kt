package com.mattiadr.burpSpecDrift.persistence

import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.PersistedTab
import com.mattiadr.burpSpecDrift.model.SpecTabData
import burp.api.montoya.persistence.PersistedList
import kotlinx.serialization.json.Json

/**
 * Maps the SpecDrift tabs to/from Burp's project-file persistence. Each tab is stored as a JSON
 * string (the parsed rows with overrides/hits inline, plus metadata) keyed by its id; an ordered
 * id list keeps the tab order. No raw spec text and no re-parse on restore.
 */
class SpecDriftPersistence {

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val root get() = AppContext.persistence.extensionData()

	/** Rebuilds the tab data from the project file. */
	fun restore(): List<SpecTabData> {
		val order = root.getStringList(KEY_ORDER) ?: return emptyList()
		return order.mapNotNull { id ->
			val stored = root.getString(id) ?: return@mapNotNull null
			runCatching { SpecTabData.fromPersisted(json.decodeFromString(PersistedTab.serializer(), stored)) }.getOrNull()
		}
	}

	/** Persists the order of the current tabs. */
	fun saveOrder(tabs: List<SpecTabData>) {
		root.setStringList(KEY_ORDER, stringList(tabs.map { it.id }))
	}

	/** Persists one tab (rows, overrides, hits, metadata) as JSON. */
	fun saveTab(data: SpecTabData) {
		root.setString(data.id, json.encodeToString(PersistedTab.serializer(), data.toPersisted()))
	}

	fun removeTab(data: SpecTabData) {
		root.deleteString(data.id)
	}

	private fun stringList(values: List<String>): PersistedList<String> =
		PersistedList.persistedStringList().apply { addAll(values) }

	companion object {
		private const val KEY_ORDER = "tabOrder"
	}
}
