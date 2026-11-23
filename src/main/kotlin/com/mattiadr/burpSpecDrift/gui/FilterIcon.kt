package com.mattiadr.burpSpecDrift.gui

import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * A small funnel (filter) glyph painted in the host component's foreground colour, so it follows
 * the look & feel / theme. It is drawn as an outline when [active] is false and filled when a
 * filter is applied, giving a clear on/off cue without relying on colour.
 */
class FilterIcon(private val size: Int = 14) : Icon {

	var active: Boolean = false

	override fun getIconWidth(): Int = size

	override fun getIconHeight(): Int = size

	override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
		val g2 = g.create() as Graphics2D
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			g2.color = c?.foreground ?: g2.color
			g2.translate(x, y)
			val s = size / 16.0
			val funnel = Path2D.Double().apply {
				moveTo(2.5 * s, 3.0 * s)
				lineTo(13.5 * s, 3.0 * s)
				lineTo(9.5 * s, 8.0 * s)
				lineTo(9.5 * s, 13.5 * s)
				lineTo(6.5 * s, 13.5 * s)
				lineTo(6.5 * s, 8.0 * s)
				closePath()
			}
			if (active) {
				g2.fill(funnel)
			} else {
				g2.stroke = java.awt.BasicStroke((1.3 * s).toFloat())
				g2.draw(funnel)
			}
		} finally {
			g2.dispose()
		}
	}
}
