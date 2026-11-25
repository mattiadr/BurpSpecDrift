package com.mattiadr.burpSpecDrift.spec.postman

import com.mattiadr.burpSpecDrift.model.EndpointRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PostmanImporter] — collapsing requests per (method, path), path normalization,
 * variable resolution, body/auth mapping. See docs/spec-import-mapping.md.
 */
class PostmanImporterTest {

	private val collection = """
		{
		  "info": { "_postman_id": "x", "name": "Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
		  "auth": { "type": "bearer", "bearer": [ { "key": "token", "value": "{{token}}" } ] },
		  "variable": [ { "key": "baseUrl", "value": "https://api.example.com" } ],
		  "item": [
		    { "name": "Users", "item": [
		      { "name": "List", "request": { "method": "GET",
		        "url": { "host": ["{{baseUrl}}"], "path": ["users"], "query": [ { "key": "limit", "value": "10" } ] } } },
		      { "name": "Create admin", "request": { "method": "POST",
		        "header": [ { "key": "Content-Type", "value": "application/json" } ],
		        "body": { "mode": "raw", "raw": "{\"role\":\"admin\"}", "options": { "raw": { "language": "json" } } },
		        "url": { "host": ["{{baseUrl}}"], "path": ["users"] } } },
		      { "name": "Create guest", "request": { "method": "POST",
		        "body": { "mode": "raw", "raw": "{\"role\":\"guest\"}", "options": { "raw": { "language": "json" } } },
		        "url": { "host": ["{{baseUrl}}"], "path": ["users"] } } },
		      { "name": "Get by id", "request": { "method": "GET",
		        "url": { "host": ["{{baseUrl}}"], "path": ["users", ":id"], "variable": [ { "key": "id", "value": "42" } ] } } }
		    ] },
		    { "name": "Login", "request": { "method": "POST", "auth": { "type": "noauth" },
		      "body": { "mode": "urlencoded", "urlencoded": [ { "key": "u", "value": "a" }, { "key": "p", "value": "b" } ] },
		      "url": { "host": ["{{baseUrl}}"], "path": ["login"] } } }
		  ]
		}
	""".trimIndent()

	private val spec = PostmanImporter.import(collection)

	private fun row(method: String, path: String): EndpointRow =
		spec.endpoints.first { it.method == method && it.path == path }

	@Test
	fun `title and format`() {
		assertEquals("Demo", spec.title)
		assertEquals("Postman 2.1.0", spec.format)
	}

	@Test
	fun `requests on the same method and path collapse into one row`() {
		val posts = spec.endpoints.filter { it.method == "POST" && it.path == "/users" }
		assertEquals(1, posts.size)
	}

	@Test
	fun `collapsed bodies are kept as separate examples with names`() {
		val body = assertNotNull(row("POST", "/users").requestBody)
		assertEquals(2, body.contents.size)
		assertTrue(body.contents.all { it.mediaType == "application/json" })
		assertEquals(listOf("Create admin", "Create guest"), body.contents.map { it.name })
		assertEquals("{\"role\":\"admin\"}", body.contents[0].example)
	}

	@Test
	fun `path params are normalized to braces and captured`() {
		val get = row("GET", "/users/{id}")
		val id = assertNotNull(get.parameters.firstOrNull { it.location == "path" && it.name == "id" })
		assertTrue(id.required)
		assertEquals("42", id.example)
	}

	@Test
	fun `base is resolved from a collection variable`() {
		assertEquals("https://api.example.com", row("GET", "/users").specBase)
	}

	@Test
	fun `query params are imported`() {
		val limit = assertNotNull(row("GET", "/users").parameters.firstOrNull { it.location == "query" && it.name == "limit" })
		assertEquals("10", limit.example)
	}

	@Test
	fun `auth is inherited from the collection`() {
		assertEquals(listOf("bearer"), row("GET", "/users").security)
	}

	@Test
	fun `noauth maps to an explicitly empty requirement`() {
		val requirements = assertNotNull(row("POST", "/login").securityRequirements)
		assertTrue(requirements.isEmpty())
	}

	@Test
	fun `urlencoded body is reconstructed`() {
		val body = assertNotNull(row("POST", "/login").requestBody)
		assertEquals("application/x-www-form-urlencoded", body.contents.single().mediaType)
		assertEquals("u=a&p=b", body.contents.single().example)
	}

	@Test
	fun `absent base stays null when the variable is unknown`() {
		val noVar = PostmanImporter.import(
			"""{ "info": { "name": "n", "schema": "v2.1.0" }, "item": [
			   { "name": "x", "request": { "method": "GET", "url": { "host": ["{{unknownHost}}"], "path": ["ping"] } } } ] }""",
		)
		assertNull(noVar.endpoints.single().specBase)
		assertEquals("/ping", noVar.endpoints.single().path)
	}
}
