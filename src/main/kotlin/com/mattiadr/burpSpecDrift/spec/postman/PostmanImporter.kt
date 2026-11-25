package com.mattiadr.burpSpecDrift.spec.postman

import com.mattiadr.burpSpecDrift.model.Body
import com.mattiadr.burpSpecDrift.model.ContentExample
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.ImportedSpec
import com.mattiadr.burpSpecDrift.model.Param
import com.mattiadr.burpSpecDrift.model.ResponseInfo
import com.mattiadr.burpSpecDrift.model.SecurityRequirement
import com.mattiadr.burpSpecDrift.model.SecurityScheme
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Imports a Postman Collection v2.1 into the generic [ImportedSpec]. Because a collection can hold
 * several requests on the same `(method, path)`, requests are **collapsed** into one [EndpointRow]
 * per `(method, path)`, each original request body kept as a separate example on that row (see
 * docs/spec-import-mapping.md). Values come verbatim from the collection (no schema generation);
 * `{{variables}}` are resolved against the collection variables ([VariableResolver]).
 */
object PostmanImporter {

	private val json = Json { ignoreUnknownKeys = true; isLenient = true }

	fun import(content: String): ImportedSpec {
		val collection = json.decodeFromString(PostmanCollection.serializer(), content)
		val resolver = VariableResolver(collection.variable)
		val rows = LinkedHashMap<String, RowBuilder>()
		flatten(collection.item, emptyList(), collection.auth, resolver, rows)
		return ImportedSpec(
			title = collection.info?.name,
			format = formatLabel(collection.info?.schema),
			endpoints = rows.values.map { it.build() },
		)
	}

	/** Walks the item tree depth-first, threading the folder path and the inherited auth down. */
	private fun flatten(
		items: List<PostmanItem>,
		folders: List<String>,
		inheritedAuth: PostmanAuth?,
		resolver: VariableResolver,
		rows: LinkedHashMap<String, RowBuilder>,
	) {
		for (item in items) {
			val children = item.item
			if (children != null) {
				flatten(children, folders + listOfNotNull(item.name?.takeIf { it.isNotBlank() }), item.auth ?: inheritedAuth, resolver, rows)
				continue
			}
			val request = parseRequest(item.request) ?: continue
			val endpoint = buildEndpoint(item, request, folders, inheritedAuth, resolver)
			val key = "${endpoint.method} ${endpoint.path}"
			rows.getOrPut(key) { RowBuilder(endpoint.method, endpoint.path) }.add(endpoint)
		}
	}

	/** A request can be a bare URL string or a full object. */
	private fun parseRequest(element: JsonElement?): PostmanRequest? = when (element) {
		is JsonPrimitive -> if (element.isString) PostmanRequest(method = "GET", url = element) else null
		is JsonObject -> json.decodeFromJsonElement(PostmanRequest.serializer(), element)
		else -> null
	}

	private fun buildEndpoint(
		item: PostmanItem,
		request: PostmanRequest,
		folders: List<String>,
		inheritedAuth: PostmanAuth?,
		resolver: VariableResolver,
	): ParsedEndpoint {
		val headers = parseHeaders(request.header)
		val url = parseUrl(request.url, resolver)
		val headerParams = headers.filter { !it.disabled && !it.key.isNullOrBlank() }.map { header ->
			Param(
				name = header.key!!,
				location = "header",
				required = false,
				description = descriptionText(header.description),
				example = resolver.resolve(header.value ?: ""),
			)
		}
		return ParsedEndpoint(
			method = (request.method ?: "GET").uppercase(),
			path = url.path,
			base = url.base,
			summary = item.name,
			description = descriptionText(request.description),
			tags = folders,
			security = mapAuth(request.auth ?: inheritedAuth),
			parameters = url.pathParams + url.queryParams + headerParams,
			body = parseBody(request.body, headers, item.name, resolver),
			responses = item.response.mapNotNull { parseResponse(it, resolver) },
		)
	}

	// --- auth ------------------------------------------------------------------------------------

	/** null = no auth declared anywhere (unknown); empty list = `noauth` (explicitly none). */
	private fun mapAuth(auth: PostmanAuth?): List<SecurityRequirement>? {
		val type = auth?.type ?: return null
		if (type == "noauth") return emptyList()
		return listOf(SecurityRequirement(listOf(SecurityScheme(name = type, type = type, detail = authDetail(auth)))))
	}

	private fun authDetail(auth: PostmanAuth): String? = when (auth.type) {
		"apikey" -> {
			val params = auth.apikey.associate { (it.key ?: "") to primitive(it.value) }
			params["key"]?.takeIf { it.isNotBlank() }?.let { "in ${params["in"]?.takeIf(String::isNotBlank) ?: "header"}: $it" }
		}
		"bearer" -> "bearer"
		"basic" -> "basic"
		else -> null
	}

	// --- body ------------------------------------------------------------------------------------

	private fun parseBody(body: PostmanBody?, headers: List<PostmanHeader>, exampleName: String?, resolver: VariableResolver): Body? {
		if (body == null || body.disabled) return null
		val content = when (body.mode) {
			"raw" -> ContentExample(rawMediaType(body, headers), body.raw?.let(resolver::resolve), exampleName)
			"urlencoded" -> ContentExample("application/x-www-form-urlencoded", encodeForm(body.urlencoded, resolver), exampleName)
			"graphql" -> ContentExample("application/json", body.graphql?.toString()?.let(resolver::resolve), exampleName)
			// multipart/binary bodies can't be synthesized faithfully: keep the media type, no example.
			"formdata" -> ContentExample("multipart/form-data", null, exampleName)
			"file" -> ContentExample("application/octet-stream", null, exampleName)
			else -> return null
		}
		return Body(required = false, contents = listOf(content))
	}

	private fun encodeForm(fields: List<PostmanField>, resolver: VariableResolver): String =
		fields.filter { !it.disabled && !it.key.isNullOrBlank() }
			.joinToString("&") { "${encode(resolver.resolve(it.key!!))}=${encode(resolver.resolve(it.value ?: ""))}" }

	/** Content type of a raw body: an explicit Content-Type header, then `options.raw.language`, else text. */
	private fun rawMediaType(body: PostmanBody, headers: List<PostmanHeader>): String =
		contentType(headers) ?: rawLanguageMediaType(body.options) ?: "text/plain"

	private fun rawLanguageMediaType(options: JsonObject?): String? {
		val language = (options?.get("raw") as? JsonObject)?.get("language")?.let { it as? JsonPrimitive }?.contentOrNull
		return when (language?.lowercase()) {
			"json" -> "application/json"
			"xml" -> "application/xml"
			"html" -> "text/html"
			"javascript" -> "application/javascript"
			"text" -> "text/plain"
			else -> null
		}
	}

	private fun contentType(headers: List<PostmanHeader>): String? =
		headers.firstOrNull { !it.disabled && it.key?.equals("Content-Type", ignoreCase = true) == true }?.value

	// --- responses -------------------------------------------------------------------------------

	private fun parseResponse(response: PostmanResponse, resolver: VariableResolver): ResponseInfo? {
		val statusCode = response.code?.toString() ?: response.status ?: return null
		val contents = response.body?.let {
			listOf(ContentExample(contentType(parseHeaders(response.header)) ?: "text/plain", resolver.resolve(it), response.name))
		} ?: emptyList()
		return ResponseInfo(statusCode = statusCode, description = response.status ?: response.name, contents = contents)
	}

	// --- url -------------------------------------------------------------------------------------

	private class UrlParts(val base: String?, val path: String, val queryParams: List<Param>, val pathParams: List<Param>)

	private fun parseUrl(element: JsonElement?, resolver: VariableResolver): UrlParts = when (element) {
		is JsonPrimitive -> if (element.isString) parseRawUrl(element.content, resolver) else EMPTY_URL
		is JsonObject -> parseUrlObject(element, resolver)
		else -> EMPTY_URL
	}

	private fun parseUrlObject(url: JsonObject, resolver: VariableResolver): UrlParts {
		val protocol = url.string("protocol")
		val host = segments(url["host"], ".")
		val port = url.string("port")
		val pathSegments = segments(url["path"], "/")
		val variables = decodeList(url["variable"], PostmanVariable.serializer())
			.filter { !it.key.isNullOrBlank() }.associate { it.key!! to primitive(it.value) }
		val queries = decodeList(url["query"], PostmanQuery.serializer())
		return UrlParts(
			base = buildBase(protocol, host, port, resolver),
			path = buildPath(pathSegments),
			queryParams = queries.filter { !it.disabled && !it.key.isNullOrBlank() }.map { query ->
				Param(query.key!!, "query", required = false, description = descriptionText(query.description), example = resolver.resolve(query.value ?: ""))
			},
			pathParams = pathParamNames(pathSegments).map { name ->
				Param(name, "path", required = true, example = resolver.resolve(variables[name] ?: ""))
			},
		)
	}

	/** Best-effort split of a raw URL string into base + path (+ query); used when there is no object form. */
	private fun parseRawUrl(raw: String, resolver: VariableResolver): UrlParts {
		val beforeQuery = raw.substringBefore('#').substringBefore('?')
		val queryString = raw.substringBefore('#').substringAfter('?', "")
		val schemeIndex = beforeQuery.indexOf("://")
		val afterScheme = if (schemeIndex >= 0) beforeQuery.substring(schemeIndex + 3) else beforeQuery
		val firstSlash = afterScheme.indexOf('/')
		val authority = if (firstSlash >= 0) afterScheme.substring(0, firstSlash) else afterScheme
		val rawPath = if (firstSlash >= 0) afterScheme.substring(firstSlash) else ""
		val baseRaw = if (schemeIndex >= 0) beforeQuery.substring(0, schemeIndex + 3) + authority else authority
		val pathSegments = rawPath.split('/').filter { it.isNotEmpty() }
		return UrlParts(
			base = normalizeBase(resolver.resolve(baseRaw)),
			path = buildPath(pathSegments),
			queryParams = queryString.split('&').filter { it.isNotBlank() }.map {
				Param(resolver.resolve(it.substringBefore('=')), "query", required = false, example = resolver.resolve(it.substringAfter('=', "")))
			},
			pathParams = pathParamNames(pathSegments).map { Param(it, "path", required = true, example = "") },
		)
	}

	private fun buildBase(protocol: String?, host: List<String>, port: String?, resolver: VariableResolver): String? {
		val hostJoined = host.joinToString(".")
		if (hostJoined.isBlank()) return null
		val raw = buildString {
			if (!protocol.isNullOrBlank()) append(protocol).append("://")
			append(hostJoined)
			if (!port.isNullOrBlank()) append(":").append(port)
		}
		return normalizeBase(resolver.resolve(raw))
	}

	/** A usable base: non-blank, fully resolved (no leftover `{{…}}`), with a scheme; else null. */
	private fun normalizeBase(base: String): String? {
		val trimmed = base.trim().trimEnd('/')
		if (trimmed.isBlank() || "{{" in trimmed) return null
		return if ("://" in trimmed) trimmed else "https://$trimmed"
	}

	private fun buildPath(segments: List<String>): String =
		if (segments.isEmpty()) "/" else "/" + segments.joinToString("/") { normalizeSegment(it) }

	/** Postman path params (`:id`, `{{id}}`) become OpenAPI-style `{id}` (required by RouteMatcher). */
	private fun normalizeSegment(segment: String): String = when {
		segment.startsWith(":") -> "{${segment.drop(1)}}"
		segment.startsWith("{{") && segment.endsWith("}}") -> "{${segment.removeSurrounding("{{", "}}").trim()}}"
		else -> segment
	}

	private fun pathParamNames(segments: List<String>): List<String> = segments.mapNotNull {
		when {
			it.startsWith(":") -> it.drop(1)
			it.startsWith("{{") && it.endsWith("}}") -> it.removeSurrounding("{{", "}}").trim()
			else -> null
		}
	}

	// --- helpers ---------------------------------------------------------------------------------

	private fun parseHeaders(element: JsonElement?): List<PostmanHeader> = decodeList(element, PostmanHeader.serializer())

	private fun <T> decodeList(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
		(element as? JsonArray)?.let { runCatching { json.decodeFromJsonElement(ListSerializer(serializer), it) }.getOrDefault(emptyList()) } ?: emptyList()

	/** A string-or-array field as segments: an array of primitives, or a single string split by [separator]. */
	private fun segments(element: JsonElement?, separator: String): List<String> = when (element) {
		is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
		is JsonPrimitive -> element.contentOrNull?.split(separator)?.filter { it.isNotEmpty() } ?: emptyList()
		else -> emptyList()
	}

	private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

	private fun primitive(element: JsonElement?): String = (element as? JsonPrimitive)?.contentOrNull ?: ""

	/** A Postman description is a string or a `{ content, type }` object. */
	private fun descriptionText(element: JsonElement?): String? = when (element) {
		is JsonPrimitive -> element.contentOrNull
		is JsonObject -> (element["content"] as? JsonPrimitive)?.contentOrNull
		else -> null
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private fun formatLabel(schema: String?): String {
		val version = schema?.let { Regex("""v(\d+\.\d+\.\d+)""").find(it)?.groupValues?.get(1) }
		return if (version != null) "Postman $version" else "Postman"
	}

	private val EMPTY_URL = UrlParts(null, "/", emptyList(), emptyList())

	// --- collapse --------------------------------------------------------------------------------

	private class ParsedEndpoint(
		val method: String,
		val path: String,
		val base: String?,
		val summary: String?,
		val description: String?,
		val tags: List<String>,
		val security: List<SecurityRequirement>?,
		val parameters: List<Param>,
		val body: Body?,
		val responses: List<ResponseInfo>,
	)

	/** Accumulates every collapsed request for one `(method, path)` into a single [EndpointRow]. */
	private class RowBuilder(private val method: String, private val path: String) {
		private val bases = LinkedHashSet<String>()
		private var summary: String? = null
		private var description: String? = null
		private val tags = LinkedHashSet<String>()
		private var security: List<SecurityRequirement>? = null
		private val parameters = LinkedHashMap<String, Param>()
		private val contents = mutableListOf<ContentExample>()
		private val responses = mutableListOf<ResponseInfo>()

		fun add(endpoint: ParsedEndpoint) {
			endpoint.base?.let { bases.add(it) }
			if (summary == null) summary = endpoint.summary
			if (description == null) description = endpoint.description
			tags.addAll(endpoint.tags.filter { it.isNotBlank() })
			if (security == null) security = endpoint.security
			endpoint.parameters.forEach { parameters.putIfAbsent("${it.name}:${it.location}", it) }
			endpoint.body?.let { contents.addAll(it.contents) }
			responses.addAll(endpoint.responses)
		}

		fun build(): EndpointRow {
			val baseList = bases.toList()
			return EndpointRow(
				method = method,
				path = path,
				specBase = baseList.firstOrNull(),
				alternativeBases = baseList.drop(1),
				summary = summary,
				description = description,
				tags = tags.toList(),
				securityRequirements = security,
				parameters = parameters.values.toList(),
				requestBody = if (contents.isEmpty()) null else Body(required = false, contents = contents.toList()),
				responses = responses.toList(),
			)
		}
	}
}
