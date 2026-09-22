package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** Glyphs drawn from cached pictures land where {@code drawChars} would put them. */
class GlyphSpritesTest {

	private static final Font[] FONTS = { new Font(Font.MONOSPACED, Font.PLAIN, 16) };

	private static int litPixels(BufferedImage image, int fromX, int toX, int fromY, int toY) {
		var lit = 0;
		for (var y = Math.max(0, fromY); y < Math.min(image.getHeight(), toY); y++) {
			for (var x = Math.max(0, fromX); x < Math.min(image.getWidth(), toX); x++) {
				if ((image.getRGB(x, y) >>> 24) != 0) {
					lit++;
				}
			}
		}
		return lit;
	}

	@Test
	void aGlyphIsDrawnAboveItsBaselineInItsColour() {

		var sprites = new GlyphSprites("A#", FONTS, new Color[] { Color.GREEN });
		var canvas = new BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB);
		var g = canvas.createGraphics();
		try {
			sprites.draw(g, 0, 0, 0, 20, 40);
		} finally {
			g.dispose();
		}

		assertTrue(litPixels(canvas, 18, 34, 24, 41) > 10, "nothing drawn above the baseline");
		assertEquals(0, litPixels(canvas, 0, 60, 0, 20), "drawn far above where a 16pt glyph reaches");
		var sample = 0;
		for (var y = 24; y < 41 && sample == 0; y++) {
			for (var x = 18; x < 34 && sample == 0; x++) {
				if ((canvas.getRGB(x, y) >>> 24) == 0xFF) {
					sample = canvas.getRGB(x, y);
				}
			}
		}
		assertEquals(Color.GREEN.getRGB(), sample);
	}

	@Test
	void aScaledScreenGetsGlyphsAtItsOwnResolutionInTheSamePlace() {

		var sprites = new GlyphSprites("A", FONTS, new Color[] { Color.WHITE });
		var plain = new BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB);
		var retina = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		var g = plain.createGraphics();
		sprites.draw(g, 0, 0, 0, 20, 40);
		g.dispose();
		var scaled = retina.createGraphics();
		scaled.scale(2, 2);
		sprites.draw(scaled, 0, 0, 0, 20, 40);
		scaled.dispose();

		// Twice the size each way, so about four times the pixels, over the same logical spot.
		var small = litPixels(plain, 0, 60, 0, 60);
		var large = litPixels(retina, 36, 68, 48, 82);
		assertTrue(large > small * 2, "the 2x glyph was not drawn at 2x: " + small + " vs " + large);
	}
}
