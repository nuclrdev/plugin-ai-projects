package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;

import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * A glyph drawn as an icon, for the icon slot of a button, menu item, label,
 * table cell or frame title - where a picture belongs, rather than typed into
 * the text beside the words.
 *
 * <p>Filled from the glyph's outline instead of rendered into a bitmap, so it
 * stays sharp at any display scale and can take a colour. Java2D draws an emoji
 * font in one colour anyway; a tint is what lets a green "running" and a red
 * "failed" read apart before either is read.
 *
 * <p>Every glyph is fitted into the same square and centred on the ink it
 * actually draws, not on its font's metrics, so a column of them lines up even
 * when one came from the emoji font and the next from the interface font.
 */
public final class GlyphIcon implements Icon {

	/** How far a small symbol may be enlarged towards filling the square. */
	private static final double MAX_GROWTH = 1.35;

	/** How faint the icon of a disabled component is drawn. */
	private static final float DISABLED_ALPHA = 0.38f;

	private final String glyph;
	private final int size;
	private final Color tint;
	private final Shape shape;
	private final boolean faint;

	/**
	 * An icon for a glyph.
	 *
	 * @param glyph the characters to draw
	 * @param font  a font that can draw them
	 * @param size  the side of the square, in pixels
	 * @param tint  the colour, or {@code null} for the component's own foreground
	 */
	GlyphIcon(String glyph, Font font, int size, Color tint) {
		this(glyph, size, tint, fit(glyph, font, size), false);
	}

	private GlyphIcon(String glyph, int size, Color tint, Shape shape, boolean faint) {
		this.glyph = glyph;
		this.size = size;
		this.tint = tint;
		this.shape = shape;
		this.faint = faint;
	}

	/** The characters this icon draws. */
	public String glyph() {
		return glyph;
	}

	/** The colour it is drawn in, or {@code null} when it follows the component. */
	public Color tint() {
		return tint;
	}

	/** Whether there turned out to be nothing to draw. */
	public boolean isBlank() {
		return shape == null;
	}

	/**
	 * The same icon, faded, for a disabled component.
	 *
	 * <p>Set explicitly rather than left to the look and feel: FlatLaf only knows
	 * how to fade an {@code ImageIcon}, and for any other icon a disabled menu item
	 * shows no picture at all.
	 *
	 * @return the faded form
	 */
	public GlyphIcon disabled() {
		return faint ? this : new GlyphIcon(glyph, size, tint, shape, true);
	}

	@Override
	public void paintIcon(Component component, Graphics graphics, int x, int y) {
		if (shape == null) {
			return;
		}
		var canvas = (Graphics2D) graphics.create();
		try {
			canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			if (faint || component != null && !component.isEnabled()) {
				canvas.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA));
			}
			canvas.setColor(tint != null ? tint : foreground(component));
			canvas.translate(x, y);
			canvas.fill(shape);
		} finally {
			canvas.dispose();
		}
	}

	@Override
	public int getIconWidth() {
		return size;
	}

	@Override
	public int getIconHeight() {
		return size;
	}

	private static Color foreground(Component component) {
		if (component != null && component.getForeground() != null) {
			return component.getForeground();
		}
		var themed = UIManager.getColor("Label.foreground");
		return themed != null ? themed : Color.GRAY;
	}

	/**
	 * The glyph's outline, scaled and centred in the square.
	 *
	 * @return the shape, or {@code null} when the glyph draws nothing
	 */
	static Shape fit(String glyph, Font font, int size) {
		if (glyph == null || glyph.isEmpty() || font == null || size <= 0) {
			return null;
		}
		try {
			// A variation selector asks for emoji presentation; as a character of its own
			// most fonts would draw it as a missing-glyph box beside the picture.
			var text = glyph.replace("️", "");
			var vector = font.deriveFont(Font.PLAIN, (float) size)
					.createGlyphVector(new FontRenderContext(null, true, true), text);
			var outline = vector.getOutline();
			var bounds = outline.getBounds2D();
			if (bounds.isEmpty()) {
				return null;
			}
			// A pixel of air on each side, and small symbols grown only so far: a dot
			// blown up to the size of a folder would shout.
			var room = size - 2.0;
			var scale = Math.min(MAX_GROWTH, room / Math.max(bounds.getWidth(), bounds.getHeight()));
			var placement = new AffineTransform();
			placement.translate(size / 2.0, size / 2.0);
			placement.scale(scale, scale);
			placement.translate(-bounds.getCenterX(), -bounds.getCenterY());
			return placement.createTransformedShape(outline);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
