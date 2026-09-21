package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.Value;

/**
 * What a tool block shows when opened - the call's input and what it returned - cut into
 * coloured runs.
 *
 * <p>An edit's input is a diff: every protocol here writes it as lines marked {@code - }
 * and {@code + }, and Codex as a unified diff. Those lines are marked as removed or added,
 * and the code after the marker is coloured in the language the file's extension names,
 * the old and the new side each lexed as one run of lines so a comment opened on one line
 * stays a comment on the next. A written file's content is coloured the same way. Anything
 * else - a command's output, a path above a Codex diff - is left as text.
 *
 * <p>The runs spell the text exactly, so what is copied out of the block is what the agent
 * sent.
 */
final class ToolText {

	private static final Set<String> EDITS = Set.of("edit", "multiedit");
	private static final Set<String> WRITES = Set.of("write");

	/** What a line is, which decides the shade behind it. */
	enum Line {
		PLAIN, ADDED, REMOVED, HEADER
	}

	/** One run of text in one colour, on one kind of line. */
	@Value
	static class Run {
		String text;
		/** The token's colour, or {@code null} for the colour of the text around it. */
		String colour;
		Line line;
		/** Whether this is the {@code +} or {@code -} that marks a line. */
		boolean marker;
	}

	private ToolText() {
	}

	/**
	 * The runs for a tool call's input and output.
	 *
	 * @param name        the tool
	 * @param title       its one-line title - for a file tool, the path
	 * @param detail      its input, readable
	 * @param output      what it returned so far
	 * @param highlighter the colours for code
	 * @return the runs, in order
	 */
	static List<Run> runs(String name, String title, String detail, String output, CodeHighlighter highlighter) {
		var tool = name == null ? "" : name.toLowerCase(Locale.ROOT);
		var language = CodeHighlighter.languageOfPath(firstPath(title));
		var runs = new ArrayList<Run>();
		detail = detail == null ? "" : detail;
		output = output == null ? "" : output;
		if (EDITS.contains(tool)) {
			diff(runs, detail, language, highlighter);
		} else if (WRITES.contains(tool)) {
			code(runs, detail, language, highlighter);
		} else {
			plain(runs, detail);
		}
		if (!detail.isEmpty() && !output.isEmpty()) {
			plain(runs, "\n\n");
		}
		// An ACP agent reports its edits as a result rather than as input; the markers say which lines are which.
		if (EDITS.contains(tool)) {
			diff(runs, output, language, highlighter);
		} else {
			plain(runs, output);
		}
		return runs;
	}

	/** The first of the paths a title names; Codex joins them with commas. */
	private static String firstPath(String title) {
		if (title == null) {
			return null;
		}
		var comma = title.indexOf(", ");
		return comma < 0 ? title : title.substring(0, comma);
	}

	private static void plain(List<Run> runs, String text) {
		if (!text.isEmpty()) {
			runs.add(new Run(text, null, Line.PLAIN, false));
		}
	}

	private static void code(List<Run> runs, String text, String language, CodeHighlighter highlighter) {
		if (text.isEmpty()) {
			return;
		}
		var lines = List.of(text.split("\n", -1));
		var tokens = highlighter.tokens(lines, language);
		if (tokens == null) {
			plain(runs, text);
			return;
		}
		for (var index = 0; index < lines.size(); index++) {
			if (index > 0) {
				plain(runs, "\n");
			}
			for (var token : tokens.get(index)) {
				runs.add(new Run(token.getText(), token.getColour(), Line.PLAIN, false));
			}
		}
	}

	private static void diff(List<Run> runs, String text, String language, CodeHighlighter highlighter) {

		if (text.isEmpty()) {
			return;
		}
		var lines = text.split("\n", -1);
		var kinds = new Line[lines.length];
		var markers = new String[lines.length];
		var inHunk = false;
		for (var index = 0; index < lines.length; index++) {
			var line = lines[index];
			if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
				inHunk |= line.startsWith("@@");
				kinds[index] = Line.HEADER;
			} else if (line.startsWith("+") || line.startsWith("-")) {
				kinds[index] = line.startsWith("+") ? Line.ADDED : Line.REMOVED;
				markers[index] = line.length() > 1 && line.charAt(1) == ' ' ? line.substring(0, 2) : line.substring(0, 1);
			} else if (inHunk && line.startsWith(" ")) {
				// A unified diff's context: code, on neither side.
				kinds[index] = Line.PLAIN;
				markers[index] = " ";
			} else {
				kinds[index] = Line.PLAIN;
			}
		}

		// Each side is lexed as the file it came from: the old lines together, the new and the unchanged together.
		var colours = new ArrayList<List<CodeHighlighter.Token>>(Collections.nCopies(lines.length, null));
		lex(lines, kinds, markers, colours, Line.REMOVED, language, highlighter);
		lex(lines, kinds, markers, colours, Line.ADDED, language, highlighter);

		for (var index = 0; index < lines.length; index++) {
			if (index > 0) {
				plain(runs, "\n");
			}
			var kind = kinds[index];
			var marker = markers[index];
			if (marker == null) {
				if (!lines[index].isEmpty()) {
					runs.add(new Run(lines[index], null, kind, false));
				}
				continue;
			}
			runs.add(new Run(marker, null, kind, kind != Line.PLAIN));
			var rest = lines[index].substring(marker.length());
			var tokens = colours.get(index);
			if (tokens == null) {
				if (!rest.isEmpty()) {
					runs.add(new Run(rest, null, kind, false));
				}
				continue;
			}
			for (var token : tokens) {
				runs.add(new Run(token.getText(), token.getColour(), kind, false));
			}
		}
	}

	/** Colour one side's code - its own lines, plus the context lines when it is the new side. */
	private static void lex(String[] lines, Line[] kinds, String[] markers, List<List<CodeHighlighter.Token>> colours,
			Line side, String language, CodeHighlighter highlighter) {
		var indices = new ArrayList<Integer>();
		var code = new ArrayList<String>();
		for (var index = 0; index < lines.length; index++) {
			var context = kinds[index] == Line.PLAIN && markers[index] != null;
			if (markers[index] != null && (kinds[index] == side || side == Line.ADDED && context)) {
				indices.add(index);
				code.add(lines[index].substring(markers[index].length()));
			}
		}
		if (code.isEmpty()) {
			return;
		}
		var tokens = highlighter.tokens(code, language);
		if (tokens == null) {
			return;
		}
		for (var at = 0; at < indices.size(); at++) {
			colours.set(indices.get(at), tokens.get(at));
		}
	}
}
