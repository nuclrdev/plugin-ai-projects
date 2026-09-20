package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown agents write, as the HTML 3.2 Swing's {@code HTMLEditorKit} draws.
 *
 * <p>Deliberately small: fenced code, headings, bullet and numbered lists, quotes,
 * tables, and inline code, bold, italics and links - what model replies are made
 * of. Everything is escaped first, so nothing an agent writes becomes markup it did
 * not ask for. A fence still open at the end - a reply mid-stream - renders as code
 * so far, which is what it will turn out to be.
 */
final class MiniMarkdown {

	private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
	private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
	private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
	private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");

	private MiniMarkdown() {
	}

	/**
	 * Render Markdown as an HTML fragment, without {@code <html>} or {@code <body>}.
	 *
	 * @param markdown the text
	 * @return the fragment
	 */
	static String toHtml(String markdown) {
		var lines = markdown.replace("\r\n", "\n").split("\n", -1);
		var html = new StringBuilder();
		var paragraph = new ArrayList<String>();
		var index = 0;
		while (index < lines.length) {
			var line = lines[index];
			var trimmed = line.strip();

			if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
				flushParagraph(paragraph, html);
				var fence = trimmed.substring(0, 3);
				var code = new StringBuilder();
				index++;
				while (index < lines.length && !lines[index].strip().startsWith(fence)) {
					if (!code.isEmpty()) {
						code.append('\n');
					}
					code.append(lines[index]);
					index++;
				}
				html.append("<pre>").append(escape(code.toString())).append("</pre>");
				index++;
				continue;
			}
			if (trimmed.isEmpty()) {
				flushParagraph(paragraph, html);
				index++;
				continue;
			}
			var heading = HEADING.matcher(trimmed);
			if (heading.matches()) {
				flushParagraph(paragraph, html);
				var level = Math.min(6, heading.group(1).length() + 2);
				html.append("<h").append(level).append('>').append(inline(heading.group(2)))
						.append("</h").append(level).append('>');
				index++;
				continue;
			}
			if (trimmed.startsWith("|") && index + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[index + 1]).matches()) {
				flushParagraph(paragraph, html);
				html.append("<table cellspacing=\"0\" cellpadding=\"3\" border=\"1\"><tr>");
				for (var cell : cells(trimmed)) {
					html.append("<th>").append(inline(cell)).append("</th>");
				}
				html.append("</tr>");
				index += 2;
				while (index < lines.length && lines[index].strip().startsWith("|")) {
					html.append("<tr>");
					for (var cell : cells(lines[index].strip())) {
						html.append("<td>").append(inline(cell)).append("</td>");
					}
					html.append("</tr>");
					index++;
				}
				html.append("</table>");
				continue;
			}
			if (UNORDERED.matcher(line).matches() || ORDERED.matcher(line).matches()) {
				flushParagraph(paragraph, html);
				var ordered = ORDERED.matcher(line).matches();
				html.append(ordered ? "<ol>" : "<ul>");
				while (index < lines.length) {
					Matcher item = (ordered ? ORDERED : UNORDERED).matcher(lines[index]);
					if (item.matches()) {
						html.append("<li>").append(inline(item.group(ordered ? 2 : 1)));
						index++;
					} else if (!lines[index].isBlank() && Character.isWhitespace(lines[index].charAt(0))) {
						// A continuation line, indented under its item.
						html.append("<br>").append(inline(lines[index].strip()));
						index++;
					} else {
						break;
					}
				}
				html.append(ordered ? "</ol>" : "</ul>");
				continue;
			}
			if (trimmed.startsWith(">")) {
				flushParagraph(paragraph, html);
				var quote = new ArrayList<String>();
				while (index < lines.length && lines[index].strip().startsWith(">")) {
					quote.add(lines[index].strip().substring(1).strip());
					index++;
				}
				html.append("<blockquote>").append(String.join("<br>", quote.stream().map(MiniMarkdown::inline).toList()))
						.append("</blockquote>");
				continue;
			}
			paragraph.add(trimmed);
			index++;
		}
		flushParagraph(paragraph, html);
		return html.toString();
	}

	private static void flushParagraph(List<String> paragraph, StringBuilder html) {
		if (paragraph.isEmpty()) {
			return;
		}
		html.append("<p>").append(String.join("<br>", paragraph.stream().map(MiniMarkdown::inline).toList()))
				.append("</p>");
		paragraph.clear();
	}

	private static List<String> cells(String row) {
		var inner = row;
		if (inner.startsWith("|")) {
			inner = inner.substring(1);
		}
		if (inner.endsWith("|")) {
			inner = inner.substring(0, inner.length() - 1);
		}
		return List.of(inner.split("\\|", -1)).stream().map(String::strip).toList();
	}

	/** Inline code first, so nothing inside backticks is read as emphasis or a link. */
	static String inline(String text) {
		var html = new StringBuilder();
		var parts = text.split("`", -1);
		for (var index = 0; index < parts.length; index++) {
			var code = index % 2 == 1 && index < parts.length - 1;
			if (code) {
				html.append("<code>").append(escape(parts[index])).append("</code>");
			} else {
				var part = index % 2 == 1 ? "`" + parts[index] : parts[index];
				html.append(emphasis(escape(part)));
			}
		}
		return html.toString();
	}

	private static String emphasis(String escaped) {
		var linked = LINK.matcher(escaped).replaceAll(match -> Matcher.quoteReplacement(
				"<a href=\"" + match.group(2) + "\">" + match.group(1) + "</a>"));
		var bold = BOLD.matcher(linked).replaceAll(match -> Matcher.quoteReplacement(
				"<b>" + (match.group(1) != null ? match.group(1) : match.group(2)) + "</b>"));
		return ITALIC.matcher(bold).replaceAll(match -> Matcher.quoteReplacement(
				"<i>" + (match.group(1) != null ? match.group(1) : match.group(2)) + "</i>"));
	}

	static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
