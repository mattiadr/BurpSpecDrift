package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.SpecTabData
import com.mattiadr.burpSpecDrift.spec.SpecParseResult
import com.mattiadr.burpSpecDrift.spec.SpecParser
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * View for a single SpecDrift tab. Holds no live data itself: parse results are written
 * into its [SpecTabData], which is owned by the model layer.
 */
class SpecTab(val data: SpecTabData) {

	/** Invoked on the EDT after a successful load, so the owner can refresh tab decorations. */
	var onSpecLoaded: (() -> Unit)? = null

	private val urlField = JTextField(40)
	private val loadUrlButton = JButton("Load from URL")
	private val loadFileButton = JButton("Load from File")
	private val statusArea = JTextArea(10, 60).apply {
		isEditable = false
	}

	val component: JPanel = JPanel(BorderLayout()).apply {
		add(buildToolbar(), BorderLayout.NORTH)
		add(JScrollPane(statusArea), BorderLayout.CENTER)
	}

	init {
		loadUrlButton.addActionListener { onLoadUrl() }
		loadFileButton.addActionListener { onLoadFile() }
	}

	private fun buildToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
		add(JLabel("Spec URL:"))
		add(urlField)
		add(loadUrlButton)
		add(loadFileButton)
	}

	private fun onLoadUrl() {
		val url = urlField.text.trim()
		if (url.isEmpty()) {
			setStatus("Enter a URL.")
			return
		}
		loadAsync("URL: $url") { SpecParser.parseUrl(url) }
	}

	private fun onLoadFile() {
		val chooser = JFileChooser()
		if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION) return
		val path = chooser.selectedFile.toPath()
		loadAsync("File: $path") { SpecParser.parseFile(path) }
	}

	/** Loads a spec from already-retrieved content (e.g. a response body sent from Burp). */
	fun loadFromString(content: String) {
		loadAsync("Sent from Burp") { SpecParser.parseContent(content) }
	}

	/** Runs the parsing on a background thread and updates the UI on the EDT. */
	private fun loadAsync(source: String, block: () -> SpecParseResult) {
		setButtonsEnabled(false)
		setStatus("Loading $source ...")
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
	}

	private fun renderResult(source: String, result: SpecParseResult) {
		if (result.isSuccess) {
			data.openAPI = result.openAPI
			data.source = source
			data.applySpecTitleToName(result.openAPI?.info?.title)
		}

		val sb = StringBuilder()
		sb.appendLine(source)
		val api = result.openAPI
		if (api != null) {
			val info = api.info
			sb.appendLine("Title:    ${info?.title ?: "-"}")
			sb.appendLine("Version:  ${info?.version ?: "-"}")
			sb.appendLine("OpenAPI:  ${api.openapi ?: "-"}")
			sb.appendLine("Paths:    ${api.paths?.size ?: 0}")
		} else {
			sb.appendLine("Parsing failed.")
		}
		if (result.messages.isNotEmpty()) {
			sb.appendLine()
			sb.appendLine("Messages:")
			result.messages.forEach { sb.appendLine("  - $it") }
		}
		setStatus(sb.toString())
		AppContext.logging.logToOutput("[SpecDrift] $source -> success=${result.isSuccess}")

		if (result.isSuccess) {
			onSpecLoaded?.invoke()
		}
	}

	private fun setButtonsEnabled(enabled: Boolean) {
		loadUrlButton.isEnabled = enabled
		loadFileButton.isEnabled = enabled
	}

	private fun setStatus(text: String) {
		statusArea.text = text
	}
}
