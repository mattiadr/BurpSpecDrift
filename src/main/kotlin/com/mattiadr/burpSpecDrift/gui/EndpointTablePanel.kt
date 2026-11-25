package com.mattiadr.burpSpecDrift.gui

import burp.api.montoya.http.message.params.HttpParameter
import burp.api.montoya.http.message.requests.HttpRequest
import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.BaseSource
import com.mattiadr.burpSpecDrift.model.EndpointRow
import com.mattiadr.burpSpecDrift.model.SpecTabData
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * The endpoint table: sortable (click a header), multi-select, and ready to be filtered later
 * via its row sorter. The Base column shows each route's effective base, styled by source; a
 * click on it opens a popup menu to set/clear the per-route override.
 */
class EndpointTablePanel(private val data: SpecTabData) {

	private val model = EndpointTableModel()
	private val sorter = TableRowSorter(model)
	private val table = JTable(model).apply {
		rowSorter = sorter
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
			preferredWidth = 90
		}
		columnModel.getColumn(EndpointTableModel.COL_PATH).apply {
			minWidth = 150
			preferredWidth = 400
		}
		columnModel.getColumn(EndpointTableModel.COL_AUTH).apply {
			cellRenderer = AuthRenderer()
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
				preferredWidth = 120
			}
		}
		tableHeader.defaultRenderer = FilterHeaderRenderer(tableHeader.defaultRenderer)
	}

	val component: JScrollPane = JScrollPane(table)

	/** Invoked with the (lead) selected row, or null when the selection is cleared. */
	var onRowSelected: ((EndpointRow?) -> Unit)? = null

	/**
	 * Supplies the request-body example index chosen in the detail view. Honored only when a single
	 * row is selected (the detail view shows exactly that row); a batch send falls back to the first body.
	 */
	var selectedBodyIndexProvider: (() -> Int?)? = null

	/** The columns whose header carries a filter funnel (everything except the free-text Path). */
	private val filterableColumns = setOf(
		EndpointTableModel.COL_METHOD,
		EndpointTableModel.COL_BASE,
		EndpointTableModel.COL_AUTH,
		EndpointTableModel.COL_PROXY_HITS,
		EndpointTableModel.COL_REPEATER_HITS,
		EndpointTableModel.COL_OTHER_HITS,
	)
	private val funnelActive = FilterIcon().apply { active = true }
	private val funnelInactive = FilterIcon()
	private var activeFilter = EndpointFilter()
	private var pathQuery = ""

	/** Toggle state for the header filter popups (keyed by model column) and the Base-cell popups
	 * (keyed by model row), so clicking the same source while open closes it instead of flickering. */
	private val headerFilterToggle = PopupToggle<Int>()
	private val baseCellToggle = PopupToggle<Int>()

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
		installHeaderFilters()
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

	/** Sends every selected route to Repeater. Bound to Ctrl+R by the owning [SpecTab]. */
	fun sendSelectedToRepeater() = selectedRoutes().let { routes -> routes.forEach { sendToRepeater(it, selectedBodyIndex(routes)) } }

	/** Sends every selected route to Intruder. Bound to Ctrl+I by the owning [SpecTab]. */
	fun sendSelectedToIntruder() = selectedRoutes().let { routes -> routes.forEach { sendToIntruder(it, selectedBodyIndex(routes)) } }

	/** Sends every selected route to Organizer. Bound to Ctrl+O by the owning [SpecTab]. */
	fun sendSelectedToOrganizer() = selectedRoutes().let { routes -> routes.forEach { sendToOrganizer(it, selectedBodyIndex(routes)) } }

	fun setRows(rows: List<EndpointRow>) {
		table.clearSelection()
		model.setRows(rows)
		// Drop any active filter: its selected values belong to the previous spec.
		if (activeFilter.isActive) applyFilter(EndpointFilter())
	}

	/** Resets every per-column header filter (the path search is cleared separately by the caller). */
	fun clearColumnFilters() {
		if (activeFilter.isActive) applyFilter(EndpointFilter())
	}

	/** Sets the tab-level override so the Base column reflects it (and restyles). */
	fun setGlobalOverride(value: String?) {
		model.globalOverride = value
	}

	/** Repaints the hit cells to reflect updated counters (keeps selection; no model reset). */
	fun refreshHits() {
		table.repaint()
	}

	/** True when [modelColumn] currently constrains the view (drives the funnel's filled state). */
	private fun isColumnFiltered(modelColumn: Int): Boolean = when (modelColumn) {
		EndpointTableModel.COL_METHOD -> activeFilter.methods.isNotEmpty()
		EndpointTableModel.COL_BASE -> activeFilter.bases.isNotEmpty()
		EndpointTableModel.COL_AUTH -> activeFilter.auth.isNotEmpty()
		EndpointTableModel.COL_PROXY_HITS -> activeFilter.proxyHits != HitFilter.ANY
		EndpointTableModel.COL_REPEATER_HITS -> activeFilter.repeaterHits != HitFilter.ANY
		EndpointTableModel.COL_OTHER_HITS -> activeFilter.otherHits != HitFilter.ANY
		else -> false
	}

	/**
	 * Opens the filter dropdown for one column's header, anchored under that header cell. Clicking
	 * the same column while its popup is open closes it (see [headerFilterToggle]).
	 */
	private fun openColumnFilter(viewColumn: Int) {
		val modelColumn = table.convertColumnIndexToModel(viewColumn)
		if (modelColumn !in filterableColumns) return
		if (!headerFilterToggle.requestOpen(modelColumn)) return
		val header = table.tableHeader
		val rect = header.getHeaderRect(viewColumn)
		val x = rect.x
		val y = rect.height
		val popup = when (modelColumn) {
			EndpointTableModel.COL_METHOD -> showMultiSelectFilter(header, x, y, "Method", availableMethods(), activeFilter.methods) {
				applyFilter(activeFilter.copy(methods = it))
			}
			EndpointTableModel.COL_BASE -> showMultiSelectFilter(header, x, y, "Base", availableBases(), activeFilter.bases) {
				applyFilter(activeFilter.copy(bases = it))
			}
			EndpointTableModel.COL_AUTH -> showMultiSelectFilter(header, x, y, "Auth", availableAuth(), activeFilter.auth) {
				applyFilter(activeFilter.copy(auth = it))
			}
			EndpointTableModel.COL_PROXY_HITS -> showHitsFilter(header, x, y, "Proxy Hits", activeFilter.proxyHits) {
				applyFilter(activeFilter.copy(proxyHits = it))
			}
			EndpointTableModel.COL_REPEATER_HITS -> showHitsFilter(header, x, y, "Repeater Hits", activeFilter.repeaterHits) {
				applyFilter(activeFilter.copy(repeaterHits = it))
			}
			EndpointTableModel.COL_OTHER_HITS -> showHitsFilter(header, x, y, "Other Hits", activeFilter.otherHits) {
				applyFilter(activeFilter.copy(otherHits = it))
			}
			else -> return
		}
		headerFilterToggle.track(popup, modelColumn)
	}

	private fun showMultiSelectFilter(
		invoker: JComponent,
		x: Int,
		y: Int,
		title: String,
		options: List<String>,
		selected: Set<String>,
		onChange: (Set<String>) -> Unit,
	) = FilterPopup.showMultiSelect(invoker, x, y, title, options, selected, onChange)

	private fun showHitsFilter(
		invoker: JComponent,
		x: Int,
		y: Int,
		title: String,
		current: HitFilter,
		onChange: (HitFilter) -> Unit,
	) = FilterPopup.showHits(invoker, x, y, title, current, onChange)

	private fun availableMethods(): List<String> =
		model.allRows().map { it.method }.distinct().sorted()

	private fun availableBases(): List<String> =
		model.allRows().map { it.effectiveBase(model.globalOverride) ?: EndpointFilter.NONE }.distinct().sorted()

	private fun availableAuth(): List<String> {
		val names = mutableListOf<String>()
		if (model.allRows().any { it.security.isEmpty() }) names += EndpointFilter.NONE
		names += model.allRows().flatMap { it.security }.distinct().sorted()
		return names
	}

	private fun applyFilter(filter: EndpointFilter) {
		activeFilter = filter
		rebuildRowFilter()
	}

	/** Sets the free-text path search (from the toolbar) and refilters. */
	fun setPathSearch(query: String) {
		pathQuery = query.trim()
		rebuildRowFilter()
	}

	/** Rebuilds the row filter from the column filters and the path search (both optional). */
	private fun rebuildRowFilter() {
		val filter = activeFilter
		val query = pathQuery
		sorter.rowFilter = if (filter.isActive || query.isNotEmpty()) {
			object : RowFilter<EndpointTableModel, Int>() {
				override fun include(entry: Entry<out EndpointTableModel, out Int>): Boolean {
					val row = model.rowAt(entry.identifier)
					if (query.isNotEmpty() && !row.path.contains(query, ignoreCase = true)) return false
					return filter.matches(row, model.globalOverride)
				}
			}
		} else {
			null
		}
		table.tableHeader.repaint()
	}

	/**
	 * Shows the per-route base override dropdown below the clicked Base cell. Clicking the same cell
	 * while its popup is open closes it (see [baseCellToggle]).
	 */
	private fun showBasePopup(viewRow: Int) {
		val modelRow = table.convertRowIndexToModel(viewRow)
		if (!baseCellToggle.requestOpen(modelRow)) return
		val endpoint = model.rowAt(modelRow)
		val rect = table.getCellRect(viewRow, table.convertColumnIndexToView(EndpointTableModel.COL_BASE), true)
		val popup = BasePopup.show(
			invoker = table,
			x = rect.x,
			y = rect.y + rect.height,
			data = data,
			firstLabel = endpoint.defaultBase(model.globalOverride) ?: "<none>",
			onFirst = { setOverride(endpoint, null) },
			onSelectBase = { setOverride(endpoint, it) },
		)
		baseCellToggle.track(popup, modelRow)
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
		val bodyIndex = selectedBodyIndex(routes)
		menu.add(sendItem("Send to Repeater", "Ctrl+R") { routes.forEach { sendToRepeater(it, bodyIndex) } })
		menu.add(sendItem("Send to Intruder", "Ctrl+I") { routes.forEach { sendToIntruder(it, bodyIndex) } })
		menu.add(sendItem("Send to Organizer", "Ctrl+O") { routes.forEach { sendToOrganizer(it, bodyIndex) } })

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

	/**
	 * The request-body example index to send: the detail view's dropdown selection, but only for a
	 * single-row send (the detail shows exactly that row). A batch send gets null (first body).
	 */
	private fun selectedBodyIndex(routes: List<EndpointRow>): Int? =
		if (routes.size == 1) selectedBodyIndexProvider?.invoke() else null

	private fun sendToRepeater(row: EndpointRow, bodyIndex: Int?) {
		val request = buildRequest(row, bodyIndex) ?: return
		AppContext.repeater.sendToRepeater(request, "${row.method} ${row.path}")
	}

	private fun sendToIntruder(row: EndpointRow, bodyIndex: Int?) {
		val request = buildRequest(row, bodyIndex) ?: return
		AppContext.intruder.sendToIntruder(request)
	}

	private fun sendToOrganizer(row: EndpointRow, bodyIndex: Int?) {
		val request = buildRequest(row, bodyIndex) ?: return
		AppContext.organizer.sendToOrganizer(request)
	}

	/**
	 * Builds a request from the route, filling in parameters and the request body (always included,
	 * even when optional). Path params are substituted into the path; query/cookie params are added
	 * as Burp parameters; header params become headers. The body content sent is the one at
	 * [bodyIndex] (the detail view's dropdown selection), falling back to the first declared content;
	 * its media type becomes the Content-Type. Returns null when no base is known.
	 */
	private fun buildRequest(row: EndpointRow, bodyIndex: Int?): HttpRequest? {
		val base = row.effectiveBase(model.globalOverride) ?: return null
		return try {
			var path = row.path
			row.parameters.filter { it.location == "path" }.forEach { param ->
				path = path.replace("{${param.name}}", encodePath(param.example))
			}

			var request = HttpRequest.httpRequestFromUrl(base.trimEnd('/') + path).withMethod(row.method)

			val params = row.parameters.mapNotNull { param ->
				when (param.location) {
					"query" -> HttpParameter.urlParameter(param.name, param.example)
					"cookie" -> HttpParameter.cookieParameter(param.name, param.example)
					else -> null
				}
			}
			if (params.isNotEmpty()) request = request.withAddedParameters(params)

			row.parameters.filter { it.location == "header" }.forEach { param ->
				request = request.withAddedHeader(param.name, param.example)
			}

			row.requestBody?.contents?.let { contents ->
				bodyIndex?.let { contents.getOrNull(it) } ?: contents.firstOrNull()
			}?.let { content ->
				request = request.withBody(content.example ?: "")
					.withHeader("Content-Type", content.mediaType)
			}

			request
		} catch (e: Exception) {
			AppContext.logging.logToError("[SpecDrift] Cannot build request for ${row.method} ${row.path}: ${e.message}")
			null
		}
	}

	/** Percent-encodes a value for safe insertion into a URL path segment. */
	private fun encodePath(value: String): String =
		java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

	companion object {
		/** Minimum text width (px) of the context menu items, giving the menu a minimum width. */
		private const val CONTEXT_WIDTH = 200

		/** Width (px) of the clickable funnel zone at the right of a filterable header. */
		private const val FUNNEL_HIT_WIDTH = 22

		/** Px kept clear at the very right edge of the header for the column resize handle. */
		private const val FUNNEL_HIT_EDGE = 4
	}

	/** Left-aligned renderer for the numeric hit columns (overrides the default right alignment). */
	private fun numericRenderer(): DefaultTableCellRenderer = DefaultTableCellRenderer().apply {
		horizontalAlignment = SwingConstants.LEFT
	}

	/**
	 * Routes header clicks: a right-click anywhere on a filterable column, or a left-click on its
	 * funnel icon, opens that column's filter (toggling it if already open); every other event is
	 * re-dispatched to the header's own listeners so sorting / resizing / reordering keep working.
	 * The header's mouse listeners are removed and forwarded selectively; motion listeners are left
	 * in place so the resize cursor still appears.
	 */
	private fun installHeaderFilters() {
		val header = table.tableHeader
		val defaults = header.mouseListeners
		defaults.forEach { header.removeMouseListener(it) }
		header.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(event: MouseEvent) {
				if (SwingUtilities.isRightMouseButton(event)) {
					openColumnFilter(header.columnAtPoint(event.point))
					return
				}
				val funnelColumn = funnelColumnAt(event)
				if (funnelColumn >= 0) openColumnFilter(funnelColumn) else defaults.forEach { it.mousePressed(event) }
			}

			override fun mouseReleased(event: MouseEvent) {
				if (SwingUtilities.isRightMouseButton(event)) return
				if (funnelColumnAt(event) < 0) defaults.forEach { it.mouseReleased(event) }
			}

			override fun mouseClicked(event: MouseEvent) {
				if (SwingUtilities.isRightMouseButton(event)) return
				if (funnelColumnAt(event) < 0) defaults.forEach { it.mouseClicked(event) }
			}

			override fun mouseEntered(event: MouseEvent) = defaults.forEach { it.mouseEntered(event) }

			override fun mouseExited(event: MouseEvent) = defaults.forEach { it.mouseExited(event) }
		})
	}

	/** The view-column index whose funnel hit zone contains the event, or -1 if none. */
	private fun funnelColumnAt(event: MouseEvent): Int {
		val header = table.tableHeader
		val viewColumn = header.columnAtPoint(event.point)
		if (viewColumn < 0) return -1
		if (table.convertColumnIndexToModel(viewColumn) !in filterableColumns) return -1
		val rect = header.getHeaderRect(viewColumn)
		val right = rect.x + rect.width
		// A zone on the right of the header cell, kept a few px off the very edge so the column
		// resize handle there still works.
		return if (event.x in (right - FUNNEL_HIT_EDGE - FUNNEL_HIT_WIDTH) until (right - FUNNEL_HIT_EDGE)) viewColumn else -1
	}

	/**
	 * Header renderer: left-aligned title, plus a funnel icon pinned to the right of every
	 * filterable column (filled when that column is filtered, outlined otherwise).
	 */
	private inner class FilterHeaderRenderer(private val original: TableCellRenderer) : TableCellRenderer {
		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val rendered = original.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
			val label = rendered as? JLabel ?: return rendered
			label.horizontalAlignment = SwingConstants.LEFT
			if (table.convertColumnIndexToModel(column) !in filterableColumns) return label

			val funnel = if (isColumnFiltered(table.convertColumnIndexToModel(column))) funnelActive else funnelInactive
			val iconLabel = JLabel(funnel).apply {
				foreground = label.foreground
				border = BorderFactory.createEmptyBorder(0, 4, 0, FUNNEL_HIT_EDGE + 2)
			}
			return JPanel(BorderLayout()).apply {
				isOpaque = label.isOpaque
				background = label.background
				border = label.border
				label.border = null
				add(label, BorderLayout.CENTER)
				add(iconLabel, BorderLayout.EAST)
			}
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

	/** Greys the "<none>" marker (auth explicitly disabled); scheme names render normally. */
	private class AuthRenderer : DefaultTableCellRenderer() {
		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
			// Set foreground every time: DefaultTableCellRenderer reuses one component across cells.
			foreground = when {
				isSelected -> table.selectionForeground
				value == EndpointFilter.NONE -> Color.GRAY
				else -> table.foreground
			}
			return component
		}
	}
}
