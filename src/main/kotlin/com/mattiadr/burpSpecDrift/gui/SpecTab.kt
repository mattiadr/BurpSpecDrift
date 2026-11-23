package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.ContentExample
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.ImportedSpec
import com.mattiadr.burpSpecDrift.model.Param
import com.mattiadr.burpSpecDrift.model.SpecTabData
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiImporter
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiParseResult
import com.mattiadr.burpSpecDrift.spec.openapi.OpenApiParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * View for a single SpecDrift tab. Holds no live data itself: parse results are written
 * into its [SpecTabData], which is owned by the model layer. Layout is master-detail: the
 * endpoint table on top, a detail pane below showing the load summary or the selected row.
 */
class SpecTab(val data: SpecTabData) {

	/** Invoked on the EDT after a successful load, so the owner can refresh tab decorations. */
	var onSpecLoaded: (() -> Unit)? = null

	private val urlField = HintTextField(40)
	private val loadUrlButton = JButton("Load from URL")
	private val loadFileButton = JButton("Load from File")
	private val baseOverrideButton = JButton("<none>")
	private val searchField = HintTextField(20).apply { hint = "Search path" }

	/** Toggle state for the global base-override dropdown, so re-clicking the button closes it. */
	private val baseOverrideToggle = PopupToggle<Unit>()

	private val tablePanel = EndpointTablePanel(data)
	private val detailArea = JTextArea(6, 60).apply {
		isEditable = false
	}

	/** Summary shown in the detail pane when no row is selected. */
	private var summaryText: String = "No spec loaded."

	val component: JPanel = JPanel(BorderLayout()).apply {
		add(buildToolbar(), BorderLayout.NORTH)
		val split = JSplitPane(
			JSplitPane.VERTICAL_SPLIT,
			tablePanel.component,
			JScrollPane(detailArea),
		).apply {
			resizeWeight = 0.5
			// Place the divider at 50% once the split pane first gets a real size (a fractional
			// divider location can't be set before that), then let the user drag it freely.
			addComponentListener(object : ComponentAdapter() {
				override fun componentResized(event: ComponentEvent) {
					setDividerLocation(0.5)
					removeComponentListener(this)
				}
			})
		}
		add(split, BorderLayout.CENTER)
	}

	init {
		loadUrlButton.addActionListener { onLoadUrl() }
		loadFileButton.addActionListener { onLoadFile() }
		baseOverrideButton.addActionListener { openBaseOverridePopup() }
		configureBaseOverrideButton()
		capWidthToPreferred(urlField)
		capWidthToPreferred(searchField)
		searchField.document.addDocumentListener(object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = tablePanel.setPathSearch(searchField.text)
			override fun removeUpdate(e: DocumentEvent) = tablePanel.setPathSearch(searchField.text)
			override fun changedUpdate(e: DocumentEvent) = tablePanel.setPathSearch(searchField.text)
		})
		tablePanel.onRowSelected = { row -> showDetail(row) }
		data.onBasesChanged = { onBasesChanged() }
		detailArea.text = summaryText
		updateBaseOverrideButton()
		if (data.hasLoadedSpec) renderRestored()
	}

	/** Reacts to the shared base list changing (add/remove from either dropdown). */
	private fun onBasesChanged() {
		updateBaseOverrideButton()
		tablePanel.setGlobalOverride(data.baseOverride)
	}

	/** Fixes the button width and left-aligns its text, combo-style. */
	private fun configureBaseOverrideButton() {
		val size = Dimension(BASE_COMBO_WIDTH, baseOverrideButton.preferredSize.height)
		baseOverrideButton.minimumSize = size
		baseOverrideButton.preferredSize = size
		baseOverrideButton.maximumSize = size
		baseOverrideButton.horizontalAlignment = SwingConstants.LEFT
	}

	/** Shows the current global override on the button, muted when none. */
	private fun updateBaseOverrideButton() {
		val override = data.baseOverride
		baseOverrideButton.text = override ?: "<none>"
		baseOverrideButton.foreground =
			if (override == null) Color.GRAY else UIManager.getColor("Button.foreground")
	}

	/** Opens the shared base dropdown anchored below the button (tab-level override). Clicking the
	 * button again while it is open closes it (see [baseOverrideToggle]). */
	private fun openBaseOverridePopup() {
		if (!baseOverrideToggle.requestOpen(Unit)) return
		val popup = BasePopup.show(
			invoker = baseOverrideButton,
			x = 0,
			y = baseOverrideButton.height,
			data = data,
			firstLabel = "<none>",
			onFirst = { applyGlobalOverride(null) },
			onSelectBase = { applyGlobalOverride(it) },
		)
		baseOverrideToggle.track(popup, Unit)
	}

	private fun applyGlobalOverride(value: String?) {
		data.baseOverride = value
		updateBaseOverrideButton()
		tablePanel.setGlobalOverride(value)
	}

	private fun capWidthToPreferred(component: Component) {
		component.maximumSize = component.preferredSize
	}

	private fun buildToolbar(): JPanel = JPanel().apply {
		layout = BoxLayout(this, BoxLayout.X_AXIS)
		border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
		add(JLabel("Spec URL: "))
		add(urlField)
		add(Box.createHorizontalStrut(4))
		add(loadUrlButton)
		add(Box.createHorizontalStrut(4))
		add(loadFileButton)
		add(Box.createHorizontalStrut(24))
		add(JLabel("Base Override: "))
		add(baseOverrideButton)
		add(Box.createHorizontalGlue())
		add(JLabel("Search: "))
		add(searchField)
	}

	private fun onLoadUrl() {
		val url = urlField.text.trim()
		if (url.isEmpty()) {
			setSummary("Enter a URL.")
			return
		}
		loadAsync("URL: $url") { OpenApiParser.parseUrl(url) }
	}

	private fun onLoadFile() {
		val chooser = JFileChooser()
		lastDirectory?.let { chooser.currentDirectory = it }
		if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION) return
		lastDirectory = chooser.currentDirectory
		val path = chooser.selectedFile.toPath()
		if (loadAsync("File: $path") { OpenApiParser.parseFile(path) }) {
			// Show the loaded file as a muted hint and free the URL field.
			urlField.text = ""
			urlField.hint = path.toString()
		}
	}

	/** Repaints the hit columns after the traffic tracker updated the counters. */
	fun refreshHits() {
		tablePanel.refreshHits()
	}

	/** Loads a spec from already-retrieved content (e.g. a response body sent from Burp). */
	fun loadFromString(content: String) {
		loadAsync("Sent from Burp") { OpenApiParser.parseContent(content) }
	}

	/**
	 * Runs the parsing on a background thread and updates the UI on the EDT. Returns false
	 * (without loading) when the tab already has a spec and the user declines to replace it.
	 */
	private fun loadAsync(source: String, block: () -> OpenApiParseResult): Boolean {
		if (data.hasLoadedSpec && !confirmReplace()) return false
		setButtonsEnabled(false)
		setSummary("Loading $source ...")
		Thread {
			val result = try {
				block()
			} catch (e: Exception) {
				OpenApiParseResult(null, listOf("Error: ${e.message}"))
			}
			SwingUtilities.invokeLater {
				renderResult(source, result)
				setButtonsEnabled(true)
			}
		}.apply { isDaemon = true }.start()
		return true
	}

	/** Asks before replacing a spec already loaded in this tab. "Yes" is the default (Enter). */
	private fun confirmReplace(): Boolean {
		val options = arrayOf("Yes", "No")
		val choice = JOptionPane.showOptionDialog(
			component,
			"This tab already has a spec loaded. Replace it?",
			"Replace spec",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.QUESTION_MESSAGE,
			null,
			options,
			options[0],
		)
		return choice == JOptionPane.YES_OPTION
	}

	private fun renderResult(source: String, result: OpenApiParseResult) {
		searchField.text = ""
		val api = result.openAPI
		if (api != null) {
			val imported = OpenApiImporter.extract(api)
			data.onSpecLoaded(imported, source)
			tablePanel.setRows(data.endpoints)
			tablePanel.setGlobalOverride(data.baseOverride)
			updateBaseOverrideButton()
			setSummary(buildSummary(source, imported, result.messages))
		} else {
			tablePanel.setRows(emptyList())
			setSummary(buildSummary(source, null, result.messages))
		}
		AppContext.logging.logToOutput("[SpecDrift] $source -> success=${result.isSuccess}")

		if (result.isSuccess) {
			onSpecLoaded?.invoke()
		}
	}

	/** Reflects a spec already present in [data] (e.g. restored from the project file). */
	private fun renderRestored() {
		tablePanel.setRows(data.endpoints)
		tablePanel.setGlobalOverride(data.baseOverride)
		setSummary(buildSummary(data.source ?: "Restored", data.importedSpec, emptyList()))
	}

	private fun buildSummary(source: String, spec: ImportedSpec?, messages: List<String>): String {
		val sb = StringBuilder()
		sb.appendLine(source)
		if (spec != null) {
			sb.appendLine("Title:    ${spec.title ?: "-"}")
			sb.appendLine("Version:  ${spec.version ?: "-"}")
			sb.appendLine("OpenAPI:  ${spec.openApiVersion ?: "-"}")
			sb.appendLine("Endpoints: ${spec.endpoints.size}")
		} else {
			sb.appendLine("Parsing failed.")
		}
		if (messages.isNotEmpty()) {
			sb.appendLine()
			sb.appendLine("Messages:")
			messages.forEach { sb.appendLine("  - $it") }
		}
		return sb.toString()
	}

	/** Shows either the selected endpoint's detail, or the load summary when nothing is selected. */
	private fun showDetail(row: EndpointRow?) {
		if (row == null) {
			detailArea.text = summaryText
			return
		}
		val sb = StringBuilder()
		sb.appendLine("Method:       ${row.method}")
		sb.appendLine("Route:        ${row.route(data.baseOverride)}")
		sb.appendLine("Path:         ${row.path}")
		sb.appendLine("Base:         ${row.effectiveBase(data.baseOverride) ?: "<none>"}")
		if (row.alternativeBases.isNotEmpty()) {
			sb.appendLine("Other bases:  ${row.alternativeBases.joinToString()}")
		}
		sb.appendLine("OperationId:  ${row.operationId ?: "-"}")
		sb.appendLine("Summary:      ${row.summary ?: "-"}")
		sb.appendLine("Description:  ${row.description ?: "-"}")
		sb.appendLine("Deprecated:   ${row.deprecated}")
		sb.appendLine("Security:     ${row.security.ifEmpty { listOf("-") }.joinToString()}")
		sb.appendLine("Tags:         ${row.tags.ifEmpty { listOf("-") }.joinToString()}")

		sb.appendLine()
		sb.appendLine("Parameters:")
		if (row.parameters.isEmpty()) {
			sb.appendLine("  -")
		} else {
			row.parameters.forEach { sb.appendLine("  ${formatParam(it)}") }
		}

		sb.appendLine()
		val body = row.requestBody
		if (body == null) {
			sb.appendLine("Request body: -")
		} else {
			sb.appendLine("Request body: ${if (body.required) "required" else "optional"}")
			if (body.contents.isEmpty()) sb.appendLine("  -")
			else body.contents.forEach { appendContent(sb, it) }
		}

		sb.appendLine()
		sb.appendLine("Responses:")
		if (row.responses.isEmpty()) {
			sb.appendLine("  -")
		} else {
			row.responses.forEach { response ->
				sb.append("  - ${response.statusCode}")
				response.description?.let { sb.append(": $it") }
				sb.appendLine()
				response.contents.forEach { appendContent(sb, it) }
			}
		}

		detailArea.text = sb.toString()
		detailArea.caretPosition = 0
	}

	/** Appends a media type and, indented below it, its example (if any). */
	private fun appendContent(sb: StringBuilder, content: ContentExample) {
		sb.appendLine("    ${content.mediaType}")
		content.example?.lineSequence()?.forEach { line -> sb.appendLine("      $line") }
	}

	private fun formatParam(param: Param): String = buildString {
		append("- ${param.name} (${param.location}")
		if (param.required) append(", required")
		append(")")
		param.type?.let { append(": $it") }
		param.description?.let { append(" — $it") }
		param.example?.let { append(" [example: $it]") }
	}

	private fun setSummary(text: String) {
		summaryText = text
		detailArea.text = text
	}

	private fun setButtonsEnabled(enabled: Boolean) {
		loadUrlButton.isEnabled = enabled
		loadFileButton.isEnabled = enabled
	}

	companion object {
		/** Width of the base override button. */
		private const val BASE_COMBO_WIDTH = 300

		/** Last directory used by a file dialog, shared across tabs (Montoya exposes no global one). */
		private var lastDirectory: File? = null
	}
}
