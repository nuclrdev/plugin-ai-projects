package dev.nuclr.plugin.core.ai.projects.agent.chat;

import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Everything that touches RSyntaxTextArea, and the only thing that does.
 *
 * <p>The lexers belong to Commander, not to this plugin: the shaded {@code nuclr.jar}
 * carries them for the editor screen and the plugin compiles against them. A host built
 * without the editor has none, and a conversation must still draw a reply that quotes
 * code. That is why the whole dependency is one class - {@link CodeHighlighter} names no
 * type from it in any signature, so a missing lexer library is one caught
 * {@link LinkageError} on the first call rather than a class that will not load, or a
 * method that cannot even be reflected over.
 */
final class SyntaxLexers {

	private SyntaxLexers() {
	}

	/**
	 * Whether this host has the lexers, and knows this particular language.
	 *
	 * @param style an RSyntaxTextArea syntax style, such as {@code text/java}
	 * @return whether tokens can be had for it
	 */
	static boolean knows(String style) {
		return TokenMakerFactory.getDefaultInstance().keySet().contains(style);
	}

	/**
	 * Colour the code, walking it a line at a time, since that is the unit a lexer reads.
	 *
	 * <p>The type the previous line ended on is carried into the next, which is what keeps
	 * a block comment or a triple-quoted string coloured to its end rather than only to
	 * the first newline.
	 *
	 * @param code    the code, as written
	 * @param style   the syntax style to read it as
	 * @param palette what colour each kind of token is drawn in
	 * @return escaped HTML
	 */
	static String colour(String code, String style, CodeHighlighter.Palette palette) {
		var maker = TokenMakerFactory.getDefaultInstance().getTokenMaker(style);
		var html = new StringBuilder();
		var lines = code.split("\n", -1);
		var carried = TokenTypes.NULL;
		for (var index = 0; index < lines.length; index++) {
			if (index > 0) {
				html.append('\n');
			}
			var characters = lines[index].toCharArray();
			for (var token = maker.getTokenList(new Segment(characters, 0, characters.length), carried, 0);
					token != null && token.getType() != TokenTypes.NULL; token = token.getNextToken()) {
				append(html, token.getLexeme(), colourOf(token.getType(), palette));
			}
			carried = lastTypeOn(maker, characters, carried);
		}
		return html.toString();
	}

	/**
	 * The token type a line ends in the middle of, for the line after it.
	 *
	 * <p>A lexer is entitled to refuse the question; an unfinished comment then ends with
	 * its line, which is a colour short rather than a reply short.
	 */
	private static int lastTypeOn(TokenMaker maker, char[] characters, int carried) {
		try {
			return maker.getLastTokenTypeOnLine(new Segment(characters, 0, characters.length), carried);
		} catch (RuntimeException e) {
			return TokenTypes.NULL;
		}
	}

	/** One token's text, escaped, wrapped in its colour when it has one. */
	private static void append(StringBuilder html, String lexeme, String colour) {
		if (lexeme == null || lexeme.isEmpty()) {
			return;
		}
		if (colour == null) {
			html.append(MiniMarkdown.escape(lexeme));
			return;
		}
		html.append("<font color=\"").append(colour).append("\">").append(MiniMarkdown.escape(lexeme))
				.append("</font>");
	}

	/**
	 * The colour for a token type, or {@code null} to leave it the colour of the text
	 * around it - which is what identifiers, whitespace and punctuation want.
	 */
	private static String colourOf(int type, CodeHighlighter.Palette palette) {
		return switch (type) {
			case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.LITERAL_BOOLEAN,
					TokenTypes.PREPROCESSOR -> palette.keyword();
			case TokenTypes.DATA_TYPE, TokenTypes.MARKUP_TAG_NAME -> palette.type();
			case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
					TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, TokenTypes.REGEX -> palette.string();
			case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
					TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> palette.number();
			case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
					TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT ->
				palette.comment();
			case TokenTypes.ANNOTATION, TokenTypes.MARKUP_TAG_ATTRIBUTE -> palette.annotation();
			case TokenTypes.FUNCTION -> palette.function();
			case TokenTypes.OPERATOR, TokenTypes.MARKUP_TAG_DELIMITER -> palette.operator();
			default -> null;
		};
	}
}
