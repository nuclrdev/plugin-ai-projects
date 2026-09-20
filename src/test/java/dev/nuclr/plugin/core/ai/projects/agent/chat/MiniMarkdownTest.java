package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The Markdown agents write, as HTML Swing can draw. */
class MiniMarkdownTest {

	@Test
	void paragraphsKeepTheirLineBreaksAndEscapeMarkup() {
		assertEquals("<p>a &lt;b&gt;<br>c</p><p>d</p>", MiniMarkdown.toHtml("a <b>\nc\n\nd"));
	}

	@Test
	void inlineCodeIsNotReadAsEmphasis() {
		assertEquals("<p><b>bold</b> <i>it</i> <code>**x**</code></p>", MiniMarkdown.toHtml("**bold** *it* `**x**`"));
	}

	@Test
	void fencesListsAndHeadings() {
		assertEquals("<h3>Title</h3><ul><li>one<li>two</ul><pre>x &lt; y</pre>",
				MiniMarkdown.toHtml("# Title\n- one\n- two\n```java\nx < y\n```"));
		assertEquals("<ol><li>first<li>second</ol>", MiniMarkdown.toHtml("1. first\n2. second"));
	}

	@Test
	void anOpenFenceMidStreamIsAlreadyCode() {
		assertEquals("<pre>partial</pre>", MiniMarkdown.toHtml("```\npartial"));
	}

	@Test
	void tablesAndLinks() {
		assertEquals("<table cellspacing=\"0\" cellpadding=\"3\" border=\"1\"><tr><th>a</th><th>b</th></tr>"
				+ "<tr><td>1</td><td><a href=\"https://x.dev\">x</a></td></tr></table>",
				MiniMarkdown.toHtml("| a | b |\n|---|---|\n| 1 | [x](https://x.dev) |"));
	}

	@Test
	void snakeCaseIsNotItalic() {
		assertEquals("<p>some_file_name</p>", MiniMarkdown.toHtml("some_file_name"));
	}
}
