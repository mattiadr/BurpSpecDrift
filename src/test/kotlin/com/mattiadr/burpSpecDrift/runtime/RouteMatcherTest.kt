package com.mattiadr.burpSpecDrift.runtime

import com.mattiadr.burpSpecDrift.model.EndpointRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RouteMatcher] — the pure matching logic behind the hit counters. Mirrors the
 * rules in docs/hit-counting.md and the scenarios exercised by scripts/hit_counting_test.py.
 */
class RouteMatcherTest {

	private fun row(method: String, path: String, base: String?) =
		EndpointRow(method = method, path = path, specBase = base)

	private fun compile(method: String, path: String, base: String?) =
		RouteMatcher.compile(row(method, path, base))

	private fun seen(method: String, host: String, port: Int, path: String) =
		SeenRequest(method, host, port, path)

	private fun match(seen: SeenRequest, vararg routes: CompiledRoute): CompiledRoute? =
		RouteMatcher.bestMatch(seen, routes.toList(), globalOverride = null, baseCache = HashMap())

	// --- templateToRegex ---------------------------------------------------------------------

	@Test
	fun `param matches a single segment only`() {
		val regex = RouteMatcher.templateToRegex("/users/{id}")
		assertTrue(regex.matches("/users/5"))
		assertTrue(regex.matches("/users/abc"))
		assertTrue(!regex.matches("/users"))
		assertTrue(!regex.matches("/users/5/posts"))
		assertTrue(!regex.matches("/users/"))
	}

	@Test
	fun `literal and param do not clash within a path`() {
		val regex = RouteMatcher.templateToRegex("/users/{id}/posts/{postId}")
		assertTrue(regex.matches("/users/5/posts/9"))
		assertTrue(!regex.matches("/users/5/posts"))
		assertTrue(!regex.matches("/users/5/posts/9/comments"))
	}

	@Test
	fun `partial segment templating keeps the literal suffix`() {
		val regex = RouteMatcher.templateToRegex("/files/{name}.json")
		assertTrue(regex.matches("/files/report.json"))
		assertTrue(!regex.matches("/files/report.txt"))
		assertTrue(!regex.matches("/files/report"))
	}

	@Test
	fun `regex special characters in the literal are escaped`() {
		val regex = RouteMatcher.templateToRegex("/a.b/{id}")
		assertTrue(regex.matches("/a.b/1"))
		assertTrue(!regex.matches("/axb/1"))
	}

	@Test
	fun `matching is case sensitive and anchored`() {
		val regex = RouteMatcher.templateToRegex("/health")
		assertTrue(regex.matches("/health"))
		assertTrue(!regex.matches("/Health"))
		assertTrue(!regex.matches("/health/"))
		assertTrue(!regex.matches("/health/extra"))
	}

	// --- parseBase ---------------------------------------------------------------------------

	@Test
	fun `parseBase splits scheme host port and base path`() {
		val parsed = RouteMatcher.parseBase("http://localhost:8000/api/v1")
		assertEquals("localhost", parsed.host)
		assertEquals(8000, parsed.explicitPort)
		assertEquals("/api/v1", parsed.basePath)
	}

	@Test
	fun `parseBase handles a missing scheme`() {
		val parsed = RouteMatcher.parseBase("example.com/api")
		assertEquals("example.com", parsed.host)
		assertNull(parsed.explicitPort)
		assertEquals("/api", parsed.basePath)
	}

	@Test
	fun `parseBase leaves the default port implicit`() {
		val parsed = RouteMatcher.parseBase("https://api.example.com")
		assertEquals("api.example.com", parsed.host)
		assertNull(parsed.explicitPort)
		assertEquals("", parsed.basePath)
	}

	// --- bestMatch: host / port / base path --------------------------------------------------

	@Test
	fun `base path is stripped before matching the template`() {
		val route = compile("GET", "/users/{id}", "http://localhost:8000/api/v1")
		assertEquals(route.row, match(seen("GET", "localhost", 8000, "/api/v1/users/5"), route)?.row)
		// Missing base-path prefix -> no match.
		assertNull(match(seen("GET", "localhost", 8000, "/users/5"), route))
	}

	@Test
	fun `host must match the base host`() {
		val route = compile("GET", "/legacy", "http://127.0.0.1:8000")
		assertEquals(route.row, match(seen("GET", "127.0.0.1", 8000, "/legacy"), route)?.row)
		// Same path, different host string -> no match (127.0.0.1 != localhost).
		assertNull(match(seen("GET", "localhost", 8000, "/legacy"), route))
	}

	@Test
	fun `non-default port must match, default port is ignored`() {
		val explicit = compile("GET", "/health", "http://localhost:8000/api/v1")
		assertNull(match(seen("GET", "localhost", 9999, "/api/v1/health"), explicit))
		assertEquals(explicit.row, match(seen("GET", "localhost", 8000, "/api/v1/health"), explicit)?.row)

		val defaultPort = compile("GET", "/health", "http://api.example.com")
		// Port differs but base declares no explicit non-default port -> ignored.
		assertEquals(defaultPort.row, match(seen("GET", "api.example.com", 12345, "/health"), defaultPort)?.row)
	}

	@Test
	fun `method must match`() {
		val route = compile("GET", "/users/{id}", "http://localhost:8000/api/v1")
		assertNull(match(seen("POST", "localhost", 8000, "/api/v1/users/5"), route))
	}

	@Test
	fun `query string is not part of the matched path`() {
		// SeenRequest.path is already query-free; a stray query must not creep into matching.
		val route = compile("GET", "/search", "http://localhost:8000/api/v1")
		assertEquals(route.row, match(seen("GET", "localhost", 8000, "/api/v1/search"), route)?.row)
		assertNull(match(seen("GET", "localhost", 8000, "/api/v1/search?q=x"), route))
	}

	@Test
	fun `a route with no base matches path and method on any host`() {
		val route = compile("GET", "/anything/{id}", null)
		assertEquals(route.row, match(seen("GET", "whatever.example", 1234, "/anything/7"), route)?.row)
		assertNull(match(seen("GET", "whatever.example", 1234, "/other/7"), route))
	}

	// --- bestMatch: specificity --------------------------------------------------------------

	@Test
	fun `literal route wins over a templated one`() {
		val literal = compile("GET", "/users/me", "http://localhost:8000/api/v1")
		val templated = compile("GET", "/users/{id}", "http://localhost:8000/api/v1")
		assertEquals(
			literal.row,
			match(seen("GET", "localhost", 8000, "/api/v1/users/me"), templated, literal)?.row,
		)
		assertEquals(
			templated.row,
			match(seen("GET", "localhost", 8000, "/api/v1/users/123"), templated, literal)?.row,
		)
	}

	@Test
	fun `nested literal is more specific than a shorter template`() {
		val shallow = compile("GET", "/users/{id}", "http://localhost:8000/api/v1")
		val deep = compile("GET", "/users/{id}/posts/{postId}", "http://localhost:8000/api/v1")
		assertEquals(
			deep.row,
			match(seen("GET", "localhost", 8000, "/api/v1/users/5/posts/9"), shallow, deep)?.row,
		)
	}

	@Test
	fun `global override replaces the spec base for matching`() {
		val route = compile("GET", "/users/{id}", "http://localhost:8000/api/v1")
		val routes = listOf(route)
		// With an override to another host, localhost traffic no longer matches...
		assertNull(
			RouteMatcher.bestMatch(
				seen("GET", "localhost", 8000, "/api/v1/users/5"),
				routes,
				globalOverride = "http://other.example/api/v1",
				baseCache = HashMap(),
			),
		)
		// ...but traffic to the override host does.
		assertEquals(
			route.row,
			RouteMatcher.bestMatch(
				seen("GET", "other.example", 80, "/api/v1/users/5"),
				routes,
				globalOverride = "http://other.example/api/v1",
				baseCache = HashMap(),
			)?.row,
		)
	}
}
