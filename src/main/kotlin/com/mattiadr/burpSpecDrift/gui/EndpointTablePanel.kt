package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.EndpointRow
import java.awt.Color
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * The endpoint table: sortable (click a header), multi-select, and ready to be filtered
 * later via its row sorter. Null base cells are rendered as a muted "<none>". Row selection
 * is reported through [onRowSelected] for the master-detail view.
 */
class EndpointTablePanel {

	private val model = EndpointTableModel()
	private val table = JTable(model).apply {
		autoCreateRowSorter = true
		setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
		autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
		fillsViewportHeight = true
		columnModel.getColumn(EndpointTableModel.COL_BASE).apply {
			cellRenderer = NoneAwareRenderer()
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
	}

	fun setRows(rows: List<EndpointRow>) {
		table.clearSelection()
		model.setRows(rows)
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

	/** Shows "<none>" in a muted color for null base cells. */
	private class NoneAwareRenderer : DefaultTableCellRenderer() {
		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val display = value ?: "<none>"
			val component = super.getTableCellRendererComponent(table, display, isSelected, hasFocus, row, column)
			if (value == null && !isSelected) {
				component.foreground = Color.GRAY
			}
			return component
		}
	}
}
