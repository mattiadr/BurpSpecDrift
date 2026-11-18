package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.BaseSource
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
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
		autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
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
				val viewRow = table.rowAtPoint(event.point)
				val viewCol = table.columnAtPoint(event.point)
				if (viewRow < 0 || viewCol < 0) return
				if (table.convertColumnIndexToModel(viewCol) == EndpointTableModel.COL_BASE) {
					showBasePopup(viewRow)
				}
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

	/** Repaints the Base column, e.g. after the available bases or overrides changed. */
	fun refresh() {
		model.refresh()
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
