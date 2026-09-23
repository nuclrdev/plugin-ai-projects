package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SuggestedPromptTest {

	/** Feed a reply in pieces and return what was shown, flushed as at the end of a turn. */
	private static String shown(SuggestedPrompt reader, String... chunks) {
		var shown = new StringBuilder();
		for (var chunk : chunks) {
			shown.append(reader.feed(chunk));
		}
		return shown.append(reader.flush()).toString();
	}

	@Test
	void takesTheTagOutOfTheReplyWhereverTheStreamCutsIt() {
		var reply = "Done.\n\n<nuclr-next>Run the tests</nuclr-next>";
		for (var cut = 1; cut < reply.length(); cut++) {
			var reader = new SuggestedPrompt();
			assertEquals("Done.\n\n", shown(reader, reply.substring(0, cut), reply.substring(cut)), "cut at " + cut);
			assertEquals("Run the tests", reader.take(), "cut at " + cut);
		}
	}

	@Test
	void holdsBackOnlyWhatCouldStillBeTheTag() {
		var reader = new SuggestedPrompt();
		assertEquals("a ", reader.feed("a <nuc"));
		assertEquals("<nucleus", reader.feed("leus"), "not the tag after all, so shown at once");
		assertNull(reader.take());
	}

	@Test
	void aStartOfTheTagThatNeverGoesOnIsShownWhenTheReplyEnds() {
		var reader = new SuggestedPrompt();
		assertEquals("x <nuclr-next>half", shown(reader, "x <nuclr-", "next>half"));
		assertNull(reader.take());
	}

	@Test
	void anUnclosedTagTooLongToBeASuggestionIsTheAgentsWords() {
		var reader = new SuggestedPrompt();
		var words = "w".repeat(SuggestedPrompt.MAX_LENGTH + SuggestedPrompt.CLOSE.length() + 1);
		assertEquals(SuggestedPrompt.OPEN + words, reader.feed(SuggestedPrompt.OPEN + words));
		assertNull(reader.take());
	}

	@Test
	void theSuggestionIsOneLineWithoutQuotes() {
		var reader = new SuggestedPrompt();
		shown(reader, "<nuclr-next> \"Now commit\n  it\" </nuclr-next>\n");
		assertEquals("Now commit it", reader.take());
		assertNull(reader.take(), "forgotten once taken");
	}

	@Test
	void anEmptyTagSuggestsNothing() {
		var reader = new SuggestedPrompt();
		assertEquals("ok", shown(reader, "ok<nuclr-next>  </nuclr-next>"));
		assertNull(reader.take());
	}

	@Test
	void theBriefingNamesTheTag() {
		assertTrue(SuggestedPrompt.BRIEFING.startsWith("## "));
		assertTrue(SuggestedPrompt.BRIEFING.contains(SuggestedPrompt.OPEN + "..." + SuggestedPrompt.CLOSE));
	}
}
