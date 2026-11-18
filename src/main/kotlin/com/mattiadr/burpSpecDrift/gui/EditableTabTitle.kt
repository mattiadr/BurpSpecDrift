package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.awt.BorderLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.KeyStroke

/**
 * Tab title component with an editable label and a close button. A single click
 * selects the tab, a double click switches the label into an inline text field
 * for renaming, and the trailing "×" closes the tab. The name itself is stored in
 * [SpecTabData]; this component only renders and edits it.
 */
class EditableTabTitle(
	private val tabbedPane: JTabbedPane,
	private val data: SpecTabData,
	private val onClose: (EditableTabTitle) -> Unit,
) : JPanel(BorderLayout()) {

	private val label = JLabel(data.name)
	private val editor = JTextField()
	private val closeButton = JButton("×").apply {
		isBorderPainted = false
		isContentAreaFilled = false
		isFocusable = false
		isVisible = false
		margin = Insets(0, 0, 0, 0)
		border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
		toolTipText = "Close tab"
	}

	init {
		isOpaque = false
		add(label, BorderLayout.CENTER)
		add(closeButton, BorderLayout.EAST)

		closeButton.addActionListener { onClose(this) }

		label.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount >= 2) {
					startEditing()
				} else {
					selectThisTab()
				}
			}
		})

		editor.addActionListener { commitEditing() }
		editor.addFocusListener(object : FocusAdapter() {
			override fun focusLost(e: FocusEvent) = commitEditing()
		})
		editor.registerKeyboardAction(
			{ stopEditing() },
			KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
			JComponent.WHEN_FOCUSED,
		)
	}

	/** Shows the close button only while the tab is selected. */
	fun setCloseVisible(visible: Boolean) {
		if (closeButton.isVisible == visible) return
		closeButton.isVisible = visible
		revalidateTab()
	}

	/** Refreshes the displayed label from the model (e.g. after a spec load renamed the tab). */
	fun refreshFromData() {
		if (label.text != data.name) {
			label.text = data.name
			syncTabTitle()
			revalidateTab()
		}
	}

	private fun selectThisTab() {
		val idx = tabbedPane.indexOfTabComponent(this)
		if (idx >= 0) tabbedPane.selectedIndex = idx
	}

	private fun startEditing() {
		editor.text = label.text
		remove(label)
		add(editor, BorderLayout.CENTER)
		revalidateTab()
		editor.selectAll()
		editor.requestFocusInWindow()
	}

	private fun commitEditing() {
		val newText = editor.text.trim()
		if (newText.isNotEmpty()) {
			data.renameByUser(newText)
			label.text = data.name
		}
		stopEditing()
	}

	private fun stopEditing() {
		remove(editor)
		add(label, BorderLayout.CENTER)
		syncTabTitle()
		revalidateTab()
	}

	private fun syncTabTitle() {
		val idx = tabbedPane.indexOfTabComponent(this)
		if (idx >= 0) tabbedPane.setTitleAt(idx, label.text)
	}

	private fun revalidateTab() {
		revalidate()
		repaint()
		tabbedPane.revalidate()
		tabbedPane.repaint()
	}
}
