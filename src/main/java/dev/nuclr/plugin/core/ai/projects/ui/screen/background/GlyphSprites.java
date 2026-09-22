package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Glyphs rendered once into small images, so an effect that draws thousands of
 * characters a frame blits pictures rather than setting type.
 *
 * <p>Drawing the text itself every frame leaked native memory on macOS - about a
 * gigabyte a minute of it. Every character went through CoreText, and the
 * katakana in the code rain are not in the Monospaced font there, so each of
 * them also went through font fallback, thousands of times a second. Images are
 * the one thing every other effect already draws every frame without trouble, and
 * rendering each glyph once turns thousands of text draws a second into a few
 * hundred, made once and kept.
 *
 * <p>Sprites are made at the device scale the graphics carries, so text stays
 * sharp on a Retina display, and made again if the desktop moves to a screen
 * with another scale. One set is kept per colour and font size, made the first
 * time a column asks for it.
 */
final class GlyphSprites {

	/** Room round each glyph, in logical pixels, for antialiasing that spills past its advance. */
	private static final int PAD = 1;

	private final char[] glyphs;
	private final Font[] fonts;
	private final Color[] colors;

	private double scale;
	/** Indexed by colour, then font size, then glyph; {@code null} until first drawn. */
	private Sprite[][][] sprites;

	/**
	 * @param glyphs every character that will be drawn
	 * @param fonts  the fonts, indexed by the size a caller names
	 * @param colors the colours, indexed by the number a caller names
	 */
	GlyphSprites(String glyphs, Font[] fonts, Color[] colors) {
		this.glyphs = glyphs.toCharArray();
		this.fonts = fonts.clone();
		this.colors = colors.clone();
	}

	/** How many glyphs there are; a glyph is named by its index below this. */
	int count() {
		return glyphs.length;
	}

	/**
	 * Draw one glyph with its baseline at a point, as {@code drawChars} would.
	 *
	 * @param g        where to draw, with whatever composite should apply
	 * @param color    which colour
	 * @param fontSize which font
	 * @param glyph    which glyph
	 * @param x        the left of the glyph
	 * @param baseline its baseline
	 */
	void draw(Graphics2D g, int color, int fontSize, int glyph, int x, int baseline) {
		var sprite = sprite(g, color, fontSize, glyph);
		if (sprite.image == null) {
			return;
		}
		g.drawImage(sprite.image, x - PAD, baseline - sprite.ascent - PAD, sprite.width, sprite.height, null);
	}

	/** Let every sprite go, for a reset or a closing desktop. */
	void discard() {
		sprites = null;
		scale = 0;
	}

	private Sprite sprite(Graphics2D g, int color, int fontSize, int glyph) {
		var deviceScale = Math.max(1.0, g.getTransform().getScaleX());
		if (sprites == null || deviceScale != scale) {
			scale = deviceScale;
			sprites = new Sprite[colors.length][fonts.length][];
		}
		var set = sprites[color][fontSize];
		if (set == null) {
			set = new Sprite[glyphs.length];
			sprites[color][fontSize] = set;
		}
		var sprite = set[glyph];
		if (sprite == null) {
			sprite = render(fonts[fontSize], colors[color], glyphs[glyph]);
			set[glyph] = sprite;
		}
		return sprite;
	}

	private Sprite render(Font font, Color color, char glyph) {
		var scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE);
		var measure = scratch.createGraphics();
		int advance;
		int ascent;
		int descent;
		try {
			var metrics = measure.getFontMetrics(font);
			advance = Math.max(1, metrics.charWidth(glyph));
			ascent = metrics.getAscent();
			descent = metrics.getDescent();
		} finally {
			measure.dispose();
		}
		var width = advance + PAD * 2;
		var height = ascent + descent + PAD * 2;
		BufferedImage image;
		try {
			image = new BufferedImage((int) Math.ceil(width * scale), (int) Math.ceil(height * scale),
					BufferedImage.TYPE_INT_ARGB_PRE);
		} catch (OutOfMemoryError | IllegalArgumentException e) {
			return new Sprite(null, 0, 0, 0);
		}
		var g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.scale(scale, scale);
			g.setFont(font);
			g.setColor(color);
			g.drawChars(new char[] { glyph }, 0, 1, PAD, PAD + ascent);
		} finally {
			g.dispose();
		}
		return new Sprite(image, ascent, width, height);
	}

	/** One glyph's picture, and where it sits against the baseline, in logical pixels. */
	private static final class Sprite {

		private final BufferedImage image;
		private final int ascent;
		private final int width;
		private final int height;

		private Sprite(BufferedImage image, int ascent, int width, int height) {
			this.image = image;
			this.ascent = ascent;
			this.width = width;
			this.height = height;
		}
	}
}
