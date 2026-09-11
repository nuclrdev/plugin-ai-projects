package dev.nuclr.plugin.core.ai.projects.ui;

/**
 * A table or list cell's value: words, and the glyph that goes in the cell's
 * icon slot beside them.
 *
 * <p>{@link #toString} is the words alone, which is what a row sorter compares
 * and what a copy from the table should carry.
 *
 * @param glyph the glyph, possibly {@code null} or empty for none
 * @param text  the words, possibly {@code null}
 */
public record GlyphText(String glyph, String text) {

	@Override
	public String toString() {
		return text == null ? "" : text;
	}
}
