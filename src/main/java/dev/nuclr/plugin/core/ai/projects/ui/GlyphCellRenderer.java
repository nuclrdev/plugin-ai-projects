package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Component;

import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * A table cell renderer that puts a {@link GlyphText}'s glyph in the icon slot
 * and draws every other value as the default renderer would.
 */
public class GlyphCellRenderer extends DefaultTableCellRenderer {

	private static final long serialVersionUID = 1L;

	@Override
	public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
			boolean focused, int row, int column) {

		super.getTableCellRendererComponent(table, value, selected, focused, row, column);
		// One renderer paints every cell, so an icon left over from the previous one
		// has to be cleared explicitly.
		Glyphs.decorate(this, value instanceof GlyphText cell ? cell.glyph() : null,
				value == null ? "" : value.toString());
		return this;
	}
}
