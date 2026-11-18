package com.mattiadr.burpSpecDrift.gui

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import javax.swing.JMenuItem

/**
 * Adds a "Send to SpecDrift" item to the message editor and the request/response list.
 * The item appears only when the target has a response whose body looks like an OpenAPI
 * spec. The heuristic is intentionally loose (no full parse here) — an invalid spec is
 * reported later by the parser when loaded into a tab.
 */
class SpecDriftContextMenu(private val specDriftTab: SpecDriftTab) : ContextMenuItemsProvider {

	override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
		val requestResponse = targetRequestResponse(event) ?: return emptyList()
		val response = requestResponse.response() ?: return emptyList()
		val body = response.bodyToString()
		if (!looksLikeOpenApiSpec(body)) return emptyList()

		val item = JMenuItem("Send to SpecDrift")
		item.addActionListener { specDriftTab.sendSpecContent(body) }
		return listOf(item)
	}

	/**
	 * The single request/response this menu applies to: the one shown in the editor, or the
	 * single selected list entry. Returns null (no menu item) for multi-selection or neither.
	 */
	private fun targetRequestResponse(event: ContextMenuEvent): HttpRequestResponse? {
		val editor = event.messageEditorRequestResponse()
		if (editor.isPresent) return editor.get().requestResponse()

		val selected = event.selectedRequestResponses()
		return if (selected.size == 1) selected[0] else null
	}

	/** Cheap check for an OpenAPI/Swagger declaration near the start of the body. */
	private fun looksLikeOpenApiSpec(body: String): Boolean =
		OPENAPI_HINT.containsMatchIn(body.take(PREFIX_LENGTH))

	companion object {
		private const val PREFIX_LENGTH = 8192

		/** Matches `"openapi":`, `openapi:`, `"swagger":`, `swagger:` (JSON or YAML). */
		private val OPENAPI_HINT = Regex("""["']?(openapi|swagger)["']?\s*:""", RegexOption.IGNORE_CASE)
	}
}
