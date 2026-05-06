package com.mattiadr.burpExtensionTemplate

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi

@Suppress("unused")
class Main : BurpExtension {

	override fun initialize(api: MontoyaApi) {
		api.extension().setName("ExtensionTemplate")
		AppContext.init(api)

		AppContext.logging.logToOutput("Hello World!")
	}

}
