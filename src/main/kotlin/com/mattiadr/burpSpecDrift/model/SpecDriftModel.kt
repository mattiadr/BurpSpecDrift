package com.mattiadr.burpSpecDrift.model

/**
 * Live application state for SpecDrift: the ordered set of spec tabs. GUI-agnostic,
 * so it can later be serialized to Burp's persistence API.
 */
class SpecDriftModel {

	private val mutableTabs = mutableListOf<SpecTabData>()
	val tabs: List<SpecTabData> get() = mutableTabs

	/** Running counter for default tab names ("1", "2", ...). */
	private var tabCounter = 0

	/** Creates and appends a tab with the next default name. */
	fun newTab(): SpecTabData {
		tabCounter++
		return SpecTabData(tabCounter.toString()).also { mutableTabs.add(it) }
	}

	fun remove(tab: SpecTabData) {
		mutableTabs.remove(tab)
	}

	/** The first tab, in order, that has not loaded a spec yet. */
	fun firstEmptyTab(): SpecTabData? = mutableTabs.firstOrNull { !it.hasLoadedSpec }
}
