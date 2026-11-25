package com.mattiadr.burpSpecDrift.spec

import com.mattiadr.burpSpecDrift.model.ImportedSpec
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiImporter
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiParseResult
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiParser
import com.mattiadr.burpSpecDrift.spec.postman.PostmanImporter
import java.nio.file.Files
import java.nio.file.Path

/** A supported spec source format. */
enum class SpecFormat(val label: String) {
	OPENAPI("OpenAPI"),
	POSTMAN("Postman"),
}

/**
 * Format-agnostic result of a spec import: the imported spec (null on failure), any parser/importer
 * messages to show in the summary, and the detected [SpecFormat] (null when it couldn't be
 * determined). Replaces the per-format parse results in the GUI contract.
 */
data class SpecImportResult(
	val spec: ImportedSpec?,
	val messages: List<String>,
	val format: SpecFormat?,
) {
	val isSuccess: Boolean get() = spec != null
}

/**
 * Format-agnostic entry point for loading a spec: detects the source format and delegates to the
 * matching parser + importer, always returning a generic [SpecImportResult] (an `ImportedSpec` plus
 * parser messages). The GUI talks only to this, never to a specific format.
 *
 * Only OpenAPI is wired today; Postman (and later Bruno) slot in at [detectFormat] and the
 * [importContent] branch.
 */
object SpecImporter {

	/** Loads and imports a spec from a local file (its content is sniffed for the format). */
	fun importFile(path: Path): SpecImportResult =
		runCatching { Files.readString(path) }.fold(
			onSuccess = ::importContent,
			onFailure = { SpecImportResult(null, listOf("Error reading $path: ${it.message}"), null) },
		)

	/** Imports a spec from already-retrieved content (format auto-detected). */
	fun importContent(content: String): SpecImportResult = when (detectFormat(content)) {
		SpecFormat.OPENAPI -> fromOpenApi(OpenApiParser.parseContent(content))
		SpecFormat.POSTMAN -> fromPostman(content)
		null -> SpecImportResult(null, listOf("Unrecognized spec format."), null)
	}

	/** Downloads and imports a spec from a URL (OpenAPI only for now). */
	fun importUrl(url: String): SpecImportResult = fromOpenApi(OpenApiParser.parseUrl(url))

	/**
	 * Whether [content] looks like a spec this importer can load. A cheap heuristic (no full parse)
	 * for gating the "Send to SpecDrift" menu; a content that passes but is invalid is reported by
	 * the parser when actually loaded.
	 */
	fun looksLikeSpec(content: String): Boolean = detectFormat(content) != null

	/**
	 * Cheap detection of the source format from the content prefix (no full parse), or null when
	 * unrecognized. Postman detection (e.g. `info.schema` / a top-level `item` array) is added
	 * together with its importer.
	 */
	private fun detectFormat(content: String): SpecFormat? {
		val head = content.take(DETECT_PREFIX_LENGTH)
		return when {
			OPENAPI_HINT.containsMatchIn(head) -> SpecFormat.OPENAPI
			POSTMAN_HINT.containsMatchIn(head) -> SpecFormat.POSTMAN
			else -> null
		}
	}

	private fun fromOpenApi(result: OpenApiParseResult): SpecImportResult {
		val api = result.openAPI ?: return SpecImportResult(null, result.messages, SpecFormat.OPENAPI)
		return SpecImportResult(OpenApiImporter.extract(api), result.messages, SpecFormat.OPENAPI)
	}

	private fun fromPostman(content: String): SpecImportResult = runCatching { PostmanImporter.import(content) }.fold(
		onSuccess = { SpecImportResult(it, emptyList(), SpecFormat.POSTMAN) },
		onFailure = { SpecImportResult(null, listOf("Postman import failed: ${it.message}"), SpecFormat.POSTMAN) },
	)

	private const val DETECT_PREFIX_LENGTH = 8192

	/** Matches `"openapi":`, `openapi:`, `"swagger":`, `swagger:` (JSON or YAML). */
	private val OPENAPI_HINT = Regex("""["']?(openapi|swagger)["']?\s*:""", RegexOption.IGNORE_CASE)

	/** Matches a Postman collection: its schema URL or the `_postman_id` marker. */
	private val POSTMAN_HINT = Regex("""schema\.(get)?postman\.com|_postman_id""", RegexOption.IGNORE_CASE)
}
