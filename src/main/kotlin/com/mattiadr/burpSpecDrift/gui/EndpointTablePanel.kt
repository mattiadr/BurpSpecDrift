package com.mattiadr.burpSpecDrift.gui

import burp.api.montoya.http.message.requests.HttpRequest
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.BaseSource
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.MenuSelectionManager
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * The endpoint table: sortable (click a header), multi-select, and ready to be filtered later
 * via its row sorter. The Base column shows each route's effective base, styled by source; a
 * click on it opens a popup menu to set/clear the per-route override.
 */
class EndpointTablePanel(private val data: SpecTabData) {

	private val model = EndpointTableModel()
	private val table = JTable(model).apply {
		autoCreateRowSorter = true
		setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
		// Auto-resize is off so every column stays freely draggable; we instead stretch the Path
		// column by hand (see stretchPathColumn) so all the leftover width goes there alone.
		autoResizeMode = JTable.AUTO_RESIZE_OFF
		fillsViewportHeight = true
		columnModel.getColumn(EndpointTableModel.COL_BASE).apply {
			cellRenderer = BaseRenderer()
			minWidth = 150
			preferredWidth = 300
		}
		columnModel.getColumn(EndpointTableModel.COL_METHOD).apply {
			minWidth = 50
			preferredWidth = 70
		}
		columnModel.getColumn(EndpointTableModel.COL_PATH).apply {
			minWidth = 150
			preferredWidth = 400
		}
		columnModel.getColumn(EndpointTableModel.COL_AUTH).apply {
			minWidth = 60
			preferredWidth = 120
		}
		val hitsRenderer = numericRenderer()
		for (col in intArrayOf(
			EndpointTableModel.COL_PROXY_HITS,
			EndpointTableModel.COL_REPEATER_HITS,
			EndpointTableModel.COL_OTHER_HITS,
		)) {
			columnModel.getColumn(col).apply {
				cellRenderer = hitsRenderer
				minWidth = 50
				preferredWidth = 90
			}
		}
		leftAlignHeader()
	}

	val component: JScrollPane = JScrollPane(table)

	/** Invoked with the (lead) selected row, or null when the selection is cleared. */
	var onRowSelected: ((EndpointRow?) -> Unit)? = null

	init {
		table.selectionModel.addListSelectionListener { event ->
			if (event.valueIsAdjusting) return@addListSelectionListener
			val viewRow = table.selectedRow
			val row = if (viewRow >= 0) model.rowAt(table.convertRowIndexToModel(viewRow)) else null
			onRowSelected?.invoke(row)
		}
		table.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(event: MouseEvent) {
				if (event.isPopupTrigger) {
					showContextMenu(event)
					return
				}
				if (SwingUtilities.isLeftMouseButton(event)) {
					val viewRow = table.rowAtPoint(event.point)
					val viewCol = table.columnAtPoint(event.point)
					if (viewRow >= 0 && viewCol >= 0 &&
						table.convertColumnIndexToModel(viewCol) == EndpointTableModel.COL_BASE
					) {
						showBasePopup(viewRow)
					}
				}
			}

			override fun mouseReleased(event: MouseEvent) {
				if (event.isPopupTrigger) showContextMenu(event)
			}
		})
		installShortcuts()
		// Keep Path filling the leftover viewport width as the table/window is resized.
		component.viewport.addComponentListener(object : ComponentAdapter() {
			override fun componentResized(event: ComponentEvent) = stretchPathColumn()
		})
		SwingUtilities.invokeLater { stretchPathColumn() }
	}

	/**
	 * Widens (or shrinks) the Path column so the visible columns exactly fill the viewport, giving
	 * all the slack to Path alone. Every column stays user-resizable (auto-resize is off); when the
	 * other columns already overflow the viewport, Path falls back to its minimum and a horizontal
	 * scrollbar appears.
	 */
	private fun stretchPathColumn() {
		val viewportWidth = component.viewport.width
		if (viewportWidth <= 0) return
		val columns = table.columnModel
		val pathColumn = columns.getColumn(EndpointTableModel.COL_PATH)
		var othersWidth = 0
		for (i in 0 until columns.columnCount) {
			if (i != EndpointTableModel.COL_PATH) othersWidth += columns.getColumn(i).width
		}
		val target = maxOf(pathColumn.minWidth, viewportWidth - othersWidth)
		if (pathColumn.width != target) {
			pathColumn.preferredWidth = target
			pathColumn.width = target
		}
	}

	/** Ctrl+R / Ctrl+I send every selected route to Repeater / Intruder. */
	private fun installShortcuts() {
		val inputMap = table.getInputMap(JComponent.WHEN_FOCUSED)
		val actionMap = table.actionMap
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "sendToRepeater")
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "sendToIntruder")
		actionMap.put("sendToRepeater", object : AbstractAction() {
			override fun actionPerformed(e: ActionEvent) {
				selectedRoutes().forEach { sendToRepeater(it) }
			}
		})
		actionMap.put("sendToIntruder", object : AbstractAction() {
			override fun actionPerformed(e: ActionEvent) {
				selectedRoutes().forEach { sendToIntruder(it) }
			}
		})
	}

	fun setRows(rows: List<EndpointRow>) {
		table.clearSelection()
		model.setRows(rows)
	}

	/** Sets the tab-level override so the Base column reflects it (and restyles). */
	fun setGlobalOverride(value: String?) {
		model.globalOverride = value
	}

	/** Shows the per-route base override dropdown below the clicked Base cell. */
	private fun showBasePopup(viewRow: Int) {
		val endpoint = model.rowAt(table.convertRowIndexToModel(viewRow))
		val rect = table.getCellRect(viewRow, table.convertColumnIndexToView(EndpointTableModel.COL_BASE), true)
		BasePopup.show(
			invoker = table,
			x = rect.x,
			y = rect.y + rect.height,
			data = data,
			firstLabel = endpoint.defaultBase(model.globalOverride) ?: "<none>",
			onFirst = { setOverride(endpoint, null) },
			onSelectBase = { setOverride(endpoint, it) },
		)
	}

	private fun setOverride(endpoint: EndpointRow, override: String?) {
		endpoint.routeOverride = override
		model.refresh()
		data.notifyChanged()
	}

	/** Right-click menu acting on the selected routes. */
	private fun showContextMenu(event: MouseEvent) {
		val viewRow = table.rowAtPoint(event.point)
		if (viewRow >= 0 && !table.isRowSelected(viewRow)) {
			table.setRowSelectionInterval(viewRow, viewRow)
		}
		val routes = selectedRoutes()
		if (routes.isEmpty()) return

		val menu = JPopupMenu()

		// "Override Base" is a native submenu: it opens to the right with a ">" arrow, so the base
		// dropdown never overlaps or dismisses the context menu.
		val overrideMenu = JMenu("Override Base")
		overrideMenu.add(
			BasePopup.buildList(
				data = data,
				firstLabel = "<none>",
				onFirst = { routes.forEach { it.routeOverride = null }; model.refresh(); data.notifyChanged() },
				onSelectBase = { base -> routes.forEach { it.routeOverride = base }; model.refresh(); data.notifyChanged() },
				onDone = { MenuSelectionManager.defaultManager().clearSelectedPath() },
			),
		)
		menu.add(withMinWidth(overrideMenu))
		menu.addSeparator()
		menu.add(sendItem("Send to Repeater", "Ctrl+R") { routes.forEach { sendToRepeater(it) } })
		menu.add(sendItem("Send to Intruder", "Ctrl+I") { routes.forEach { sendToIntruder(it) } })

		menu.show(table, event.x, event.y)
	}

	/**
	 * A menu item with native (gutter-aligned) text and a right-aligned grey shortcut hint.
	 * The hint is a plain label with no key binding — real shortcuts live on the table later.
	 */
	private fun sendItem(name: String, hint: String, action: () -> Unit): JMenuItem {
		val item = JMenuItem(name)
		item.layout = BorderLayout()
		item.add(
			JLabel(hint).apply {
				foreground = Color.GRAY
				border = BorderFactory.createEmptyBorder(0, 0, 0, 2)
			},
			BorderLayout.EAST,
		)
		item.addActionListener { action() }
		return withMinWidth(item)
	}

	/** Gives a menu item a minimum width so the context menu doesn't look cramped. */
	private fun <T : JMenuItem> withMinWidth(item: T): T {
		item.preferredSize = Dimension(maxOf(CONTEXT_WIDTH, item.preferredSize.width), item.preferredSize.height)
		return item
	}

	private fun selectedRoutes(): List<EndpointRow> =
		table.selectedRows.map { model.rowAt(table.convertRowIndexToModel(it)) }

	private fun sendToRepeater(row: EndpointRow) {
		val request = buildRequest(row) ?: return
		AppContext.api.repeater().sendToRepeater(request, "${row.method} ${row.path}")
	}

	private fun sendToIntruder(row: EndpointRow) {
		val request = buildRequest(row) ?: return
		AppContext.api.intruder().sendToIntruder(request)
	}

	/** Builds a request from the route's effective base + path; null when no base is known. */
	private fun buildRequest(row: EndpointRow): HttpRequest? {
		val base = row.effectiveBase(model.globalOverride) ?: return null
		return try {
			HttpRequest.httpRequestFromUrl(base.trimEnd('/') + row.path).withMethod(row.method)
		} catch (e: Exception) {
			AppContext.logging.logToError("[SpecDrift] Cannot build request for ${row.method} ${row.path}: ${e.message}")
			null
		}
	}

	companion object {
		/** Minimum text width (px) of the context menu items, giving the menu a minimum width. */
		private const val CONTEXT_WIDTH = 200
	}

	/** Left-aligned renderer for the numeric hit columns (overrides the default right alignment). */
	private fun numericRenderer(): DefaultTableCellRenderer = DefaultTableCellRenderer().apply {
		horizontalAlignment = SwingConstants.LEFT
	}

	/** Left-aligns the header labels while keeping the look & feel's own renderer. */
	private fun JTable.leftAlignHeader() {
		val original = tableHeader.defaultRenderer
		tableHeader.defaultRenderer = TableCellRenderer { tbl, value, isSelected, hasFocus, row, column ->
			val rendered = original.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
			(rendered as? JLabel)?.horizontalAlignment = SwingConstants.LEFT
			rendered
		}
	}

	/**
	 * Renders the Base cell by source: muted "<none>" when absent, plain when from the spec,
	 * italic when from the global override, bold italic when from the route override.
	 */
	private inner class BaseRenderer : DefaultTableCellRenderer() {
		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val endpoint = model.rowAt(table.convertRowIndexToModel(row))
			val source = endpoint.baseSource(model.globalOverride)
			val display = endpoint.effectiveBase(model.globalOverride) ?: "<none>"
			val component = super.getTableCellRendererComponent(table, display, isSelected, hasFocus, row, column)
			font = when (source) {
				BaseSource.ROUTE_OVERRIDE -> table.font.deriveFont(Font.BOLD or Font.ITALIC)
				BaseSource.GLOBAL_OVERRIDE -> table.font.deriveFont(Font.ITALIC)
				else -> table.font.deriveFont(Font.PLAIN)
			}
			// Set foreground every time: DefaultTableCellRenderer caches the last value, so a
			// grey "<none>" cell would otherwise bleed into the following cells.
			foreground = when {
				isSelected -> table.selectionForeground
				source == BaseSource.NONE -> Color.GRAY
				else -> table.foreground
			}
			return component
		}
	}
}
