package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.model.EndpointRow
import javax.swing.table.AbstractTableModel

/**
 * Table model over a list of [EndpointRow]s. The Base column shows each route's effective
 * base (route > global > spec) and is editable so the user can set a per-route override.
 */
class EndpointTableModel : AbstractTableModel() {

	private var rows: List<EndpointRow> = emptyList()

	/** The tab-level base override, used to resolve each row's effective base. */
	var globalOverride: String? = null
		set(value) {
			field = value
			fireTableDataChanged()
		}

	fun setRows(rows: List<EndpointRow>) {
		this.rows = rows
		fireTableDataChanged()
	}

	/** Repaints all cells, e.g. after overrides changed outside the table. */
	fun refresh() {
		fireTableDataChanged()
	}

	fun rowAt(modelIndex: Int): EndpointRow = rows[modelIndex]

	override fun getRowCount(): Int = rows.size

	override fun getColumnCount(): Int = COLUMNS.size

	override fun getColumnName(column: Int): String = COLUMNS[column]

	override fun getColumnClass(column: Int): Class<*> = String::class.java

	override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

	override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
		val row = rows[rowIndex]
		return when (columnIndex) {
			COL_BASE -> row.effectiveBase(globalOverride)
			COL_METHOD -> row.method
			COL_PATH -> row.path
			else -> null
		}
	}

	companion object {
		const val COL_BASE = 0
		const val COL_METHOD = 1
		const val COL_PATH = 2
		private val COLUMNS = arrayOf("Base", "Method", "Path")
	}
}
