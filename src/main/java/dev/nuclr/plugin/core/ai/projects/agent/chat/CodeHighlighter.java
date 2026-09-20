package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;

/**
 * Colour for the code in a fenced block, from RSyntaxTextArea's lexers.
 *
 * <p>The lexers only, not the widget. A reply is one editor pane re-rendered from scratch
 * a moment after every streamed chunk, so a real editor per code block would be built and
 * thrown away dozens of times while the agent types, taking its selection and scroll
 * position with it each time. Tokens cost a pass over the text and come out as coloured
 * spans in the HTML the pane already draws, which themes and zooms with everything else.
 *
 * <p>The palette is derived from the conversation's own background rather than from a
 * syntax theme file, so code sits in the reply instead of looking pasted into it.
 *
 * <p>Nothing here names a type from RSyntaxTextArea - {@link SyntaxLexers} holds all of
 * those - so a Commander without the lexers loses the colour and keeps the reply.
 */
final class CodeHighlighter {

	/**
	 * What a fence's info string means, as RSyntaxTextArea names it.
	 *
	 * <p>Only the aliases an agent actually writes. Anything else is tried against the
	 * lexers directly as {@code text/<name>}, so the other forty-odd languages work
	 * without being listed here, and an unknown word simply yields no colour.
	 */
	private static final Map<String, String> LANGUAGES = Map.ofEntries(
			Map.entry("sh", "text/unix"),
			Map.entry("bash", "text/unix"),
			Map.entry("zsh", "text/unix"),
			Map.entry("shell", "text/unix"),
			Map.entry("console", "text/unix"),
			Map.entry("cmd", "text/bat"),
			Map.entry("ps1", "text/powershell"),
			Map.entry("pwsh", "text/powershell"),
			Map.entry("js", "text/javascript"),
			Map.entry("jsx", "text/javascript"),
			Map.entry("ts", "text/typescript"),
			Map.entry("tsx", "text/typescript"),
			Map.entry("py", "text/python"),
			Map.entry("rb", "text/ruby"),
			Map.entry("rs", "text/rust"),
			Map.entry("go", "text/golang"),
			Map.entry("kt", "text/kotlin"),
			Map.entry("kts", "text/kotlin"),
			Map.entry("yml", "text/yaml"),
			Map.entry("md", "text/markdown"),
			Map.entry("h", "text/c"),
			Map.entry("hpp", "text/cpp"),
			Map.entry("c++", "text/cpp"),
			Map.entry("cc", "text/cpp"),
			Map.entry("csharp", "text/cs"),
			Map.entry("htm", "text/html"),
			Map.entry("diff", "text/plain"),
			Map.entry("patch", "text/plain"),
			Map.entry("text", "text/plain"),
			Map.entry("txt", "text/plain"));

	/** The colours one kind of token is drawn in, against one kind of background. */
	record Palette(String keyword, String type, String string, String number, String comment,
			String annotation, String function, String operator) {
	}

	/**
	 * On a dark background. Chosen to sit a step either side of the text around them
	 * rather than to shout: code is read here, not edited.
	 */
	private static final Palette DARK = new Palette("#cc7832", "#a9b7c6", "#6a8759", "#6897bb", "#808080",
			"#bbb529", "#ffc66d", "#a9b7c6");

	/** On a light background. */
	private static final Palette LIGHT = new Palette("#7f0055", "#000080", "#2a7f1f", "#1750eb", "#707070",
			"#808000", "#7a3e9d", "#000000");

	private final Palette palette;

	/**
	 * A highlighter for one background.
	 *
	 * @param background what the code is drawn on, which decides the palette
	 */
	CodeHighlighter(Color background) {
		this.palette = isDark(background) ? DARK : LIGHT;
	}

	/** Whether a background is dark enough to want light code on it. */
	private static boolean isDark(Color background) {
		if (background == null) {
			return true;
		}
		var luminance = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue();
		return luminance < 128;
	}

	/**
	 * The contents of a {@code <pre>}: the code, escaped, with its tokens coloured.
	 *
	 * @param code     the fenced code, as written
	 * @param language the fence's info string, or {@code null} when it carried none
	 * @return an HTML fragment
	 */
	String toHtml(String code, String language) {
		var style = styleOf(language);
		if (style == null) {
			return MiniMarkdown.escape(code);
		}
		try {
			return SyntaxLexers.colour(code, style, palette);
		} catch (LinkageError | RuntimeException e) {
			// No lexers in this host, or none that can read this text. Either way the
			// reply is worth more than the colour it is missing.
			return MiniMarkdown.escape(code);
		}
	}

	/**
	 * The syntax style for a fence's info string, or {@code null} when there is no lexer
	 * to read it with.
	 *
	 * <p>A fence may carry more than a language - {@code java title="X"} - so only the
	 * first word is read.
	 */
	private static String styleOf(String language) {
		if (language == null || language.isBlank()) {
			return null;
		}
		var name = language.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
		var style = LANGUAGES.getOrDefault(name, name.contains("/") ? name : "text/" + name);
		try {
			return SyntaxLexers.knows(style) ? style : null;
		} catch (LinkageError | RuntimeException e) {
			return null;
		}
	}
}
