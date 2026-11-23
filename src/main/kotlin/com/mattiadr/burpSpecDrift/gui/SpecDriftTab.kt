package com.mattiadr.burpSpecDrift.gui

import com.mattiadr.burpSpecDrift.core.AppContext
import com.mattiadr.burpSpecDrift.model.SpecDriftModel
import com.mattiadr.burpSpecDrift.model.SpecTabData
import com.mattiadr.burpSpecDrift.persistence.SpecDriftPersistence
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.Timer

/**
 * Top-level SpecDrift suite tab: a tabbed container of [SpecTab] views, Repeater-style,
 * with a trailing "+" tab that creates a new spec tab when selected. All live data lives
 * in [SpecDriftModel]; this class only renders it and routes user actions.
 */
class SpecDriftTab {

	private val model = SpecDriftModel()
	private val persistence = SpecDriftPersistence()

	private val tabbedPane = JTabbedPane()
	private val plusPanel = JPanel()

	/** Maps each spec tab's content component to its [SpecTab] view. */
	private val tabsByComponent = HashMap<Component, SpecTab>()

	/** Guards against the "+" tab reacting while we programmatically add/select/remove tabs. */
	private var adjusting = false

	/** Set by the (future) proxy handler when hit counts change, flushed by the timer. */
	@Volatile
	private var hitsDirty = false

	val component: JComponent get() = tabbedPane

	init {
		tabbedPane.addTab("+", plusPanel)
		tabbedPane.setTabComponentAt(tabbedPane.indexOfComponent(plusPanel), buildPlusLabel())
		tabbedPane.addChangeListener {
			updateCloseButtons()
			if (!adjusting && tabbedPane.selectedComponent === plusPanel) {
				addSpecTab()
			}
		}
		restoreTabs()
		startHitsFlush()
	}

	/** Restores persisted tabs, or creates an initial empty tab when there is nothing to restore. */
	private fun restoreTabs() {
		val restored = persistence.restore()
		if (restored.isEmpty()) {
			addSpecTab()
			return
		}
		restored.forEach { data ->
			model.addRestored(data)
			installTab(data)
		}
		tabbedPane.selectedIndex = 0
		updateCloseButtons()
		persistence.saveOrder(model.tabs)
	}

	/** Periodically (and on unload) persists hit counts, only when they changed. */
	private fun startHitsFlush() {
		Timer(HITS_FLUSH_MS) { flushHits() }.apply { isRepeats = true }.start()
		AppContext.api.extension().registerUnloadingHandler { flushHits() }
	}

	private fun flushHits() {
		if (!hitsDirty) return
		hitsDirty = false
		model.tabs.forEach { persistence.saveTab(it) }
	}

	/** Shows the close button only on the currently selected tab. */
	private fun updateCloseButtons() {
		val selected = tabbedPane.selectedIndex
		for (i in 0 until tabbedPane.tabCount) {
			(tabbedPane.getTabComponentAt(i) as? EditableTabTitle)?.setCloseVisible(i == selected)
		}
	}

	/** A larger "+" label used as the add-tab button. */
	private fun buildPlusLabel(): JLabel = JLabel("+").apply {
		font = font.deriveFont(Font.BOLD, 18f)
		border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
		toolTipText = "New tab"
		addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				addSpecTab()
			}
		})
	}

	/**
	 * Sends spec content (e.g. a response body from Burp) to the first tab that has not loaded a
	 * spec yet, creating a new tab if every existing one is already in use.
	 */
	fun sendSpecContent(content: String) {
		val target = model.firstEmptyTab()?.let(::viewFor) ?: addSpecTab()
		val idx = tabbedPane.indexOfComponent(target.component)
		if (idx >= 0) tabbedPane.selectedIndex = idx
		target.loadFromString(content)
	}

	/** The view backing the given data, or null if it has no tab (should not happen). */
	private fun viewFor(data: SpecTabData): SpecTab? =
		tabsByComponent.values.firstOrNull { it.data === data }

	private fun addSpecTab(): SpecTab {
		val data = model.newTab()
		val specTab = installTab(data)
		persistence.saveTab(data)
		persistence.saveOrder(model.tabs)
		return specTab
	}

	/** Builds the view and tab component for an existing [data] and inserts it before "+". */
	private fun installTab(data: SpecTabData): SpecTab {
		adjusting = true
		try {
			val specTab = SpecTab(data)
			val insertIndex = tabbedPane.indexOfComponent(plusPanel).takeIf { it >= 0 } ?: tabbedPane.tabCount
			tabbedPane.insertTab(data.name, null, specTab.component, null, insertIndex)
			tabsByComponent[specTab.component] = specTab

			val title = EditableTabTitle(tabbedPane, data, ::closeTab)
			tabbedPane.setTabComponentAt(insertIndex, title)
			specTab.onSpecLoaded = {
				title.refreshFromData()
				persistence.saveTab(data)
			}
			data.onChanged = { persistence.saveTab(data) }

			tabbedPane.selectedIndex = insertIndex
			updateCloseButtons()
			return specTab
		} finally {
			adjusting = false
		}
	}

	private fun closeTab(title: EditableTabTitle) {
		val idx = tabbedPane.indexOfTabComponent(title)
		if (idx < 0) return
		val specTab = tabsByComponent[tabbedPane.getComponentAt(idx)] ?: return

		// "Yes" is the default button so Enter confirms; Esc closes the dialog and cancels.
		val options = arrayOf("Yes", "No")
		val choice = JOptionPane.showOptionDialog(
			tabbedPane,
			"Close tab \"${specTab.data.name}\"?",
			"Close tab",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.QUESTION_MESSAGE,
			null,
			options,
			options[0],
		)
		if (choice != JOptionPane.YES_OPTION) return

		adjusting = true
		try {
			tabsByComponent.remove(specTab.component)
			model.remove(specTab.data)
			persistence.removeTab(specTab.data)
			tabbedPane.removeTabAt(idx)
			// If the "+" tab ended up selected, fall back to the nearest real tab.
			if (tabbedPane.selectedComponent === plusPanel) {
				val plusIndex = tabbedPane.indexOfComponent(plusPanel)
				if (plusIndex > 0) tabbedPane.selectedIndex = plusIndex - 1
			}
		} finally {
			adjusting = false
		}

		// Keep at least one real spec tab around.
		if (tabbedPane.tabCount <= 1) addSpecTab()
		persistence.saveOrder(model.tabs)
		updateCloseButtons()
	}

	companion object {
		private const val HITS_FLUSH_MS = 30_000
	}
}
