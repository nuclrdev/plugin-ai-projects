package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.PromptWaitDetector;

/**
 * The heuristic that decides whether an agent is waiting, and whether that is
 * worth interrupting the user for.
 */
class PromptWaitDetectorTest {

	@Test
	void aShellPromptLooksLikeAPrompt() {
		assertTrue(PromptWaitDetector.looksLikePrompt("C:\\work\\project>"));
		assertTrue(PromptWaitDetector.looksLikePrompt("user@host:~/project$ "));
		assertTrue(PromptWaitDetector.looksLikePrompt("root@host:/# "));
	}

	@Test
	void agentReplPromptsLookLikePrompts() {
		assertTrue(PromptWaitDetector.looksLikePrompt("\u276f "));
		assertTrue(PromptWaitDetector.looksLikePrompt("Enter your prompt:"));
	}

	@Test
	void outputInFlightDoesNotLookLikeAPrompt() {
		assertFalse(PromptWaitDetector.looksLikePrompt("Editing src/main/java/Thing.java"));
		assertFalse(PromptWaitDetector.looksLikePrompt("Running tests, 12 of 40 complete"));
	}

	@Test
	void blankOutputIsNotAPrompt() {
		assertFalse(PromptWaitDetector.looksLikePrompt(null));
		assertFalse(PromptWaitDetector.looksLikePrompt("   \n\n  "));
	}

	@Test
	void anIdlePromptIsNotAConfirmation() {
		assertFalse(PromptWaitDetector.looksLikeConfirmation("user@host:~/project$ "));
	}

	@Test
	void askingPermissionIsAConfirmation() {
		assertTrue(PromptWaitDetector.looksLikeConfirmation("Do you want to apply this change? (y/n)"));
		assertTrue(PromptWaitDetector.looksLikeConfirmation("Allow the agent to write to /etc? [y/N]"));
		assertTrue(PromptWaitDetector.looksLikeConfirmation("Are you sure you want to continue?"));
	}

	@Test
	void aConfirmationAnsweredLongAgoNoLongerCounts() {

		var oldQuestion = "Do you want to continue? (y/n)\ny\n";
		var since = "working ".repeat(120);

		assertFalse(PromptWaitDetector.looksLikeConfirmation(oldQuestion + since));
	}

	@Test
	void escapeSequencesDoNotHideThePrompt() {

		var coloured = "\u001B[32mAll good\u001B[0m\n\u001B[1;34muser@host\u001B[0m:~$ ";

		assertTrue(PromptWaitDetector.looksLikePrompt(coloured));
		assertEquals("user@host:~$", PromptWaitDetector.lastMeaningfulLine(coloured));
	}

	@Test
	void windowTitleSequencesAreStripped() {
		var titled = "\u001B]0;C:\\work\u0007C:\\work>";
		assertEquals("C:\\work>", PromptWaitDetector.strip(titled));
	}

	@Test
	void carriageReturnsFromProgressBarsAreRemoved() {
		assertEquals("100%", PromptWaitDetector.strip("10%\r50%\r100%").substring(6));
	}

	@Test
	void theLastMeaningfulLineSkipsTrailingBlankLines() {
		assertEquals("done", PromptWaitDetector.lastMeaningfulLine("working\ndone\n\n   \n"));
		assertNull(PromptWaitDetector.lastMeaningfulLine("\n\n"));
	}
}
