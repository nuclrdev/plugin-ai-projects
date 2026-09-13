package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;

import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The glyph layer, and the promise that it never puts a box on screen.
 *
 * <p>Java2D draws a character its font lacks as an empty rectangle rather than
 * falling back to another font, and the theme font Commander uses on Windows
 * contains none of these emoji. So the interesting cases here are the ones where
 * the interface font <em>cannot</em> draw the glyph: HTML such as the quick view
 * should still get the picture, through a font it names, while anything handed
 * to the host falls back to a plain symbol. The icon form, which the plugin's
 * own widgets use, is covered by {@link GlyphIconTest}.
 */
class GlyphsTest {

	/** The robot, built from its code point so no surrogate pair is typed here. */
	private static final String ROBOT = new String(Character.toChars(0x1F916));

	@AfterEach
	void clearOverrides() {
		System.clearProperty(Glyphs.MODE_PROPERTY);
		UIManager.put("Label.font", null);
	}

	/** Stand in for the theme font Commander actually uses on Windows. */
	private static void pretendTheFontIsSegoeUi() {
		UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 12));
	}

	private static boolean segoeUiIsInstalled() {
		return "Segoe UI".equals(new Font("Segoe UI", Font.PLAIN, 12).getFamily());
	}

	// ------------------------------------------------------------------ choosing

	@Test
	void theAutomaticChoiceIsOneOfTheTwoOffered() {
		var chosen = Glyphs.pick(ROBOT, "◆");
		assertTrue(ROBOT.equals(chosen) || "◆".equals(chosen), chosen);
	}

	@Test
	void emojiCanBeForcedForAFontTheCheckIsTooCautiousAbout() {
		System.setProperty(Glyphs.MODE_PROPERTY, "emoji");
		assertEquals(ROBOT, Glyphs.pick(ROBOT, "◆"));
	}

	@Test
	void symbolsCanBeForcedForATerminalOrThemeThatWantsThem() {
		System.setProperty(Glyphs.MODE_PROPERTY, "symbols");
		assertEquals("◆", Glyphs.pick(ROBOT, "◆"));
	}

	@Test
	void anUnrecognisedModeFallsBackToAsking() {
		System.setProperty(Glyphs.MODE_PROPERTY, "nonsense");
		var chosen = Glyphs.pick(ROBOT, "◆");
		assertTrue(ROBOT.equals(chosen) || "◆".equals(chosen), chosen);
	}

	@Test
	void askingTwiceGivesTheSameAnswer() {
		// The check is cached, so a glyph cannot change its mind between two labels
		// that are meant to match.
		assertEquals(Glyphs.pick(ROBOT, "◆"), Glyphs.pick(ROBOT, "◆"));
	}

	// -------------------------------------------------------------------- html

	@Test
	void aThemeFontWithNoEmojiStillGetsEmojiThroughANamedFont() {

		Assumptions.assumeTrue(segoeUiIsInstalled(), "no Segoe UI here to stand in for the theme font");
		assertFalse(new Font("Segoe UI", Font.PLAIN, 12).canDisplayUpTo(ROBOT) == -1,
				"Segoe UI is not supposed to contain the robot");

		pretendTheFontIsSegoeUi();
		var html = Glyphs.span(ROBOT);

		// Without the named font this would be a rectangle on screen.
		assertTrue(html.contains("font-family"), html);
		assertTrue(html.contains(ROBOT), html);
	}

	@Test
	void aFontThatCanDrawTheGlyphIsLeftAlone() {
		UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		assertEquals("◆", Glyphs.span("◆"));
	}

	// ------------------------------------------------------- host-facing text

	@Test
	void aPlainLabelNeverCarriesMarkupBecauseTheHostDrawsIt() {

		Assumptions.assumeTrue(segoeUiIsInstalled(), "no Segoe UI here to stand in for the theme font");
		pretendTheFontIsSegoeUi();

		var plain = Glyphs.label(Glyphs.AGENT, "Open");

		assertFalse(plain.contains("<"), plain);
		assertTrue(plain.endsWith("Open"), plain);
	}

	@Test
	void aGlyphIsPutInFrontWithOneSpace() {
		assertEquals("◆ Agents", Glyphs.label("◆", "Agents"));
	}

	@Test
	void aMissingHalfLeavesTheOtherAlone() {
		assertEquals("Agents", Glyphs.label(null, "Agents"));
		assertEquals("Agents", Glyphs.label("", "Agents"));
		assertEquals("◆", Glyphs.label("◆", null));
		assertEquals("◆", Glyphs.label("◆", ""));
		assertEquals("", Glyphs.label(null, null));
	}

	// ------------------------------------------------------------ sidebar form

	@Test
	void aSidebarLabelKeepsItsTwoHalvesApart() {
		var halves = Glyphs.splitSidebar(Glyphs.sidebar(ROBOT, "Coder"));
		assertEquals(ROBOT, halves[0]);
		assertEquals("Coder", halves[1]);
	}

	@Test
	void aLabelThatWasNeverJoinedSplitsIntoJustWords() {
		var halves = Glyphs.splitSidebar("Coder");
		assertEquals("", halves[0]);
		assertEquals("Coder", halves[1]);
		assertEquals("", Glyphs.splitSidebar(null)[1]);
	}

	@Test
	void aSidebarLabelWithNoGlyphIsJustTheWords() {
		assertEquals("Coder", Glyphs.sidebar(null, "Coder"));
		assertEquals("Coder", Glyphs.sidebar("", "Coder"));
	}

	// ---------------------------------------------------------------- coverage

	@Test
	void everyStatusHasAGlyph() {
		for (var status : AgentStatus.values()) {
			var glyph = Glyphs.forStatus(status);
			assertNotNull(glyph, status.name());
			assertFalse(glyph.isBlank(), status.name());
		}
		assertEquals(Glyphs.STOPPED, Glyphs.forStatus(null));
	}

	@Test
	void everyContextKindHasAGlyph() {
		for (var kind : ContextItem.Kind.values()) {
			var glyph = Glyphs.forContextKind(kind);
			assertNotNull(glyph, kind.name());
			assertFalse(glyph.isBlank(), kind.name());
		}
		assertEquals(Glyphs.CONTEXT, Glyphs.forContextKind(null));
	}

	@Test
	void aRunningAgentAndAStoppedOneDoNotLookTheSame() {
		assertFalse(Glyphs.RUNNING.equals(Glyphs.STOPPED));
		assertFalse(Glyphs.RUNNING.equals(Glyphs.FAILED));
		assertFalse(Glyphs.WAITING.equals(Glyphs.RUNNING));
	}

	@Test
	void everyDeclaredGlyphCanBeDrawnBySomething() {

		// The whole point of pick(): a declared glyph that nothing on this machine can
		// render would be a box wherever it appeared.
		for (var glyph : new String[] { Glyphs.RUNNING, Glyphs.STARTING, Glyphs.WAITING, Glyphs.STOPPED,
				Glyphs.FINISHED, Glyphs.FAILED, Glyphs.ATTENTION, Glyphs.AGENT, Glyphs.PROJECT,
				Glyphs.INSTRUCTION, Glyphs.SKILL, Glyphs.INJECTED, Glyphs.TOOL, Glyphs.ENVIRONMENT,
				Glyphs.PERMISSION, Glyphs.ROOT, Glyphs.VARIABLE, Glyphs.HARNESS, Glyphs.CONTEXT,
				Glyphs.NEW, Glyphs.START, Glyphs.STOP, Glyphs.RESTART, Glyphs.SEND, Glyphs.BROADCAST,
				Glyphs.DUPLICATE, Glyphs.FOLDER, Glyphs.SAVE, Glyphs.UNDO, Glyphs.REDO, Glyphs.RELOAD,
				Glyphs.RESET, Glyphs.TILE, Glyphs.CASCADE, Glyphs.WINDOWS, Glyphs.SIDEBAR, Glyphs.CLOSE,
				Glyphs.DELETE, Glyphs.EDIT, Glyphs.RENAME, Glyphs.REFRESH, Glyphs.FOCUS, Glyphs.COPY,
				Glyphs.CLEAR, Glyphs.ZOOM, Glyphs.MORE, Glyphs.TEMPLATE, Glyphs.TRANSCRIPT,
				Glyphs.MISSING, Glyphs.BACKGROUND }) {

			assertNotNull(glyph);
			assertFalse(glyph.isBlank());

			var drawable = new Font(Font.SANS_SERIF, Font.PLAIN, 12).canDisplayUpTo(
					glyph.replace("️", "")) == -1;
			var named = Glyphs.span(glyph).contains("font-family");
			assertTrue(drawable || named || glyph.codePointAt(0) < 0x2000,
					"nothing can draw " + glyph + " (U+"
							+ Integer.toHexString(glyph.codePointAt(0)).toUpperCase() + ")");
		}
	}
}
