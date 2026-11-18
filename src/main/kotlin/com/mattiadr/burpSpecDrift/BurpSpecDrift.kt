package com.mattiadr.burpSpecDrift

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
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
	}

}
