package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * The composer's grey text is drawn by the area itself: FlatLaf paints a placeholder in
 * a text field but not in a text area, so a client property alone shows nothing.
 */
class PlaceholderTextAreaTest {

	/** The area as painted, empty or holding {@code text}. */
	private static BufferedImage painted(String placeholder, String text) throws Exception {
		var image = new BufferedImage(300, 60, BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() -> {
			var area = new PlaceholderTextArea(3, 40);
			area.setSize(300, 60);
			area.setPlaceholder(placeholder);
			area.setText(text);
			var g = image.createGraphics();
			area.paint(g);
			g.dispose();
		});
		return image;
	}

	private static boolean same(BufferedImage a, BufferedImage b) {
		for (var x = 0; x < a.getWidth(); x++) {
			for (var y = 0; y < a.getHeight(); y++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					return false;
				}
			}
		}
		return true;
	}

	@Test
	void anEmptyAreaDrawsItsPlaceholder() throws Exception {
		assertFalse(same(painted("Commit it   (Tab)", ""), painted("", "")), "the grey text is drawn");
	}

	@Test
	void anAreaWithTextDrawsNoPlaceholder() throws Exception {
		assertTrue(same(painted("Commit it   (Tab)", "typed"), painted("", "typed")));
	}

	@Test
	void thePlaceholderIsFadedTowardTheBackgroundInEitherTheme() {
		var grey = new java.awt.Color(128, 128, 128);
		var onDark = PlaceholderTextArea.blend(grey, java.awt.Color.BLACK, PlaceholderTextArea.FADE);
		var onLight = PlaceholderTextArea.blend(grey, java.awt.Color.WHITE, PlaceholderTextArea.FADE);

		assertTrue(onDark.getRed() < grey.getRed(), "darker on a dark background: " + onDark);
		assertTrue(onLight.getRed() > grey.getRed(), "lighter on a light background: " + onLight);
		assertEquals(grey, PlaceholderTextArea.blend(grey, null, PlaceholderTextArea.FADE));
	}

	@Test
	void thePlaceholderWrapsAtWords() {
		var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
				.getFontMetrics(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		var width = metrics.stringWidth("aaa bbb");
		assertEquals(List.of("aaa bbb", "ccc"), PlaceholderTextArea.wrap("aaa bbb ccc", width, metrics));
		assertEquals(List.of("toolongforaline"), PlaceholderTextArea.wrap("toolongforaline", 5, metrics));
	}
}
