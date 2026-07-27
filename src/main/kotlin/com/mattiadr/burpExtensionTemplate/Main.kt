package com.mattiadr.burpExtensionTemplate

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging

@Suppress("unused")
class Main : BurpExtension {

	override fun initialize(api: MontoyaApi) {
		api.extension().setName("ExtensionTemplate")
		AppContext.init(api)

		AppContext.logging.logToOutput("Hello World!")
	}

}

object AppContext {
	lateinit var api: MontoyaApi private set
	lateinit var logging: Logging private set

	fun init(api: MontoyaApi) {
		this.api = api
		this.logging = api.logging()
	}
}
