package com.mattiadr.burpSpecDrift.model

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.servers.Server

/**
 * Flattens an [OpenAPI] model into [EndpointRow]s, one per operation. Server URLs are
 * resolved with the precedence operation → path → root, and server variables are
 * substituted with their declared defaults.
 */
object EndpointExtractor {

	fun extract(openAPI: OpenAPI): List<EndpointRow> {
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
					tags = operation.tags ?: emptyList(),
				)
			}
		}
		return rows
	}

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
