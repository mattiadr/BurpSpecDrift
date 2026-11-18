package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.SpecTabData
import com.mattiadr.burpSpecDrift.spec.SpecParseResult
import com.mattiadr.burpSpecDrift.spec.SpecParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.basic.ComboPopup

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
	private val baseOverrideCombo = JComboBox<String>()

	/** Guards the combo's action listener while we repopulate it programmatically. */
	private var updatingCombo = false

	private val tablePanel = EndpointTablePanel()
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
		).apply { resizeWeight = 0.7 }
		add(split, BorderLayout.CENTER)
	}

	init {
		loadUrlButton.addActionListener { onLoadUrl() }
		loadFileButton.addActionListener { onLoadFile() }
		baseOverrideCombo.addActionListener { onBaseOverrideSelected() }
		configureBaseCombo()
		capWidthToPreferred(urlField)
		tablePanel.onRowSelected = { row -> showDetail(row) }
		detailArea.text = summaryText
		rebuildBaseCombo()
	}

	/** Fixes the combo width (minimum) and mutes the non-base sentinel entries. */
	private fun configureBaseCombo() {
		val height = baseOverrideCombo.preferredSize.height
		val size = Dimension(BASE_COMBO_WIDTH, height)
		baseOverrideCombo.minimumSize = size
		baseOverrideCombo.preferredSize = size
		baseOverrideCombo.maximumSize = size
		baseOverrideCombo.renderer = BaseComboRenderer()
		attachRemoveHandler()
	}

	/**
	 * Renders combo items with no selection highlight, a muted "<none>", and a trailing "✕"
	 * on removable (user-added) bases. The "✕" is only shown in the popup, not when collapsed.
	 */
	private inner class BaseComboRenderer : ListCellRenderer<Any?> {
		private val delegate = DefaultListCellRenderer()
		private val panel = JPanel(BorderLayout())
		private val closeLabel = JLabel("✕", SwingConstants.CENTER).apply {
			preferredSize = Dimension(X_HIT_WIDTH, preferredSize.height)
		}

		override fun getListCellRendererComponent(
			list: JList<out Any?>,
			value: Any?,
			index: Int,
			isSelected: Boolean,
			cellHasFocus: Boolean,
		): Component {
			// Force unselected look so the popup has no blue selection highlight.
			val label = delegate.getListCellRendererComponent(list, value, index, false, false) as JLabel
			if (value == NONE_ITEM) label.foreground = Color.GRAY
			val rowHeight = label.preferredSize.height

			val removable = index >= 0 && value is String && data.isCustomBase(value)
			if (!removable) {
				// Cap width so long items don't trigger horizontal scrolling in the popup.
				label.preferredSize = Dimension(ITEM_WIDTH, rowHeight)
				return label
			}

			panel.removeAll()
			panel.isOpaque = true
			panel.background = label.background
			label.preferredSize = null // let the label fill the center and ellipsize if long
			closeLabel.foreground = label.foreground
			panel.add(label, BorderLayout.CENTER)
			panel.add(closeLabel, BorderLayout.EAST)
			panel.preferredSize = Dimension(ITEM_WIDTH, rowHeight)
			return panel
		}
	}

	/** Lets a click on the trailing "✕" of a removable base delete it from the list. */
	private fun attachRemoveHandler() {
		val popup = baseOverrideCombo.ui.getAccessibleChild(baseOverrideCombo, 0) as? ComboPopup ?: return
		val list = popup.list
		list.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(e: MouseEvent) {
				val index = list.locationToIndex(e.point)
				if (index < 0) return
				val value = list.model.getElementAt(index) as? String ?: return
				if (!data.isCustomBase(value)) return
				val bounds = list.getCellBounds(index, index) ?: return
				if (e.x < bounds.x + bounds.width - X_HIT_WIDTH) return
				e.consume()
				baseOverrideCombo.hidePopup()
				SwingUtilities.invokeLater {
					data.removeCustomBase(value)
					rebuildBaseCombo()
				}
			}
		})
	}

	/** Mutes the combo's own text when "<none>" is the current selection (shown collapsed). */
	private fun updateComboForeground() {
		baseOverrideCombo.foreground =
			if (baseOverrideCombo.selectedItem == NONE_ITEM) Color.GRAY
			else UIManager.getColor("ComboBox.foreground")
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
		add(baseOverrideCombo)
		add(Box.createHorizontalGlue())
	}

	/** Repopulates the combo: "<none>", the sorted available bases, then a trailing "+ Add". */
	private fun rebuildBaseCombo() {
		updatingCombo = true
		try {
			baseOverrideCombo.removeAllItems()
			baseOverrideCombo.addItem(NONE_ITEM)
			data.availableBases().sorted().forEach { baseOverrideCombo.addItem(it) }
			baseOverrideCombo.addItem(ADD_ITEM)
			baseOverrideCombo.selectedItem = data.baseOverride ?: NONE_ITEM
		} finally {
			updatingCombo = false
		}
		updateComboForeground()
	}

	private fun onBaseOverrideSelected() {
		if (updatingCombo) return
		when (val selected = baseOverrideCombo.selectedItem as? String ?: return) {
			NONE_ITEM -> data.baseOverride = null
			ADD_ITEM -> promptAddBase()
			else -> data.baseOverride = selected
		}
		updateComboForeground()
	}

	private fun promptAddBase() {
		val input = JOptionPane.showInputDialog(
			component,
			"New base URL:",
			"Add base override",
			JOptionPane.PLAIN_MESSAGE,
		)
		val base = input?.trim()
		if (base.isNullOrEmpty()) {
			rebuildBaseCombo() // revert the "+ Add" selection
			return
		}
		data.addCustomBase(base)
		data.baseOverride = base
		rebuildBaseCombo()
	}

	private fun onLoadUrl() {
		val url = urlField.text.trim()
		if (url.isEmpty()) {
			setSummary("Enter a URL.")
			return
		}
		loadAsync("URL: $url") { SpecParser.parseUrl(url) }
	}

	private fun onLoadFile() {
		val chooser = JFileChooser()
		lastDirectory?.let { chooser.currentDirectory = it }
		if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION) return
		lastDirectory = chooser.currentDirectory
		val path = chooser.selectedFile.toPath()
		if (loadAsync("File: $path") { SpecParser.parseFile(path) }) {
			// Show the loaded file as a muted hint and free the URL field.
			urlField.text = ""
			urlField.hint = path.toString()
		}
	}

	/** Loads a spec from already-retrieved content (e.g. a response body sent from Burp). */
	fun loadFromString(content: String) {
		loadAsync("Sent from Burp") { SpecParser.parseContent(content) }
	}

	/**
	 * Runs the parsing on a background thread and updates the UI on the EDT. Returns false
	 * (without loading) when the tab already has a spec and the user declines to replace it.
	 */
	private fun loadAsync(source: String, block: () -> SpecParseResult): Boolean {
		if (data.hasLoadedSpec && !confirmReplace()) return false
		setButtonsEnabled(false)
		setSummary("Loading $source ...")
		Thread {
			val result = try {
				block()
			} catch (e: Exception) {
				SpecParseResult(null, listOf("Error: ${e.message}"))
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

	private fun renderResult(source: String, result: SpecParseResult) {
		val api = result.openAPI
		if (api != null) {
			data.onSpecLoaded(api, source)
			tablePanel.setRows(data.endpoints)
			rebuildBaseCombo()
		} else {
			tablePanel.setRows(emptyList())
		}

		val sb = StringBuilder()
		sb.appendLine(source)
		if (api != null) {
			val info = api.info
			sb.appendLine("Title:    ${info?.title ?: "-"}")
			sb.appendLine("Version:  ${info?.version ?: "-"}")
			sb.appendLine("OpenAPI:  ${api.openapi ?: "-"}")
			sb.appendLine("Endpoints: ${data.endpoints.size}")
		} else {
			sb.appendLine("Parsing failed.")
		}
		if (result.messages.isNotEmpty()) {
			sb.appendLine()
			sb.appendLine("Messages:")
			result.messages.forEach { sb.appendLine("  - $it") }
		}
		setSummary(sb.toString())
		AppContext.logging.logToOutput("[SpecDrift] $source -> success=${result.isSuccess}")

		if (result.isSuccess) {
			onSpecLoaded?.invoke()
		}
	}

	/** Shows either the selected endpoint's detail, or the load summary when nothing is selected. */
	private fun showDetail(row: EndpointRow?) {
		if (row == null) {
			detailArea.text = summaryText
			return
		}
		val sb = StringBuilder()
		sb.appendLine("Method:      ${row.method}")
		sb.appendLine("Route:       ${row.route()}")
		sb.appendLine("Path:        ${row.path}")
		sb.appendLine("Base:        ${row.specBase ?: "<none>"}")
		if (row.alternativeBases.isNotEmpty()) {
			sb.appendLine("Other bases: ${row.alternativeBases.joinToString()}")
		}
		sb.appendLine("OperationId: ${row.operationId ?: "-"}")
		sb.appendLine("Summary:     ${row.summary ?: "-"}")
		if (row.tags.isNotEmpty()) {
			sb.appendLine("Tags:        ${row.tags.joinToString()}")
		}
		detailArea.text = sb.toString()
		detailArea.caretPosition = 0
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
		/** Top combo entry that disables the override (muted). */
		private const val NONE_ITEM = "<none>"

		/** Trailing combo entry that prompts for a custom base (muted). */
		private const val ADD_ITEM = "+  Add"

		/** Minimum width of the base override combo. */
		private const val BASE_COMBO_WIDTH = 300

		/** Max preferred cell width in the popup (below the viewport, to avoid horizontal scroll). */
		private const val ITEM_WIDTH = BASE_COMBO_WIDTH - 24

		/** Width of the trailing "✕" hot zone (right edge) of a removable combo item. */
		private const val X_HIT_WIDTH = 24

		/** Last directory used by a file dialog, shared across tabs (Montoya exposes no global one). */
		private var lastDirectory: File? = null
	}
}
