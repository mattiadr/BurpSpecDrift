package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The shared base-selection dropdown, used both for the tab-level (global) override and the
 * per-route override so they look and behave identically. Shows a muted first entry (clears
 * the override), the available bases (sorted, with a "✕" to remove user-added ones), and a
 * trailing "+ Add". All rows use a fixed cell width so text and the "✕" align on a grid, with
 * the "✕" pinned to the right. Entries highlight on hover like a menu.
 */
object BasePopup {

	private const val ADD_LABEL = "+ Add"
	private const val LEFT_MARGIN = 8
	private const val RIGHT_MARGIN = 8
	private const val GAP = 10
	private const val X_COL_WIDTH = 16
	private const val MIN_WIDTH = 220

	/** Right-edge zone (the "✕" column plus the right margin) that counts as a remove click. */
	private const val HIT_ZONE = X_COL_WIDTH + RIGHT_MARGIN

	private sealed interface Item
	private data class First(val label: String) : Item
	private data class Base(val url: String, val removable: Boolean) : Item
	private object Add : Item

	private fun textOf(item: Item): String = when (item) {
		is First -> item.label
		is Base -> item.url
		Add -> ADD_LABEL
	}

	/**
	 * Shows the dropdown anchored at ([x], [y]) relative to [invoker].
	 * [firstLabel] is the muted top entry; [onFirst] clears the override, [onSelectBase] sets it.
	 */
	fun show(
		invoker: Component,
		x: Int,
		y: Int,
		data: SpecTabData,
		firstLabel: String,
		onFirst: () -> Unit,
		onSelectBase: (String) -> Unit,
	) {
		val items = buildList {
			add(First(firstLabel))
			data.availableBases().sorted().forEach { add(Base(it, data.isCustomBase(it))) }
			add(Add)
		}

		val list = JList(items.toTypedArray()).apply {
			selectionMode = ListSelectionModel.SINGLE_SELECTION
			cellRenderer = ItemRenderer()
		}
		// Fixed cell width => text and "✕" align on a grid; also enforces a minimum width.
		val metrics = list.getFontMetrics(list.font)
		val textWidth = items.maxOf { metrics.stringWidth(textOf(it)) }
		list.fixedCellWidth = maxOf(MIN_WIDTH, LEFT_MARGIN + textWidth + GAP + X_COL_WIDTH + RIGHT_MARGIN)

		val popup = JPopupMenu().apply {
			layout = BorderLayout()
			add(list, BorderLayout.CENTER)
		}

		// Menu-like hover highlight.
		list.addMouseMotionListener(object : MouseMotionAdapter() {
			override fun mouseMoved(e: MouseEvent) {
				list.selectedIndex = list.locationToIndex(e.point)
			}
		})
		list.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(e: MouseEvent) {
				val index = list.locationToIndex(e.point)
				if (index < 0) return
				when (val item = items[index]) {
					is First -> {
						onFirst()
						popup.isVisible = false
					}
					is Base -> {
						val bounds = list.getCellBounds(index, index)
						if (item.removable && bounds != null && e.x >= bounds.x + bounds.width - HIT_ZONE) {
							popup.isVisible = false
							data.removeCustomBase(item.url)
						} else {
							onSelectBase(item.url)
							popup.isVisible = false
						}
					}
					Add -> {
						popup.isVisible = false
						promptAdd(invoker, data, onSelectBase)
					}
				}
			}
		})

		popup.show(invoker, x, y)
	}

	private fun promptAdd(invoker: Component, data: SpecTabData, onSelectBase: (String) -> Unit) {
		val input = JOptionPane.showInputDialog(
			invoker,
			"New base URL:",
			"Add base override",
			JOptionPane.PLAIN_MESSAGE,
		) as String?
		val base = input?.trim()
		if (base.isNullOrEmpty()) return
		data.addCustomBase(base)
		onSelectBase(base)
	}

	/**
	 * Fixed-grid row: left margin, text, a reserved "✕" column on the right, right margin.
	 * The "✕" column is blank for non-removable entries so every row's text column lines up.
	 */
	private class ItemRenderer : ListCellRenderer<Item> {
		private val panel = JPanel(BorderLayout()).apply {
			isOpaque = true
			border = BorderFactory.createEmptyBorder(2, LEFT_MARGIN, 2, RIGHT_MARGIN)
		}
		private val textLabel = JLabel().apply {
			border = BorderFactory.createEmptyBorder(0, 0, 0, GAP)
		}
		private val closeLabel = JLabel("", SwingConstants.CENTER).apply {
			preferredSize = Dimension(X_COL_WIDTH, preferredSize.height)
		}

		init {
			panel.add(textLabel, BorderLayout.CENTER)
			panel.add(closeLabel, BorderLayout.EAST)
		}

		override fun getListCellRendererComponent(
			list: JList<out Item>,
			value: Item,
			index: Int,
			isSelected: Boolean,
			cellHasFocus: Boolean,
		): Component {
			panel.background = if (isSelected) list.selectionBackground else list.background
			val foreground = if (isSelected) list.selectionForeground else list.foreground

			textLabel.text = textOf(value)
			textLabel.foreground = if (value is First && !isSelected) Color.GRAY else foreground
			closeLabel.text = if (value is Base && value.removable) "✕" else ""
			closeLabel.foreground = foreground
			return panel
		}
	}
}
