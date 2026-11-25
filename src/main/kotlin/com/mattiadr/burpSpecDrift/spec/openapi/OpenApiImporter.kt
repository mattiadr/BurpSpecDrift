package com.mattiadr.burpSpecDrift.spec.openapi

import com.mattiadr.burpSpecDrift.model.Body
import com.mattiadr.burpSpecDrift.model.ContentExample
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.ImportedSpec
import com.mattiadr.burpSpecDrift.model.Param
import com.mattiadr.burpSpecDrift.model.ResponseInfo
import com.mattiadr.burpSpecDrift.model.SecurityRequirement
import com.mattiadr.burpSpecDrift.model.SecurityScheme
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.parameters.Parameter as OasParameter
import io.swagger.v3.oas.models.security.SecurityScheme as OasSecurityScheme

/**
 * Imports an [OpenAPI] model into the generic [ImportedSpec] (info + [EndpointRow]s), one row per
 * operation. Server URLs are resolved with the precedence operation → path → root (variables
 * substituted with defaults). Detail is kept shallow: content types + an example (declared, or
 * generated from the schema) rather than full schemas.
 */
object OpenApiImporter {

	fun extract(openAPI: OpenAPI): ImportedSpec = ImportedSpec(
		title = openAPI.info?.title,
		version = openAPI.info?.version,
		format = openAPI.openapi?.let { "OpenAPI $it" } ?: "OpenAPI",
		endpoints = extractEndpoints(openAPI),
	)

	private fun extractEndpoints(openAPI: OpenAPI): List<EndpointRow> {
		val rootServers = resolveServers(openAPI.servers)
		val paths = openAPI.paths ?: return emptyList()

		val rows = ArrayList<EndpointRow>()
		for ((path, item) in paths) {
			val pathServers = resolveServers(item.servers)
			for ((method, operation) in item.readOperationsMap()) {
				val opServers = resolveServers(operation.servers)
				val bases = opServers.ifEmpty { pathServers.ifEmpty { rootServers } }
				rows += EndpointRow(
					method = method.name,
					path = path,
					specBase = bases.firstOrNull(),
					alternativeBases = if (bases.size > 1) bases.drop(1) else emptyList(),
					operationId = operation.operationId,
					summary = operation.summary,
					description = operation.description,
					tags = operation.tags ?: emptyList(),
					deprecated = operation.deprecated == true,
					securityRequirements = security(openAPI, operation),
					parameters = parameters(item.parameters, operation.parameters, openAPI),
					requestBody = requestBody(operation.requestBody, openAPI),
					responses = responses(operation, openAPI),
				)
			}
		}
		return rows
	}

	/**
	 * Security requirements for the operation (falling back to the root security), each scheme
	 * resolved against `components.securitySchemes` for a richer detail view. The outer list is an
	 * OR of alternatives; the schemes within one requirement are ANDed together.
	 */
	private fun security(openAPI: OpenAPI, operation: Operation): List<SecurityRequirement>? {
		// null (field absent at both levels) = unspecified; an empty list = auth explicitly disabled.
		val requirements = operation.security ?: openAPI.security ?: return null
		val schemes = openAPI.components?.securitySchemes ?: emptyMap()
		return requirements.map { requirement ->
			SecurityRequirement(
				requirement.map { (name, scopes) ->
					val scheme = schemes[name]
					SecurityScheme(
						name = name,
						type = scheme?.type?.toString(),
						detail = scheme?.let(::describeScheme),
						scopes = scopes ?: emptyList(),
					)
				}
			)
		}
	}

	/** Short human-readable summary of a security scheme's mechanism, or null if it carries none. */
	private fun describeScheme(scheme: OasSecurityScheme): String? = when (scheme.type) {
		OasSecurityScheme.Type.APIKEY -> "in ${scheme.`in`?.toString() ?: "?"}: ${scheme.name ?: "?"}"
		OasSecurityScheme.Type.HTTP -> buildString {
			append(scheme.scheme ?: "http")
			scheme.bearerFormat?.let { append(" ($it)") }
		}
		OasSecurityScheme.Type.OAUTH2 -> {
			val flows = scheme.flows
			val names = buildList {
				if (flows?.implicit != null) add("implicit")
				if (flows?.password != null) add("password")
				if (flows?.clientCredentials != null) add("clientCredentials")
				if (flows?.authorizationCode != null) add("authorizationCode")
			}
			if (names.isEmpty()) null else "flows: ${names.joinToString()}"
		}
		OasSecurityScheme.Type.OPENIDCONNECT -> scheme.openIdConnectUrl?.let { "discovery: $it" }
		OasSecurityScheme.Type.MUTUALTLS -> null
		null -> null
	}

	/** Merges path-level and operation-level parameters; operation overrides by (name, in). */
	private fun parameters(pathParams: List<OasParameter>?, opParams: List<OasParameter>?, openAPI: OpenAPI): List<Param> {
		val merged = LinkedHashMap<String, OasParameter>()
		(pathParams.orEmpty() + opParams.orEmpty()).forEach { merged["${it.name}:${it.`in`}"] = it }
		return merged.values.map { param ->
			Param(
				name = param.name ?: "",
				location = param.`in` ?: "",
				required = param.required == true,
				type = param.schema?.type,
				description = param.description,
				example = paramExample(param, openAPI),
			)
		}
	}

	/**
	 * The value to send for a parameter, resolved from the spec by priority: a parameter-level
	 * example, a named example, then the schema (example/default/enum/format/type default via
	 * [OpenApiSchemaExampleGenerator.generateScalar]). Falls back to the type name as a placeholder,
	 * except an optional string with no hint uses "optional" so it reads as clearly removable.
	 */
	private fun paramExample(param: OasParameter, openAPI: OpenAPI): String {
		param.example?.let { return it.toString() }
		param.examples?.values?.firstOrNull()?.value?.let { return it.toString() }
		OpenApiSchemaExampleGenerator.generateScalar(param.schema, openAPI)?.let { return it }
		val type = param.schema?.type ?: "string"
		return if (type == "string" && param.required != true) "optional" else type
	}

	private fun requestBody(body: RequestBody?, openAPI: OpenAPI): Body? {
		if (body == null) return null
		return Body(
			required = body.required == true,
			contents = contents(body.content, openAPI),
		)
	}

	private fun responses(operation: Operation, openAPI: OpenAPI): List<ResponseInfo> {
		val responses = operation.responses ?: return emptyList()
		return responses.map { (code, response: ApiResponse) ->
			ResponseInfo(
				statusCode = code,
				description = response.description,
				contents = contents(response.content, openAPI),
			)
		}
	}

	/**
	 * One [ContentExample] per media type: the declared example, or a sample synthesized from the
	 * schema for the formats we can build (JSON and x-www-form-urlencoded). For other types (e.g.
	 * application/xml) we keep whatever example the spec declares but don't synthesize one, so we
	 * never label a body as a format it isn't.
	 */
	private fun contents(content: Content?, openAPI: OpenAPI): List<ContentExample> =
		content?.map { (mediaType, media) ->
			val generated = when {
				isJsonMediaType(mediaType) -> OpenApiSchemaExampleGenerator.generateJson(media.schema, openAPI)
				isFormUrlEncoded(mediaType) -> OpenApiSchemaExampleGenerator.generateFormUrlEncoded(media.schema, openAPI)
				else -> null
			}
			ContentExample(mediaType, exampleOf(media) ?: generated)
		} ?: emptyList()

	/** True for application/json and any structured JSON type (e.g. application/problem+json). */
	private fun isJsonMediaType(mediaType: String): Boolean =
		mediaType.substringBefore(';').trim().endsWith("json", ignoreCase = true)

	/** True for application/x-www-form-urlencoded (ignoring any parameters). */
	private fun isFormUrlEncoded(mediaType: String): Boolean =
		mediaType.substringBefore(';').trim().equals("application/x-www-form-urlencoded", ignoreCase = true)

	/** The example explicitly declared for a single media type (inline, named, or in its schema). */
	private fun exampleOf(media: MediaType): String? =
		(media.example ?: media.examples?.values?.firstOrNull()?.value ?: media.schema?.example)?.toString()

	/**
	 * Resolves server URLs, dropping the "/" placeholder that swagger-parser injects when a
	 * spec declares no servers, so those endpoints end up with a null (unknown) base.
	 */
	private fun resolveServers(servers: List<Server>?): List<String> =
		servers?.map(::resolveUrl)?.filter { it.isNotBlank() && it != "/" } ?: emptyList()

	/** Substitutes `{var}` placeholders with each variable's declared default. */
	private fun resolveUrl(server: Server): String {
		var url = server.url ?: ""
		server.variables?.forEach { (name, variable) ->
			variable.default?.let { url = url.replace("{$name}", it) }
		}
		return url
	}
}
