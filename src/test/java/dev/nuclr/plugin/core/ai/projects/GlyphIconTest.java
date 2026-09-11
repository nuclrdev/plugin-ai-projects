package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphCellRenderer;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphIcon;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphText;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The icon form of a glyph: that it lands in the icon slot rather than in the
 * words, that it actually puts ink on the screen and stays inside its square,
 * and that it survives being disabled.
 */
class GlyphIconTest {

	/** The robot, built from its code point so no surrogate pair is typed here. */
	private static final String ROBOT = new String(Character.toChars(0x1F916));

	@AfterEach
	void clearOverrides() {
		UIManager.put("Label.font", null);
	}

	/**
	 * Every glyph the plugin declares that is more than plain ASCII - the stand-ins
	 * such as "+" or "..." turn up in ordinary words.
	 */
	static List<String> declaredGlyphs() throws IllegalAccessException {
		var glyphs = new ArrayList<String>();
		for (var field : Glyphs.class.getFields()) {
			if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())
					|| field.getName().equals("MODE_PROPERTY") || field.getName().equals("SIDEBAR_SEPARATOR")) {
				continue;
			}
			var value = (String) field.get(null);
			if (value.chars().anyMatch(c -> c > 0x7F)) {
				glyphs.add(value);
			}
		}
		return glyphs;
	}

	private static boolean somethingCanDraw(String glyph) {
		var bare = glyph.replace("️", "");
		return new Font(Font.SANS_SERIF, Font.PLAIN, 12).canDisplayUpTo(bare) == -1
				|| Glyphs.span(glyph).contains("font-family");
	}

	/** Paint an icon onto a transparent canvas with a margin round it, so ink outside its square shows. */
	private static BufferedImage paint(Icon icon, Component component, int margin) {
		var image = new BufferedImage(icon.getIconWidth() + 2 * margin, icon.getIconHeight() + 2 * margin,
				BufferedImage.TYPE_INT_ARGB);
		var graphics = image.createGraphics();
		try {
			icon.paintIcon(component, graphics, margin, margin);
		} finally {
			graphics.dispose();
		}
		return image;
	}

	private static int alpha(BufferedImage image, int x, int y) {
		return image.getRGB(x, y) >>> 24;
	}

	private static int ink(BufferedImage image) {
		var inked = 0;
		for (var y = 0; y < image.getHeight(); y++) {
			for (var x = 0; x < image.getWidth(); x++) {
				if (alpha(image, x, y) != 0) {
					inked++;
				}
			}
		}
		return inked;
	}

	private static int strongestAlpha(BufferedImage image) {
		var strongest = 0;
		for (var y = 0; y < image.getHeight(); y++) {
			for (var x = 0; x < image.getWidth(); x++) {
				strongest = Math.max(strongest, alpha(image, x, y));
			}
		}
		return strongest;
	}

	// ------------------------------------------------------------------ widgets

	@Test
	void aButtonGetsItsGlyphInTheIconSlotAndPlainWords() {
		var button = Glyphs.decorate(new JButton(), ROBOT, "New agent");
		assertEquals("New agent", button.getText());
		assertInstanceOf(GlyphIcon.class, button.getIcon());
		assertFalse(button.getText().contains(ROBOT), "the glyph should not be in the words as well");
	}

	@Test
	void aMenuItemGetsItsGlyphInTheIconSlot() {
		var item = Glyphs.decorate(new JMenuItem(), Glyphs.SAVE, "Save layout");
		assertEquals("Save layout", item.getText());
		assertNotNull(item.getIcon());
	}

	@Test
	void aDisabledItemKeepsAFadedPictureRatherThanLosingIt() {

		// FlatLaf only fades an ImageIcon; left to itself, a disabled menu item with any
		// other icon would show none.
		var item = Glyphs.decorate(new JMenuItem(), Glyphs.CLEAR, "Clear screen");
		item.setEnabled(false);
		assertNotNull(item.getDisabledIcon());
		assertNotSame(item.getIcon(), item.getDisabledIcon());

		Assumptions.assumeTrue(somethingCanDraw(Glyphs.CLEAR), "nothing here draws the sparkles");
		var live = strongestAlpha(paint(item.getIcon(), null, 0));
		var faded = strongestAlpha(paint(item.getDisabledIcon(), null, 0));
		assertTrue(faded < live, "disabled " + faded + " should be fainter than enabled " + live);
	}

	@Test
	void redecoratingSwapsThePictureAndTheWords() {
		var button = Glyphs.decorate(new JButton(), Glyphs.START, "Start");
		Glyphs.decorate(button, Glyphs.STOP, "Stop");
		assertEquals("Stop", button.getText());
		assertSame(Glyphs.icon(Glyphs.STOP), button.getIcon());
		assertEquals(Glyphs.icon(Glyphs.STOP).glyph(), ((GlyphIcon) button.getDisabledIcon()).glyph());
	}

	@Test
	void noGlyphMeansNoIcon() {
		var button = Glyphs.decorate(new JButton(), Glyphs.START, "Start");
		Glyphs.decorate(button, null, "Start");
		assertNull(button.getIcon());
		assertNull(button.getDisabledIcon());
		assertNull(Glyphs.icon(""));
	}

	@Test
	void aLabelWorksTheSameWay() {
		var label = Glyphs.decorate(new JLabel(), Glyphs.CONTEXT, "Resolved context");
		assertEquals("Resolved context", label.getText());
		assertNotNull(label.getIcon());
		assertNotNull(label.getDisabledIcon());
	}

	// ------------------------------------------------------------------ drawing

	@Test
	void anIconIsASquareSizedFromTheInterfaceFont() {
		UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		assertEquals(16, Glyphs.iconSize());
		var icon = Glyphs.icon(Glyphs.AGENT);
		assertEquals(Glyphs.iconSize(), icon.getIconWidth());
		assertEquals(icon.getIconWidth(), icon.getIconHeight());

		// A theme with a larger font gets larger pictures, rather than 16 pixels beside 18-point words.
		UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 18));
		assertEquals(24, Glyphs.iconSize());
	}

	@Test
	void anEmojiIconActuallyPutsInkOnTheCanvas() {
		Assumptions.assumeTrue(somethingCanDraw(ROBOT), "no font here has the robot");
		assertTrue(ink(paint(Glyphs.icon(ROBOT, 16), null, 0)) > 20, "the robot drew next to nothing");
	}

	@Test
	void everyDeclaredGlyphDrawsInsideItsSquareAndNowhereElse() throws IllegalAccessException {

		var margin = 8;
		for (var glyph : declaredGlyphs()) {
			if (!somethingCanDraw(glyph)) {
				continue;
			}
			var icon = Glyphs.icon(glyph, 16);
			var hex = "U+" + Integer.toHexString(glyph.codePointAt(0)).toUpperCase();
			assertFalse(icon.isBlank(), "nothing to draw for " + hex);

			var image = paint(icon, null, margin);
			var inside = 0;
			for (var y = 0; y < image.getHeight(); y++) {
				for (var x = 0; x < image.getWidth(); x++) {
					if (alpha(image, x, y) == 0) {
						continue;
					}
					var within = x >= margin && x < margin + 16 && y >= margin && y < margin + 16;
					assertTrue(within, hex + " spills out of its square at " + x + "," + y);
					inside++;
				}
			}
			assertTrue(inside > 0, hex + " put no ink down");
		}
	}

	@Test
	void onWindowsTheEmojiThemselvesAreTracedRatherThanTheirStandIns() throws IllegalAccessException {

		// Segoe UI Emoji keeps a monochrome outline under its colour layers, and that
		// outline is what the icon is filled from. Elsewhere an emoji font may hold only
		// colour bitmaps, where the stand-in is the right answer and this would not hold.
		Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "Windows only");
		var fellBack = new ArrayList<String>();
		for (var glyph : declaredGlyphs()) {
			if (somethingCanDraw(glyph) && !Glyphs.iconForm(glyph).equals(Glyphs.icon(glyph, 16).glyph())) {
				fellBack.add("U+" + Integer.toHexString(glyph.codePointAt(0)).toUpperCase());
			}
		}
		assertTrue(fellBack.isEmpty(), "drawn from the stand-in instead: " + fellBack);
	}

	@Test
	void aGlyphNothingCanDrawStillGivesASizedIconSoAMenuStaysAligned() {
		var icon = Glyphs.icon("￿", 16);
		assertNotNull(icon);
		assertTrue(icon.isBlank());
		assertEquals(16, icon.getIconWidth());
	}

	// ------------------------------------------------------------------- colour

	@Test
	void statusGlyphsAreColouredSoRunningAndFailedDifferAtAGlance() {
		var running = Glyphs.icon(Glyphs.RUNNING).tint();
		var failed = Glyphs.icon(Glyphs.FAILED).tint();
		assertNotNull(running);
		assertNotNull(failed);
		assertNotEquals(running, failed);
		assertNotEquals(Glyphs.icon(Glyphs.WAITING).tint(), running);
	}

	@Test
	void anUncolouredGlyphFollowsTheComponentsForeground() {

		assertNull(Glyphs.icon(Glyphs.COPY).tint(), "copy is meant to follow the theme");
		Assumptions.assumeTrue(somethingCanDraw(Glyphs.COPY), "nothing here draws the clipboard");

		var label = new JLabel();
		label.setForeground(Color.RED);
		var image = paint(Glyphs.icon(Glyphs.COPY, 16), label, 0);

		// The most solid pixel is the one whose colour is not diluted by antialiasing.
		var best = 0;
		var bestAlpha = -1;
		for (var y = 0; y < image.getHeight(); y++) {
			for (var x = 0; x < image.getWidth(); x++) {
				if (alpha(image, x, y) > bestAlpha) {
					bestAlpha = alpha(image, x, y);
					best = image.getRGB(x, y);
				}
			}
		}
		var colour = new Color(best, true);
		assertTrue(colour.getRed() > 200 && colour.getGreen() < 60 && colour.getBlue() < 60,
				"expected the label's red, got " + colour);
	}

	@Test
	void attentionOutranksStatus() {
		assertEquals(Glyphs.ATTENTION, Glyphs.statusGlyph(AgentStatus.RUNNING, true));
		assertEquals(Glyphs.RUNNING, Glyphs.statusGlyph(AgentStatus.RUNNING, false));
		assertEquals(Glyphs.STOPPED, Glyphs.statusGlyph(null, false));
	}

	// -------------------------------------------------------------------- cells

	@Test
	void aCellValueSortsAndCopiesAsItsWordsAlone() {
		assertEquals("Coder", new GlyphText(ROBOT, "Coder").toString());
		assertEquals("", new GlyphText(ROBOT, null).toString());
	}

	@Test
	void theCellRendererPutsTheGlyphInTheIconSlotAndClearsItForAPlainCell() {

		var table = new JTable(new Object[][] { { new GlyphText(Glyphs.TOOL, "Provider"), "plain" } },
				new Object[] { "Field", "Value" });
		var renderer = new GlyphCellRenderer();

		var first = (JLabel) renderer.getTableCellRendererComponent(table, table.getValueAt(0, 0),
				false, false, 0, 0);
		assertEquals("Provider", first.getText());
		assertNotNull(first.getIcon());

		// One renderer paints every cell, so the icon must not linger into the next.
		var second = (JLabel) renderer.getTableCellRendererComponent(table, table.getValueAt(0, 1),
				false, false, 0, 1);
		assertEquals("plain", second.getText());
		assertNull(second.getIcon(), "an icon left over from the previous cell");
	}
}
