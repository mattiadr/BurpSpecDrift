package com.mattiadr.burpSpecDrift.gui

import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.ui.editor.EditorOptions
import burp.api.montoya.ui.editor.RawEditor
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.Param
import com.mattiadr.burpSpecDrift.model.ResponseInfo
import com.mattiadr.burpSpecDrift.model.SecurityRequirement
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.border.TitledBorder

/**
 * The master-detail detail pane. Two states, swapped with a [CardLayout]: a plain load summary
 * when nothing is selected, and, for a selected endpoint, a two-column layout of titled sections.
 *
 * Left column: a two-line header (method + full path, with summary beneath), the parameter list,
 * and the request body (content type + its example in a Burp raw editor).
 * Right column: the auth requirements and the response list with the first example in a raw editor.
 *
 * The two raw editors are created once and reused across selections (only their contents change).
 */
class EndpointDetailPanel {

	private val mono = Font(Font.MONOSPACED, Font.PLAIN, 12)

	// --- Summary card ---
	private val summaryArea = readOnlyArea()

	// --- Header ---
	private val headerLine1 = JLabel().apply { font = font.deriveFont(Font.BOLD) }
	private val headerLine2 = JLabel().apply { foreground = java.awt.Color.GRAY }

	/** Remaining endpoint metadata (operationId, tags, base/path, description), below the header. */
	private val metaArea = JTextArea().apply {
		isEditable = false
		lineWrap = true
		wrapStyleWord = true
		font = mono
	}

	// --- Parameters / auth / responses text ---
	private val paramsArea = readOnlyArea()
	private val authArea = readOnlyArea()
	private val responsesArea = readOnlyArea()

	// --- Burp raw editors (read-only), reused across selections ---
	private val requestEditor: RawEditor = AppContext.userInterface.createRawEditor(EditorOptions.READ_ONLY)
	private val responseEditor: RawEditor = AppContext.userInterface.createRawEditor(EditorOptions.READ_ONLY)
	/** Picks which request body content the editor shows; repopulated per selection. */
	private val requestSelector = JComboBox<Choice>().apply {
		addActionListener { if (!updatingSelector) showSelectedRequest() }
	}

	/** Picks which response's body the editor shows; repopulated per selection. */
	private val responseSelector = JComboBox<Choice>().apply {
		addActionListener { if (!updatingSelector) showSelectedResponse() }
	}

	/** Guards the selectors' action listeners while their items are being rebuilt. */
	private var updatingSelector = false

	/** Request-body section border; its title carries the required/optional marker. */
	private val requestBodyBorder: TitledBorder = BorderFactory.createTitledBorder("Request Body")

	private val cards = CardLayout()
	val component: JComponent = JPanel(cards)

	init {
		component.add(JScrollPane(summaryArea), CARD_SUMMARY)
		component.add(buildDetail(), CARD_DETAIL)
		enableWheelSelection(requestSelector)
		enableWheelSelection(responseSelector)
	}

	/** Lets the scroll wheel move the selection within [combo], clamped to its ends (no wrap). */
	private fun enableWheelSelection(combo: JComboBox<Choice>) {
		combo.addMouseWheelListener { event ->
			if (!combo.isEnabled || combo.itemCount == 0) return@addMouseWheelListener
			val next = (combo.selectedIndex + event.wheelRotation).coerceIn(0, combo.itemCount - 1)
			if (next != combo.selectedIndex) combo.selectedIndex = next
			event.consume()
		}
	}

	/** Shows the free-text load summary (nothing selected). */
	fun showSummary(text: String) {
		summaryArea.text = text
		summaryArea.caretPosition = 0
		cards.show(component, CARD_SUMMARY)
	}

	/** Shows the two-column detail for [row], resolving the full path against [globalOverride]. */
	fun showEndpoint(row: EndpointRow, globalOverride: String?) {
		headerLine1.text = buildString {
			append(row.method).append(' ').append(row.route(globalOverride))
			if (row.deprecated) append("  (deprecated)")
		}
		headerLine2.text = row.summary ?: " "

		metaArea.text = buildString {
			append("OperationId: ").append(row.operationId ?: "-")
			append("\nTags: ").append(row.tags.ifEmpty { listOf("-") }.joinToString())
			row.description?.let { append("\n\nDescription:\n").append(it) }
		}
		metaArea.caretPosition = 0

		paramsArea.text =
			if (row.parameters.isEmpty()) "No parameters."
			else row.parameters.joinToString("\n", transform = ::formatParam)
		paramsArea.caretPosition = 0

		authArea.text = securityText(row.securityRequirements)
		authArea.caretPosition = 0

		// Responses: the full list (overview) above the picker + editor.
		responsesArea.text =
			if (row.responses.isEmpty()) "No responses."
			else row.responses.joinToString("\n") { response ->
				buildString {
					append(response.statusCode)
					response.description?.let { append(": ").append(it) }
					val types = response.contents.joinToString { it.mediaType }
					if (types.isNotEmpty()) append("  [").append(types).append(']')
				}
			}
		responsesArea.caretPosition = 0

		requestBodyBorder.title = when {
			row.requestBody == null -> "Request Body"
			row.requestBody.required -> "Request Body (required)"
			else -> "Request Body (optional)"
		}

		// Rebuild both body pickers; each editor follows its selection, disabled when only one choice.
		updatingSelector = true
		val requestChoices = row.requestBody?.contents.orEmpty().map { Choice(it.mediaType, it.example) }
		populate(requestSelector, requestChoices, if (row.requestBody == null) "No request body" else "No content")
		populate(responseSelector, row.responses.map(::buildResponseChoice), "No responses")
		updatingSelector = false
		showSelectedRequest()
		showSelectedResponse()

		cards.show(component, CARD_DETAIL)
		component.repaint()   // reflect the updated request-body border title
	}

	/** Fills [combo] with [choices] (or a disabled placeholder when empty); enabled only if >1. */
	private fun populate(combo: JComboBox<Choice>, choices: List<Choice>, emptyLabel: String) {
		combo.removeAllItems()
		(choices.ifEmpty { listOf(Choice(emptyLabel, null)) }).forEach(combo::addItem)
		combo.selectedIndex = 0
		combo.isEnabled = choices.size > 1
	}

	private fun showSelectedRequest() {
		requestEditor.contents = bytes((requestSelector.selectedItem as? Choice)?.example)
	}

	private fun showSelectedResponse() {
		responseEditor.contents = bytes((responseSelector.selectedItem as? Choice)?.example)
	}

	private fun buildResponseChoice(response: ResponseInfo): Choice {
		val withExample = response.contents.firstOrNull { it.example != null }
		val shown = withExample ?: response.contents.firstOrNull()
		val label = buildString {
			append(response.statusCode)
			response.description?.let { append(" — ").append(it) }
			shown?.mediaType?.let { append("  [").append(it).append(']') }
		}
		return Choice(label, withExample?.example)
	}

	/** Builds the static two-column layout; the sections' contents are filled in per selection. */
	private fun buildDetail(): JComponent {
		val headerLabels = JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
			add(headerLine1)
			add(headerLine2)
		}
		val endpoint = JPanel(BorderLayout(0, 4)).apply {
			add(headerLabels, BorderLayout.NORTH)
			add(JScrollPane(metaArea).also { it.cap(200) }, BorderLayout.CENTER)
		}

		// Column 1: header/metadata + auth.
		val column1 = JPanel(BorderLayout()).apply {
			add(section("Endpoint", endpoint), BorderLayout.NORTH)
			add(section("Authentication", JScrollPane(authArea)), BorderLayout.CENTER)
		}

		// Column 2: parameters + request body (content-type dropdown over the example).
		val requestBody = JPanel(BorderLayout(0, 4)).apply {
			add(requestSelector, BorderLayout.NORTH)
			add(requestEditor.uiComponent(), BorderLayout.CENTER)
		}
		val requestBodySection = JPanel(BorderLayout()).apply {
			border = requestBodyBorder
			add(requestBody, BorderLayout.CENTER)
		}
		val column2 = JPanel(BorderLayout()).apply {
			add(section("Parameters", cappedScroll(paramsArea)), BorderLayout.NORTH)
			add(requestBodySection, BorderLayout.CENTER)
		}

		// Column 3: response list + the chosen response's body (dropdown over the editor).
		val responseBody = JPanel(BorderLayout(0, 4)).apply {
			add(responseSelector, BorderLayout.NORTH)
			add(responseEditor.uiComponent(), BorderLayout.CENTER)
		}
		val column3 = JPanel(BorderLayout()).apply {
			add(section("Responses", cappedScroll(responsesArea)), BorderLayout.NORTH)
			add(section("Response Body", responseBody), BorderLayout.CENTER)
		}

		return JPanel(GridLayout(1, 3, 8, 0)).apply {
			border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
			add(column1)
			add(column2)
			add(column3)
		}
	}

	private fun securityText(requirements: List<SecurityRequirement>?): String {
		// Unknown (not specified by the spec) -> show nothing; explicitly empty -> "<none>".
		if (requirements == null) return ""
		if (requirements.isEmpty()) return "<none> (auth explicitly disabled)"
		return buildString {
			requirements.forEachIndexed { index, requirement ->
				if (index > 0) appendLine("OR")
				requirement.schemes.forEach { scheme ->
					append("- ").append(scheme.name)
					scheme.type?.let { append(" [").append(it).append(']') }
					scheme.detail?.let { append(" — ").append(it) }
					appendLine()
					if (scheme.scopes.isNotEmpty()) appendLine("    scopes: ${scheme.scopes.joinToString()}")
				}
			}
		}.trimEnd()
	}

	private fun formatParam(param: Param): String = buildString {
		append("- ").append(param.name).append(" (").append(param.location)
		if (param.required) append(", required")
		append(')')
		param.type?.let { append(": ").append(it) }
		param.description?.let { append(" — ").append(it) }
		param.example?.let { append(" [example: ").append(it).append(']') }
	}

	private fun bytes(text: String?): MontoyaByteArray = MontoyaByteArray.byteArray(text ?: "")

	private fun readOnlyArea() = JTextArea().apply {
		isEditable = false
		lineWrap = false
		font = mono
	}

	/** Wraps [content] in a panel with a titled border. */
	private fun section(title: String, content: Component): JPanel = JPanel(BorderLayout()).apply {
		border = BorderFactory.createTitledBorder(title)
		add(content, BorderLayout.CENTER)
	}

	/** A scroll pane with a bounded preferred height, so a long list doesn't crowd out the editor. */
	private fun cappedScroll(view: Component): JScrollPane = JScrollPane(view).also { it.cap(140) }

	private fun JComponent.cap(height: Int) {
		preferredSize = Dimension(10, height)
	}

	/** One entry in a body dropdown: its display label and the example to show (if any). */
	private class Choice(private val label: String, val example: String?) {
		override fun toString(): String = label
	}

	companion object {
		private const val CARD_SUMMARY = "summary"
		private const val CARD_DETAIL = "detail"
	}
}
