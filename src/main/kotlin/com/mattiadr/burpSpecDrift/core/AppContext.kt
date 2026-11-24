package com.mattiadr.burpSpecDrift.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.extension.Extension
import burp.api.montoya.http.Http
import burp.api.montoya.intruder.Intruder
import burp.api.montoya.logging.Logging
import burp.api.montoya.organizer.Organizer
import burp.api.montoya.persistence.Persistence
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.repeater.Repeater
import burp.api.montoya.ui.UserInterface

/**
 * Process-wide access to the Montoya API. The individual service handles are resolved once at
 * [init] and cached, so call sites use `AppContext.logging` rather than `AppContext.api.logging()`.
 */
object AppContext {
	lateinit var api: MontoyaApi private set
	lateinit var extension: Extension private set
	lateinit var http: Http private set
	lateinit var intruder: Intruder private set
	lateinit var logging: Logging private set
	lateinit var organizer: Organizer private set
	lateinit var persistence: Persistence private set
	lateinit var proxy: Proxy private set
	lateinit var repeater: Repeater private set
	lateinit var userInterface: UserInterface private set

	fun init(api: MontoyaApi) {
		this.api = api
		this.extension = api.extension()
		this.http = api.http()
		this.intruder = api.intruder()
		this.logging = api.logging()
		this.organizer = api.organizer()
		this.persistence = api.persistence()
		this.proxy = api.proxy()
		this.repeater = api.repeater()
		this.userInterface = api.userInterface()
	}
}
