package com.mattiadr.burpSpecDrift.runtime

import com.mattiadr.burpSpecDrift.model.EndpointRow
import java.net.URI

/** A single observed request, reduced to the fields we match on (query already stripped). */
data class SeenRequest(
	val method: String,
	val host: String,
	val port: Int,
	val path: String,
)

/** A route's base URL split into the parts used for matching. */
data class ParsedBase(
	val host: String?,
	/** An explicitly declared, non-default port, or null (then the port is ignored). */
	val explicitPort: Int?,
	val basePath: String,
)

/**
 * A route (one [EndpointRow]) precompiled for matching: its method, its path template turned into a
 * regex (relative to the server base-path), and a specificity used to break ties between several
 * matching routes. The base is NOT baked in: it is resolved per request from the row's effective
 * base, so overrides changing at runtime need no recompilation.
 */
class CompiledRoute(
	val row: EndpointRow,
	val method: String,
	val pathRegex: Regex,
	val wildcardCount: Int,
	val literalLength: Int,
) {
	/** More specific = fewer wildcards, then more literal characters. */
	fun isMoreSpecificThan(other: CompiledRoute): Boolean =
		wildcardCount < other.wildcardCount ||
			(wildcardCount == other.wildcardCount && literalLength > other.literalLength)
}

/**
 * Matches observed requests against compiled routes. See `docs/hit-counting.md` for the rules:
 * method + host + path (port ignored unless the base declares a non-default one; scheme ignored);
 * a route with no base matches on path + method only; among several matches the most specific wins.
 */
object RouteMatcher {

	private val DEFAULT_PORTS = setOf(80, 443)

	fun compile(row: EndpointRow): CompiledRoute = CompiledRoute(
		row = row,
		method = row.method,
		pathRegex = templateToRegex(row.path),
		wildcardCount = row.path.count { it == '{' },
		literalLength = row.path.count { it != '{' && it != '}' },
	)

	/**
	 * The most specific route in [routes] that [seen] matches, or null. [globalOverride] and each
	 * row's own override decide the effective base; [baseCache] memoizes base parsing across calls.
	 */
	fun bestMatch(
		seen: SeenRequest,
		routes: List<CompiledRoute>,
		globalOverride: String?,
		baseCache: MutableMap<String, ParsedBase>,
	): CompiledRoute? {
		var best: CompiledRoute? = null
		for (route in routes) {
			if (!route.method.equals(seen.method, ignoreCase = true)) continue
			if (!matchesPathAndHost(route, seen, globalOverride, baseCache)) continue
			if (best == null || route.isMoreSpecificThan(best)) best = route
		}
		return best
	}

	private fun matchesPathAndHost(
		route: CompiledRoute,
		seen: SeenRequest,
		globalOverride: String?,
		baseCache: MutableMap<String, ParsedBase>,
	): Boolean {
		val base = route.row.effectiveBase(globalOverride)
		if (base.isNullOrBlank()) {
			// No base known: match on path + method only, any host.
			return route.pathRegex.matches(seen.path)
		}
		val parsed = baseCache.getOrPut(base) { parseBase(base) }
		if (parsed.host == null) return route.pathRegex.matches(seen.path)
		if (!parsed.host.equals(seen.host, ignoreCase = true)) return false
		if (parsed.explicitPort != null && parsed.explicitPort !in DEFAULT_PORTS && parsed.explicitPort != seen.port) return false
		val relative = stripBasePath(seen.path, parsed.basePath) ?: return false
		return route.pathRegex.matches(relative)
	}

	/** Turns an OpenAPI path template into an anchored regex (`{param}` -> one path segment). */
	fun templateToRegex(path: String): Regex {
		val sb = StringBuilder("^")
		val literal = StringBuilder()
		fun flushLiteral() {
			if (literal.isNotEmpty()) {
				sb.append(Regex.escape(literal.toString()))
				literal.clear()
			}
		}
		var i = 0
		while (i < path.length) {
			if (path[i] == '{') {
				val end = path.indexOf('}', i)
				if (end > i) {
					flushLiteral()
					sb.append("[^/]+")
					i = end + 1
					continue
				}
			}
			literal.append(path[i])
			i++
		}
		flushLiteral()
		sb.append('$')
		return Regex(sb.toString())
	}

	/** Splits a base URL (possibly without scheme) into host, explicit non-default port, base-path. */
	fun parseBase(base: String): ParsedBase = try {
		val uri = if ("://" in base) URI(base) else URI("//$base")
		ParsedBase(
			host = uri.host?.takeIf { it.isNotBlank() },
			explicitPort = uri.port.takeIf { it != -1 },
			basePath = (uri.rawPath ?: "").trimEnd('/'),
		)
	} catch (_: Exception) {
		ParsedBase(null, null, "")
	}

	/** Removes the server base-path prefix from a request path, or null if it isn't a prefix. */
	private fun stripBasePath(requestPath: String, basePath: String): String? {
		if (basePath.isEmpty()) return requestPath
		if (!requestPath.startsWith(basePath)) return null
		val rest = requestPath.substring(basePath.length)
		return when {
			rest.isEmpty() -> "/"
			rest.startsWith("/") -> rest
			else -> null // base-path matched only mid-segment
		}
	}
}
