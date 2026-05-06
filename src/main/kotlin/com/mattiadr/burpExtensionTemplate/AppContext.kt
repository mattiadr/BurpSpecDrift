package com.mattiadr.burpExtensionTemplate

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging

object AppContext {
	lateinit var api: MontoyaApi private set
	lateinit var logging: Logging private set

	fun init(api: MontoyaApi) {
		this.api = api
		this.logging = api.logging()
	}
}
