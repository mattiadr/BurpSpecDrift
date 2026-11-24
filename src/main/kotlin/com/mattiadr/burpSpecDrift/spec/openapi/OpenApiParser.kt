package com.mattiadr.burpSpecDrift.spec.openapi

import burp.api.montoya.http.message.requests.HttpRequest
import com.mattiadr.burpSpecDrift.core.AppContext
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import java.nio.file.Files
import java.nio.file.Path

/** Parsing outcome: the resolved spec (on success) and any parser messages/errors. */
data class OpenApiParseResult(
	val openAPI: OpenAPI?,
	val messages: List<String>,
) {
	val isSuccess: Boolean get() = openAPI != null
}

/**
 * Parses an OpenAPI spec (2.0 / 3.0 / 3.1, JSON or YAML) via swagger-parser.
 * [OpenAPIParser] is the unified entry point: it auto-detects the version and converts
 * Swagger 2.0 to OpenAPI 3.0 via the v2 converter (loaded through ServiceLoader).
 * URL downloads go through the Montoya HTTP API to stay within Burp's traffic.
 */
object OpenApiParser {

	private fun parseOptions() = ParseOptions().apply {
		// Resolve external refs into internal components (self-contained) but don't inline fully,
		// so the model stays small. We only need method/path/servers, not full schemas.
		isResolve = true
	}

	/** Parses raw spec content (JSON or YAML, autodetected). */
	fun parseContent(content: String): OpenApiParseResult {
		val result = OpenAPIParser().readContents(content, null, parseOptions())
		return OpenApiParseResult(result.openAPI, result.messages ?: emptyList())
	}

	/** Loads and parses a spec from a local file. */
	fun parseFile(path: Path): OpenApiParseResult {
		val content = Files.readString(path)
		return parseContent(content)
	}

	/** Downloads a spec via Montoya HTTP and parses it. */
	fun parseUrl(url: String): OpenApiParseResult {
		val request = HttpRequest.httpRequestFromUrl(url)
		val response = AppContext.http.sendRequest(request).response()
			?: return OpenApiParseResult(null, listOf("No response from $url"))

		if (response.statusCode().toInt() !in 200..299) {
			return OpenApiParseResult(null, listOf("HTTP ${response.statusCode()} from $url"))
		}
		return parseContent(response.bodyToString())
	}
}
