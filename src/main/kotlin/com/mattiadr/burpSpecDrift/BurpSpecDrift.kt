package com.mattiadr.burpSpecDrift

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.ui.menu.Menu
import burp.api.montoya.ui.menu.MenuItem
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.gui.SpecDriftContextMenu
import com.mattiadr.burpSpecDrift.gui.SpecDriftTab

@Suppress("unused")
class BurpSpecDrift : BurpExtension {

	override fun initialize(api: MontoyaApi) {
		api.extension().setName("SpecDrift")
		AppContext.init(api)

		val tab = SpecDriftTab()
		api.userInterface().registerSuiteTab("SpecDrift", tab.component)
		api.userInterface().registerContextMenuItemsProvider(SpecDriftContextMenu(tab))

		// TODO debug, remove this
		api.userInterface().menuBar().registerMenu(Menu.menu("SpecDrift").withMenuItems(
			MenuItem.basicMenuItem("Inspect Persisted").withAction {
				val logging = api.logging()
				logging.logToOutput("=== SpecDrift persisted extensionData ===")
				dumpPersisted(api.persistence().extensionData(), logging, "")
			}))
	}

}

/** TODO Debug: recursively logs the raw contents of a [PersistedObject], without any parsing. */
private fun dumpPersisted(po: PersistedObject, logging: Logging, indent: String) {
	po.stringKeys().forEach { logging.logToOutput("$indent$it (string) = ${po.getString(it).replace("\n", "\\n")}") }
	po.integerKeys().forEach { logging.logToOutput("$indent$it (int) = ${po.getInteger(it)}") }
	po.longKeys().forEach { logging.logToOutput("$indent$it (long) = ${po.getLong(it)}") }
	po.booleanKeys().forEach { logging.logToOutput("$indent$it (bool) = ${po.getBoolean(it)}") }
	po.byteArrayKeys().forEach { logging.logToOutput("$indent$it (bytes) = ${po.getByteArray(it).length()} bytes") }
	po.stringListKeys().forEach { logging.logToOutput("$indent$it (stringList) = ${po.getStringList(it)?.toList()}") }
	po.integerListKeys().forEach { logging.logToOutput("$indent$it (intList) = ${po.getIntegerList(it)?.toList()}") }
	po.longListKeys().forEach { logging.logToOutput("$indent$it (longList) = ${po.getLongList(it)?.toList()}") }
	po.booleanListKeys().forEach { logging.logToOutput("$indent$it (boolList) = ${po.getBooleanList(it)?.toList()}") }
	po.childObjectKeys().forEach { key ->
		logging.logToOutput("$indent$key (object):")
		po.getChildObject(key)?.let { dumpPersisted(it, logging, "$indent    ") }
	}
}
