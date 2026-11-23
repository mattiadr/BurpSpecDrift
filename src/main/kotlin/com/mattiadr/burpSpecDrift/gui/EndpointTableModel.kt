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

	/** All backing rows (unfiltered, in model order). */
	fun allRows(): List<EndpointRow> = rows

	override fun getRowCount(): Int = rows.size

	override fun getColumnCount(): Int = COLUMNS.size

	override fun getColumnName(column: Int): String = COLUMNS[column]

	override fun getColumnClass(column: Int): Class<*> = when (column) {
		COL_PROXY_HITS, COL_REPEATER_HITS, COL_OTHER_HITS -> Integer::class.java
		else -> String::class.java
	}

	override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

	override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
		val row = rows[rowIndex]
		return when (columnIndex) {
			COL_BASE -> row.effectiveBase(globalOverride)
			COL_METHOD -> row.method
			COL_PATH -> row.path
			COL_AUTH -> row.security.joinToString(", ")
			COL_PROXY_HITS -> row.proxyHits
			COL_REPEATER_HITS -> row.repeaterHits
			COL_OTHER_HITS -> row.otherHits
			else -> null
		}
	}

	companion object {
		const val COL_BASE = 0
		const val COL_METHOD = 1
		const val COL_PATH = 2
		const val COL_AUTH = 3
		const val COL_PROXY_HITS = 4
		const val COL_REPEATER_HITS = 5
		const val COL_OTHER_HITS = 6
		private val COLUMNS = arrayOf("Base", "Method", "Path", "Auth", "Proxy Hits", "Repeater Hits", "Other Hits")
	}
}
