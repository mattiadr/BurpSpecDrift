package com.mattiadr.burpSpecDrift.gui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that wraps its components onto multiple rows when the container is too narrow,
 * and reports a preferred/minimum height that accounts for those rows. Plain [FlowLayout] always
 * reports a single-row height, so inside a [java.awt.BorderLayout] region it gets clipped instead
 * of growing taller; this subclass fixes that. Based on Rob Camick's well-known WrapLayout.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

	override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

	override fun minimumLayoutSize(target: Container): Dimension =
		layoutSize(target, false).also { it.width -= hgap + 1 }

	private fun layoutSize(target: Container, preferred: Boolean): Dimension {
		synchronized(target.treeLock) {
			// Wrap against the container's own width; before it has been sized, walk up to the first
			// ancestor that has a width, and fall back to a single row (max width) if none does.
			var container: Container? = target
			while (container != null && container.size.width == 0 && container.parent != null) {
				container = container.parent
			}
			val targetWidth = (container?.size?.width ?: 0).let { if (it == 0) Int.MAX_VALUE else it }

			val insets = target.insets
			val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
			val maxWidth = targetWidth - horizontalInsetsAndGap

			val dim = Dimension(0, 0)
			var rowWidth = 0
			var rowHeight = 0
			for (i in 0 until target.componentCount) {
				val m = target.getComponent(i)
				if (!m.isVisible) continue
				val d = if (preferred) m.preferredSize else m.minimumSize
				if (rowWidth + d.width > maxWidth) {
					addRow(dim, rowWidth, rowHeight)
					rowWidth = 0
					rowHeight = 0
				}
				if (rowWidth != 0) rowWidth += hgap
				rowWidth += d.width
				rowHeight = maxOf(rowHeight, d.height)
			}
			addRow(dim, rowWidth, rowHeight)

			dim.width += horizontalInsetsAndGap
			dim.height += insets.top + insets.bottom + vgap * 2

			// A scroll pane viewport adds one extra gap; undo it so the content fits.
			if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null && target.isValid) {
				dim.width -= hgap + 1
			}
			return dim
		}
	}

	private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
		dim.width = maxOf(dim.width, rowWidth)
		if (dim.height > 0) dim.height += vgap
		dim.height += rowHeight
	}
}
