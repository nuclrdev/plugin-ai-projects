package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PromptRecallTest {

	@Test
	void walksBackAndForthFromAnEmptyField() {
		var recall = new PromptRecall(() -> List.of("first", "second", "third"));

		assertEquals("third", recall.older(""));
		assertEquals("second", recall.older("third"));
		assertEquals("first", recall.older("second"));
		assertEquals("first", recall.older("first"), "stays at the oldest");
		assertEquals("second", recall.newer("first"));
		assertEquals("third", recall.newer("second"));
		assertEquals("", recall.newer("third"), "past the newest the field is empty again");
		assertNull(recall.newer(""));
	}

	@Test
	void leavesTheCaretAloneInAMessageBeingWritten() {
		var recall = new PromptRecall(() -> List.of("first"));

		assertNull(recall.older("half a thought"));
		assertNull(recall.newer("half a thought"));
	}

	@Test
	void anEditedRecallIsTheUsersOwnText() {
		var recall = new PromptRecall(() -> List.of("first", "second"));

		assertEquals("second", recall.older(""));
		assertNull(recall.older("second, edited"));
	}

	@Test
	void repeatsAreRecalledOnce() {
		var recall = new PromptRecall(() -> List.of("again", "other", "again"));

		assertEquals("again", recall.older(""));
		assertEquals("other", recall.older("again"));
		assertEquals("other", recall.older("other"));
	}

	@Test
	void nothingSentMeansTheKeyMovesTheCaret() {
		var recall = new PromptRecall(ArrayList::new);

		assertNull(recall.older(""));
	}
}
