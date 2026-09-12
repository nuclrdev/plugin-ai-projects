package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

/**
 * The faint horizontal banding two of the effects finish with.
 *
 * <p>Drawn as a tiled texture rather than a loop of lines. A line per five
 * pixels is a couple of hundred stroked, antialiased {@code drawLine} calls
 * across the full width of the desktop on every frame, for a pattern that
 * repeats every few pixels and never moves; one {@link TexturePaint} fill gives
 * the same picture in a single operation.
 *
 * <p>The tile is one pixel wide because the pattern does not vary horizontally,
 * and the paint is built once per colour and spacing rather than per frame.
 */
final class Scanlines {

	private final TexturePaint paint;

	/**
	 * Build a scanline pattern.
	 *
	 * @param color   the line colour, alpha included
	 * @param spacing pixels between lines; values below two are treated as two
	 */
	Scanlines(Color color, int spacing) {
		this(new Color[] { color }, spacing, 0);
	}

	/**
	 * Build a scanline pattern that cycles through several colours down the screen.
	 *
	 * <p>A pattern whose colours rotate over time is still only as many distinct
	 * patterns as it has colours, so an effect builds one of these per phase and
	 * picks between them per frame rather than drawing lines.
	 *
	 * @param colors  the colours, applied to successive lines
	 * @param spacing pixels between lines; values below two are treated as two
	 * @param phase   how far to rotate the colours down the pattern
	 */
	Scanlines(Color[] colors, int spacing, int phase) {
		var step = Math.max(2, spacing);
		var count = Math.max(1, colors.length);
		var tile = new BufferedImage(1, step * count, BufferedImage.TYPE_INT_ARGB_PRE);
		var g = tile.createGraphics();
		try {
			for (var line = 0; line < count; line++) {
				g.setColor(colors[Math.floorMod(line + phase, count)]);
				g.fillRect(0, line * step, 1, 1);
			}
		} finally {
			g.dispose();
		}
		this.paint = new TexturePaint(tile, new Rectangle(0, 0, 1, step * count));
	}

	/**
	 * Lay the pattern over an area.
	 *
	 * @param graphics where to paint
	 * @param width    the area's width
	 * @param height   the area's height
	 */
	void paint(Graphics2D graphics, int width, int height) {
		graphics.setPaint(paint);
		graphics.fillRect(0, 0, width, height);
	}
}
