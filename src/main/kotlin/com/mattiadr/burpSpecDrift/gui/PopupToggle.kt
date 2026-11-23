package com.mattiadr.burpSpecDrift.gui

import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Makes a dropdown behave like a toggle: clicking the same source while its popup is open closes it
 * instead of flickering closed-then-open. A click on the already-open source first dismisses the
 * popup (recording which source it belonged to and when); the follow-up "open" request for that
 * same source, arriving within [WINDOW_MS], is recognised as the closing half of the toggle and
 * suppressed. Each distinct [key] is tracked independently, so clicking a *different* source still
 * opens its popup normally.
 */
class PopupToggle<K : Any> {

	private var lastKey: K? = null
	private var lastClosedAt = 0L

	/** True if the popup for [key] should open now; false when this click should only close it. */
	fun requestOpen(key: K): Boolean {
		if (key == lastKey && System.currentTimeMillis() - lastClosedAt < WINDOW_MS) {
			lastKey = null
			return false
		}
		return true
	}

	/** Records [key]'s close time, so an immediate re-click on the same source toggles it off. */
	fun track(popup: JPopupMenu, key: K) {
		popup.addPopupMenuListener(object : PopupMenuListener {
			override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit

			override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
				lastKey = key
				lastClosedAt = System.currentTimeMillis()
			}

			override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
		})
	}

	companion object {
		/** How soon after a close a re-open of the same source counts as a toggle-off (ms). */
		private const val WINDOW_MS = 250L
	}
}
