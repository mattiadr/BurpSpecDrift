package com.mattiadr.burpSpecDrift.runtime

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.ImportedSpec
import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Counts, per endpoint, how many matching requests have been seen, split into Proxy / Repeater /
 * Other hits. See `docs/hit-counting.md` for the full design, limitations and assumptions.
 *
 * - Repeater/Other are counted live via the [HttpHandler] (lost while unloaded).
 * - Proxy is counted live via the handler too, but also backfilled from the persisted proxy history
 *   (keyed by the per-tab offset `lastProxyId`), so traffic seen while unloaded is recovered.
 *
 * All counter and offset mutations run on a single-threaded executor, so counters stay plain ints
 * with no locking; the UI is refreshed via [onCountsChanged] (coalesced on the EDT by the caller).
 */
class TrafficTracker(private val tabsProvider: () -> List<SpecTabData>) {

	/** Invoked (off the EDT) after counts change, so the owner can repaint and mark state dirty. */
	var onCountsChanged: (() -> Unit)? = null

	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "SpecDrift-traffic").apply { isDaemon = true }
	}

	/** Compiled routes per tab id, with the spec they were built from (to detect reloads). */
	private val compiledByTab = HashMap<String, Pair<ImportedSpec, List<CompiledRoute>>>()

	/** Memoized base parsing, reused across a whole scan (executor thread only). */
	private val baseCache = HashMap<String, ParsedBase>()

	/**
	 * Backfills every loaded tab from the proxy history, then registers the HTTP handler. Registering
	 * after the backfill avoids counting a request both in the history snapshot and live; the tiny
	 * window between the snapshot and registration may drop a request (accepted, see docs).
	 */
	fun start() {
		executor.submit {
			backfill()
			AppContext.api.http().registerHttpHandler(handler)
		}
	}

	/** A spec was (re)loaded into a tab: its offset was reset, so re-scan the whole history for it. */
	fun onSpecLoaded() {
		executor.submit { backfill() }
	}

	fun onTabRemoved(tab: SpecTabData) {
		executor.submit { compiledByTab.remove(tab.id) }
	}

	/**
	 * Resets one tab's proxy hits to zero and recomputes them from the proxy history with the tab's
	 * current (possibly overridden) bases. Repeater/Other counts are untouched. Useful after a base
	 * change, since overrides only affect future live matching.
	 */
	fun recalculateProxyHits(tab: SpecTabData) {
		executor.submit {
			tab.importedSpec?.endpoints?.forEach { it.proxyHits = 0 }
			tab.lastProxyId = 0
			backfill()
			onCountsChanged?.invoke() // ensure the cleared counts repaint even if nothing matched
		}
	}

	/** On unload: pin each tab's offset to the exact current max id, correcting any derived drift. */
	fun onUnload() {
		runCatching {
			executor.submit {
				val loaded = tabsProvider().filter { it.hasLoadedSpec }
				if (loaded.isNotEmpty()) {
					val maxId = AppContext.api.proxy().history().lastOrNull()?.id() ?: 0
					loaded.forEach { it.lastProxyId = maxOf(it.lastProxyId, maxId) }
				}
			}.get(2, TimeUnit.SECONDS)
		}
		executor.shutdown()
	}

	private val handler = object : HttpHandler {
		override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
			val counter = when (request.toolSource().toolType()) {
				ToolType.PROXY -> Counter.PROXY
				ToolType.REPEATER -> Counter.REPEATER
				else -> Counter.OTHER
			}
			val seen = SeenRequest(
				method = request.method(),
				host = request.httpService().host(),
				port = request.httpService().port(),
				path = request.pathWithoutQuery(),
			)
			// Guard against the executor being shut down during unload while the handler still fires.
			runCatching { executor.submit { onLiveRequest(counter, seen) } }
			return RequestToBeSentAction.continueWith(request)
		}

		override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction =
			ResponseReceivedAction.continueWith(response)
	}

	private fun onLiveRequest(counter: Counter, seen: SeenRequest) {
		val loaded = tabsProvider().filter { it.hasLoadedSpec }
		if (loaded.isEmpty()) return
		var changed = false
		for (tab in loaded) {
			if (increment(tab, seen, counter)) changed = true
			// Derived offset: assume one proxy request produces one new history entry.
			if (counter == Counter.PROXY) tab.lastProxyId += 1
		}
		// Proxy requests advance the offset even without a match, so persist that too.
		if (changed || counter == Counter.PROXY) onCountsChanged?.invoke()
	}

	/**
	 * Scans the proxy history once and counts, for each loaded tab, the entries past its offset.
	 * Fast path (contiguous ids, no deletions): start from the shared frontier; otherwise fall back
	 * to a per-entry id check over the whole list. Afterwards every tab's offset is moved to the max.
	 */
	private fun backfill() {
		val loaded = tabsProvider().filter { it.hasLoadedSpec }
		if (loaded.isEmpty()) return
		val history = AppContext.api.proxy().history()
		if (history.isEmpty()) return

		val minFrontier = loaded.minOf { it.lastProxyId }
		val startIndex = fastStartIndex(history, minFrontier)
		baseCache.clear()
		var changed = false
		for (i in startIndex until history.size) {
			val entry = history[i]
			val id = entry.id()
			val seen = entry.toSeenRequest()
			for (tab in loaded) {
				if (id > tab.lastProxyId && increment(tab, seen, Counter.PROXY)) changed = true
			}
		}
		val maxId = history.last().id()
		val offsetAdvanced = loaded.any { it.lastProxyId < maxId }
		loaded.forEach { it.lastProxyId = maxOf(it.lastProxyId, maxId) }
		if (changed || offsetAdvanced) onCountsChanged?.invoke()
	}

	/** Index of the first history entry past [frontier] when ids are contiguous, else 0 (full scan). */
	private fun fastStartIndex(history: List<ProxyHttpRequestResponse>, frontier: Int): Int {
		if (frontier <= 0) return 0
		if (history.size < frontier) return 0 // shrank: entries were deleted
		if (history[frontier - 1].id() != frontier) return 0 // ids not contiguous
		return frontier
	}

	/** Matches [seen] against [tab]'s routes and bumps the right counter; returns true if it hit. */
	private fun increment(tab: SpecTabData, seen: SeenRequest, counter: Counter): Boolean {
		val routes = compiledRoutes(tab)
		val match = RouteMatcher.bestMatch(seen, routes, tab.baseOverride, baseCache) ?: return false
		when (counter) {
			Counter.PROXY -> match.row.proxyHits++
			Counter.REPEATER -> match.row.repeaterHits++
			Counter.OTHER -> match.row.otherHits++
		}
		return true
	}

	/** Compiled routes for a tab, rebuilt when the tab's spec instance changes. */
	private fun compiledRoutes(tab: SpecTabData): List<CompiledRoute> {
		val spec = tab.importedSpec ?: return emptyList()
		val cached = compiledByTab[tab.id]
		if (cached != null && cached.first === spec) return cached.second
		val compiled = spec.endpoints.map(RouteMatcher::compile)
		compiledByTab[tab.id] = spec to compiled
		return compiled
	}

	private fun ProxyHttpRequestResponse.toSeenRequest(): SeenRequest {
		val request = request()
		val httpService = request.httpService()
		return SeenRequest(request.method(), httpService.host(), httpService.port(), request.pathWithoutQuery())
	}

	private enum class Counter { PROXY, REPEATER, OTHER }
}
