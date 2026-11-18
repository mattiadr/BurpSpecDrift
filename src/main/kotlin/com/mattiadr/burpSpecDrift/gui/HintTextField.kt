package com.mattiadr.burpSpecDrift.gui

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JTextField

/** A text field that paints a muted placeholder [hint] whenever it is empty. */
class HintTextField(columns: Int) : JTextField(columns) {

	var hint: String = ""
		set(value) {
			field = value
			repaint()
		}

	override fun paintComponent(g: Graphics) {
		super.paintComponent(g)
		if (text.isNotEmpty() || hint.isEmpty()) return

		val g2 = g.create() as Graphics2D
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			g2.color = Color.GRAY
			val metrics = g2.fontMetrics
			val x = insets.left + 2
			val y = (height - metrics.height) / 2 + metrics.ascent
			g2.drawString(hint, x, y)
		} finally {
			g2.dispose()
		}
	}
}
