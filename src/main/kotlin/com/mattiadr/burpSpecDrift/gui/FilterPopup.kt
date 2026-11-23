package com.mattiadr.burpSpecDrift.gui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * The per-column filter dropdown, opened from a column header's funnel icon. It edits a single
 * column's criterion: a multi-select checklist (Method / Base / Auth) or a Hits radio
 * (any / = 0 / > 0). Changes are reported immediately (the table updates live); "Clear" resets
 * this column's criterion and stays open. Returns the popup so the caller can track it.
 */
object FilterPopup {

	private const val MAX_HEIGHT = 420

	/** Multi-select checklist for a categorical column (Method / Base / Auth). */
	fun showMultiSelect(
		invoker: Component,
		x: Int,
		y: Int,
		title: String,
		options: List<String>,
		selected: Set<String>,
		onChange: (Set<String>) -> Unit,
	): JPopupMenu {
		val popup = newPopup()
		val boxes = options.map { option ->
			JCheckBox(option, option in selected).apply {
				if (option == EndpointFilter.NONE) foreground = Color.GRAY
			}
		}
		boxes.forEach { box -> box.addActionListener { onChange(boxes.filter { it.isSelected }.map { it.text }.toSet()) } }
		val checks = JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
			alignmentX = Component.LEFT_ALIGNMENT
			border = BorderFactory.createEmptyBorder(2, 6, 0, 0)
			if (boxes.isEmpty()) {
				add(JLabel("(none)").apply { isEnabled = false })
			} else {
				boxes.forEach { box -> box.alignmentX = Component.LEFT_ALIGNMENT; add(box) }
			}
		}
		popup.add(scrollOf(content(title, checks)), BorderLayout.CENTER)
		popup.add(
			clearBar(popup) {
				boxes.forEach { it.isSelected = false }
				onChange(emptySet())
			},
			BorderLayout.SOUTH,
		)
		popup.show(invoker, x, y)
		return popup
	}

	/** Radio selector for a numeric hit column. */
	fun showHits(
		invoker: Component,
		x: Int,
		y: Int,
		title: String,
		current: HitFilter,
		onChange: (HitFilter) -> Unit,
	): JPopupMenu {
		val popup = newPopup()
		val any = JRadioButton("Any", current == HitFilter.ANY)
		val zero = JRadioButton("= 0", current == HitFilter.ZERO)
		val positive = JRadioButton("> 0", current == HitFilter.POSITIVE)
		ButtonGroup().apply { add(any); add(zero); add(positive) }
		any.addActionListener { onChange(HitFilter.ANY) }
		zero.addActionListener { onChange(HitFilter.ZERO) }
		positive.addActionListener { onChange(HitFilter.POSITIVE) }
		val radios = JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
			alignmentX = Component.LEFT_ALIGNMENT
			border = BorderFactory.createEmptyBorder(2, 6, 0, 0)
			add(any); add(zero); add(positive)
		}
		popup.add(content(title, radios), BorderLayout.CENTER)
		popup.add(
			clearBar(popup) {
				any.isSelected = true
				onChange(HitFilter.ANY)
			},
			BorderLayout.SOUTH,
		)
		popup.show(invoker, x, y)
		return popup
	}

	private fun newPopup() = JPopupMenu().apply { layout = BorderLayout() }

	/** A bold column title above its [body]. */
	private fun content(title: String, body: JPanel): JPanel = JPanel(BorderLayout()).apply {
		border = BorderFactory.createEmptyBorder(8, 10, 4, 10)
		add(JLabel(title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
		add(body, BorderLayout.CENTER)
	}

	private fun scrollOf(view: JPanel): JScrollPane = JScrollPane(view).apply {
		border = BorderFactory.createEmptyBorder()
		horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
		verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
		verticalScrollBar.unitIncrement = 16
		val wanted = view.preferredSize
		preferredSize = Dimension(wanted.width + 20, minOf(wanted.height + 4, MAX_HEIGHT))
	}

	/** A single "Clear" button that resets this column's criterion and closes the popup. */
	private fun clearBar(popup: JPopupMenu, onClear: () -> Unit): JPanel = JPanel(BorderLayout()).apply {
		border = BorderFactory.createEmptyBorder(6, 10, 8, 10)
		add(JButton("Clear").apply { addActionListener { onClear(); popup.isVisible = false } }, BorderLayout.WEST)
	}
}
