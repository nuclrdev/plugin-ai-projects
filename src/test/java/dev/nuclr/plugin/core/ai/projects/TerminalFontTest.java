package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The font an agent terminal draws in.
 *
 * <p>Agent CLIs announce themselves with a banner built from Unicode block
 * glyphs, and draw their progress bars from the same range. A font missing any
 * of them, or drawing them at a width other than one cell, turns that banner
 * into rubble while leaving the words beside it perfect - which is exactly how
 * the bug this covers presented.
 */
class TerminalFontTest {

	/** {@code TerminalTheme} is package-private in the plugin, so this reaches it reflectively. */
	private static Method method(String name, Class<?>... parameters) throws Exception {
		var type = Class.forName("dev.nuclr.plugin.core.ai.projects.agent.terminal.TerminalTheme");
		var method = type.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	private static boolean drawsBlockGlyphs(Font font) throws Exception {
		return (boolean) method("drawsBlockGlyphs", Font.class).invoke(null, font);
	}

	private static String monospacedFamily() throws Exception {
		return (String) method("monospacedFamily").invoke(null);
	}

	/**
	 * Families this test knows are terminal fonts, used only to establish whether this
	 * machine has one at all. Deliberately not the list under test: a test that asks
	 * the code what it should have found proves nothing.
	 */
	private static final List<String> KNOWN_TERMINAL_FAMILIES = List.of(
			"Menlo", "SF Mono", "JetBrains Mono", "Cascadia Mono", "Cascadia Code",
			"DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono", "Ubuntu Mono");

	private static boolean installed(String family) {
		return family.equalsIgnoreCase(new Font(family, Font.PLAIN, 14).getFamily());
	}

	@Test
	void aCapableFamilyIsChosenWheneverThisMachineHasOne() throws Exception {

		// The invariant that actually fails if the font override is dropped. Without it
		// every other assertion here holds vacuously on a machine with no suitable font,
		// which is indistinguishable from the bug being back.
		String available = null;
		for (var candidate : KNOWN_TERMINAL_FAMILIES) {
			if (installed(candidate) && drawsBlockGlyphs(new Font(candidate, Font.PLAIN, 14))) {
				available = candidate;
				break;
			}
		}
		if (available == null) {
			return;
		}
		assertFalse(Font.MONOSPACED.equals(monospacedFamily()),
				available + " can draw a banner and is installed, so it should have been"
						+ " chosen rather than falling back to the logical monospaced font");
	}

	@Test
	void theChosenFamilyCanDrawEveryBlockGlyphAtCellWidth() throws Exception {

		var family = monospacedFamily();
		assertNotNull(family);

		var font = new Font(family, Font.PLAIN, 14);
		if (!family.equalsIgnoreCase(font.getFamily())) {
			// Nothing suitable is installed on this machine, so the logical font stands
			// in and there is nothing to assert about its coverage. Whether falling back
			// was legitimate is the test above.
			assertEquals(Font.MONOSPACED, family);
			return;
		}
		assertTrue(drawsBlockGlyphs(font),
				family + " cannot draw the block glyphs an agent banner is built from");
	}

	@Test
	void aFontMissingTheQuadrantBlocksIsRejected() throws Exception {

		// Consolas is JediTerm's own default on Windows, and the reason agent banners
		// rendered as rubble: it carries the half blocks but none of U+2596..U+259F.
		var consolas = new Font("Consolas", Font.PLAIN, 14);
		if (!"Consolas".equalsIgnoreCase(consolas.getFamily())) {
			return;
		}
		assertFalse(consolas.canDisplay(0x259B), "the premise of this test has changed");
		assertFalse(drawsBlockGlyphs(consolas));
		assertFalse("Consolas".equalsIgnoreCase(monospacedFamily()),
				"a font that cannot draw a banner must not be chosen to draw one");
	}

	@Test
	void theLogicalMonospacedFontIsNotAssumedToBeUniform() throws Exception {

		// It has the glyphs but not necessarily at one cell each - on Windows its full
		// block is half again the width of its M - so it is a fallback, never a choice.
		var logical = new Font(Font.MONOSPACED, Font.PLAIN, 14);
		if (drawsBlockGlyphs(logical)) {
			return;
		}
		assertFalse(Font.MONOSPACED.equals(monospacedFamily()),
				"a concrete family should have been preferred over a non-uniform logical one");
	}

	@Test
	void theSettingsProviderHandsJediTermThatFamily() throws Exception {

		var provider = method("settingsProvider").invoke(null);
		var getTerminalFont = provider.getClass().getMethod("getTerminalFont");
		getTerminalFont.setAccessible(true);
		var font = (Font) getTerminalFont.invoke(provider);

		assertNotNull(font);
		assertTrue(font.getSize() > 0);
		var family = monospacedFamily();
		if (family.equalsIgnoreCase(new Font(family, Font.PLAIN, 14).getFamily())) {
			assertEquals(family, font.getName());
		}
	}
}
