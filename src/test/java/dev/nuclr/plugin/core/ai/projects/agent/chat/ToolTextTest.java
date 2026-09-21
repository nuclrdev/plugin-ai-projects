package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** A tool block's input and output, cut into diff lines and coloured code. */
class ToolTextTest {

	private static final CodeHighlighter HIGHLIGHTER = new CodeHighlighter(new Color(0x2B, 0x2B, 0x2B));

	private static String text(List<ToolText.Run> runs) {
		return runs.stream().map(ToolText.Run::getText).collect(Collectors.joining());
	}

	private static List<ToolText.Run> on(List<ToolText.Run> runs, ToolText.Line line) {
		return runs.stream().filter(run -> run.getLine() == line).toList();
	}

	@Test
	void anEditIsADiffWithItsCodeColouredByTheFileExtension() {

		var detail = "- \tpublic Builder searchArchives(boolean value) {\n- \t\treturn this;\n+ ";
		var runs = ToolText.runs("Edit", "C:\\src\\FindFileRequest.java", detail, "The file has been updated.",
				HIGHLIGHTER);

		assertEquals(detail + "\n\nThe file has been updated.", text(runs));
		var removed = on(runs, ToolText.Line.REMOVED);
		assertTrue(removed.stream().anyMatch(ToolText.Run::isMarker), "the - is not marked");
		assertTrue(removed.stream().anyMatch(run -> run.getText().equals("public") && run.getColour() != null),
				"the removed Java was not coloured: " + removed);
		assertEquals(1, on(runs, ToolText.Line.ADDED).size(), "only the + marker, the added line is empty");
		// The tool's own report is not code, and not part of the diff.
		assertNull(runs.getLast().getColour());
		assertEquals(ToolText.Line.PLAIN, runs.getLast().getLine());
	}

	@Test
	void aUnifiedDiffKeepsItsHeadersAndPathOutOfTheCode() {

		var detail = "src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n int x;\n-int y;\n+int z;";
		var runs = ToolText.runs("Edit", "src/A.java", detail, "", HIGHLIGHTER);

		assertEquals(detail, text(runs));
		assertEquals(3, on(runs, ToolText.Line.HEADER).size());
		assertTrue(runs.stream().anyMatch(run -> run.getText().equals("int") && run.getLine() == ToolText.Line.PLAIN
				&& run.getColour() != null), "the context line was not coloured");
		assertTrue(on(runs, ToolText.Line.ADDED).stream().anyMatch(run -> "int".equals(run.getText())));
	}

	@Test
	void aWrittenFileIsColouredAndAnUnknownOneIsLeftAsText() {

		var java = ToolText.runs("Write", "A.java", "class A {}", "", HIGHLIGHTER);
		assertTrue(java.stream().anyMatch(run -> run.getColour() != null), "nothing was coloured");

		var unknown = ToolText.runs("Write", "notes.nonesuch", "class A {}", "", HIGHLIGHTER);
		assertEquals(List.of(new ToolText.Run("class A {}", null, ToolText.Line.PLAIN, false)), unknown);
	}

	@Test
	void aCommandIsLeftAsText() {

		var runs = ToolText.runs("Bash", "git status", "git status\ngit diff", "- not a diff", HIGHLIGHTER);

		assertEquals("git status\ngit diff\n\n- not a diff", text(runs));
		assertTrue(runs.stream().allMatch(run -> run.getLine() == ToolText.Line.PLAIN && run.getColour() == null));
	}

	@Test
	void theLanguageIsTheExtensionOfTheFileName() {
		assertEquals("java", CodeHighlighter.languageOfPath("C:\\a.b\\Find.java"));
		assertNull(CodeHighlighter.languageOfPath("/home/me/.bashrc"));
		assertNull(CodeHighlighter.languageOfPath("Makefile"));
	}
}
