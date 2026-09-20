package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;

import org.junit.jupiter.api.Test;

/** Colour for fenced code, and what happens to code nobody can colour. */
class CodeHighlighterTest {

	private static final Color DARK_PAGE = new Color(0x2B, 0x2B, 0x2B);
	private static final Color LIGHT_PAGE = Color.WHITE;

	/** The text a reader sees, with the colouring taken back out. */
	private static String plainText(String html) {
		return html.replaceAll("<font color=\"[^\"]*\">", "").replace("</font>", "")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
	}

	@Test
	void aKeywordIsColouredAndTheCodeItselfIsUnchanged() {

		var html = new CodeHighlighter(DARK_PAGE).toHtml("public int x = 1;", "java");

		assertTrue(html.contains("<font color="), "nothing was coloured: " + html);
		assertEquals("public int x = 1;", plainText(html));
	}

	@Test
	void theSameCodeIsColouredDifferentlyOnALightPage() {

		// The palette follows the page, so a theme change is not a blob of dark blue
		// keywords on white - or the reverse.
		var dark = new CodeHighlighter(DARK_PAGE).toHtml("public int x;", "java");
		var light = new CodeHighlighter(LIGHT_PAGE).toHtml("public int x;", "java");

		assertFalse(dark.equals(light), "both pages coloured the code the same way");
		assertEquals(plainText(dark), plainText(light));
	}

	@Test
	void aFenceWithNoLanguageIsLeftAlone() {

		// Nothing to lex, so nothing is guessed: plain escaped text, as before.
		var html = new CodeHighlighter(DARK_PAGE).toHtml("public int x;", null);

		assertEquals("public int x;", html);
	}

	@Test
	void aLanguageNobodyHasALexerForIsLeftAlone() {
		assertEquals("some words", new CodeHighlighter(DARK_PAGE).toHtml("some words", "nonesuch"));
	}

	@Test
	void markupInTheCodeStaysTextRatherThanBecomingTags() {

		// The escaping this class owes MiniMarkdown: an agent quoting HTML must not have
		// it drawn as HTML, coloured or not.
		var coloured = new CodeHighlighter(DARK_PAGE).toHtml("var x = \"<b>&</b>\";", "java");
		var plain = new CodeHighlighter(DARK_PAGE).toHtml("var x = \"<b>&</b>\";", null);

		for (var html : new String[] { coloured, plain }) {
			assertFalse(html.contains("<b>"), "markup survived into the page: " + html);
			assertTrue(html.contains("&lt;b&gt;"), html);
			assertTrue(html.contains("&amp;"), html);
		}
	}

	@Test
	void anAliasIsUnderstoodAndSoIsTheNameItStandsFor() {

		// "sh" and "bash" are the same lexer; an agent writes whichever it likes.
		assertTrue(new CodeHighlighter(DARK_PAGE).toHtml("if [ -f x ]; then echo hi; fi", "bash")
				.contains("<font color="));
		assertTrue(new CodeHighlighter(DARK_PAGE).toHtml("if [ -f x ]; then echo hi; fi", "sh")
				.contains("<font color="));
	}

	@Test
	void aFenceCarryingMoreThanALanguageStillFindsItsLexer() {
		assertTrue(new CodeHighlighter(DARK_PAGE).toHtml("public int x;", "java title=\"Example.java\"")
				.contains("<font color="));
	}

	@Test
	void aCommentRunningOverSeveralLinesStaysAComment() {

		// The lexer reads one line at a time, so the type each line ends on has to be
		// carried into the next; without that the second line comes out as code.
		var html = new CodeHighlighter(DARK_PAGE).toHtml("/* one\n two */\nint x;", "java");
		var lines = html.split("\n", -1);

		assertEquals("/* one\n two */\nint x;", plainText(html));
		assertEquals(3, lines.length, html);
		var opening = lines[0].substring(0, lines[0].indexOf('>') + 1);
		assertTrue(lines[1].startsWith(opening),
				"the comment's second line is not the colour its first line was: " + html);
	}

	/**
	 * Loads this plugin's own classes afresh, with RSyntaxTextArea hidden, as a Commander
	 * built without the editor would.
	 */
	private static final class WithoutRSyntaxTextArea extends ClassLoader {

		private final java.net.URLClassLoader plugin;

		WithoutRSyntaxTextArea() throws Exception {
			super(null);
			plugin = new java.net.URLClassLoader(new java.net.URL[] {
					CodeHighlighter.class.getProtectionDomain().getCodeSource().getLocation() }, null);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.fife.")) {
				throw new ClassNotFoundException(name + " is not in this host");
			}
			if (!name.startsWith("dev.nuclr.")) {
				return ClassLoader.getSystemClassLoader().loadClass(name);
			}
			var found = findLoadedClass(name);
			if (found != null) {
				return found;
			}
			try (var bytes = plugin.getResourceAsStream(name.replace('.', '/') + ".class")) {
				var code = bytes.readAllBytes();
				return defineClass(name, code, 0, code.length);
			} catch (java.io.IOException | NullPointerException e) {
				throw new ClassNotFoundException(name, e);
			}
		}
	}

	@Test
	void aHostWithoutRSyntaxTextAreaStillShowsTheCode() throws Exception {

		// The lexers belong to Commander, not to this plugin. An older host that has none
		// must cost the reply its colour and nothing else.
		var loaded = new WithoutRSyntaxTextArea()
				.loadClass("dev.nuclr.plugin.core.ai.projects.agent.chat.CodeHighlighter");
		var constructor = loaded.getDeclaredConstructor(Color.class);
		constructor.setAccessible(true);
		var highlighter = constructor.newInstance(DARK_PAGE);
		var toHtml = loaded.getDeclaredMethod("toHtml", String.class, String.class);
		toHtml.setAccessible(true);

		assertEquals("var x = &quot;&lt;b&gt;&quot;;", toHtml.invoke(highlighter, "var x = \"<b>\";", "java"));
	}

	@Test
	void everyLineOfTheCodeSurvives() {

		// The colouring walks lines and rejoins them; a lost blank line would silently
		// reflow whatever an agent pasted.
		var code = "def a():\n\n    return 1\n";

		assertEquals(code, plainText(new CodeHighlighter(DARK_PAGE).toHtml(code, "python")));
	}
}
