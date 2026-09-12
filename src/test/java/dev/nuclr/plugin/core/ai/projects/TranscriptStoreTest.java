package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.TranscriptStore;

/** The output an agent leaves behind, which is the only part of a session that survives. */
class TranscriptStoreTest {

	@TempDir
	Path root;

	private ProjectPaths paths;
	private TranscriptStore transcripts;

	@BeforeEach
	void setUp() throws IOException {
		paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, "p", root.resolve("home"));
		paths.createLayout(true);
		transcripts = new TranscriptStore(paths);
	}

	@Test
	void appendedOutputComesBackInOrder() {
		transcripts.append("a1", "first ");
		transcripts.append("a1", "second");
		assertEquals("first second", transcripts.tail("a1", 1000));
	}

	@Test
	void transcriptsAreKeptPerAgent() {
		transcripts.append("a1", "one");
		transcripts.append("a2", "two");
		assertEquals("one", transcripts.tail("a1", 100));
		assertEquals("two", transcripts.tail("a2", 100));
	}

	@Test
	void anUnknownAgentHasAnEmptyTranscriptRatherThanAnError() {
		assertEquals("", transcripts.tail("never-ran", 100));
		assertEquals("", transcripts.tail(null, 100));
	}

	@Test
	void theTailIsReturnedWhenMoreIsAskedForThanExists() {
		transcripts.append("a1", "0123456789");
		assertEquals("6789", transcripts.tail("a1", 4));
	}

	@Test
	void aCharacterTailDoesNotSplitAnEmoji() {
		transcripts.append("a1", "prefix😀");
		assertEquals("😀", transcripts.tail("a1", 1));
		assertEquals("", transcripts.tail("a1", 0));
	}

	@Test
	void aNoteIsRecordedAsALineOfItsOwn() {
		transcripts.appendNote("a1", "exited with status 0");
		assertTrue(transcripts.tail("a1", 500).contains("[nuclr] exited with status 0"));
	}

	@Test
	void appendsAreWrittenOffTheCallersThreadButAreVisibleToEveryReader() {

		// The point of queueing: a terminal window drains its buffer from a Swing timer,
		// so the write must not happen on the caller's thread - and must still be there
		// the moment anything asks for it.
		transcripts.append("a1", "first ");
		transcripts.append("a1", "second");

		assertEquals("first second", transcripts.tail("a1", 500));
		assertTrue(Files.isRegularFile(transcripts.fileFor("a1")),
				"handing out the path should flush what is queued");
	}

	@Test
	void appendsKeepTheirOrderThroughTheQueue() {

		for (var index = 0; index < 200; index++) {
			transcripts.append("a1", index + ",");
		}

		var written = transcripts.tail("a1", 100_000);
		var expected = new StringBuilder();
		for (var index = 0; index < 200; index++) {
			expected.append(index).append(',');
		}
		assertEquals(expected.toString(), written);
	}

	@Test
	void aLongRunningAgentCannotFillTheDisk() throws IOException {

		var chunk = "x".repeat(64 * 1024);
		for (var written = 0; written < 48; written++) {
			transcripts.append("a1", chunk);
		}

		// This reads the file directly rather than through the store, so it has to wait
		// for the queued writes itself; every accessor on the store already does.
		transcripts.flush();
		var size = Files.size(paths.transcriptFile("a1"));
		assertTrue(size <= TranscriptStore.MAX_BYTES,
				"transcript grew to " + size + " bytes, past the " + TranscriptStore.MAX_BYTES + " cap");
	}

	@Test
	void trimmingKeepsTheRecentEnd() {

		transcripts.append("a1", "x".repeat((int) TranscriptStore.MAX_BYTES + 1024));
		transcripts.append("a1", "THE-END");

		assertTrue(transcripts.tail("a1", 100).endsWith("THE-END"));
	}

	@Test
	void trimmingDoesNotKeepHalfOfAUtf8Character() {
		var retainedAscii = "y".repeat(1024 * 1024 - 2);

		transcripts.append("unicode", "x".repeat(1024 * 1024) + "€" + retainedAscii);

		var tail = transcripts.tail("unicode", Integer.MAX_VALUE);
		assertEquals(retainedAscii, tail);
		assertFalse(tail.contains("\uFFFD"));
	}

	@Test
	void deletingRemovesTheFile() {
		transcripts.append("a1", "content");
		transcripts.delete("a1");
		assertFalse(Files.exists(paths.transcriptFile("a1")));
		assertEquals("", transcripts.tail("a1", 100));
	}

	@Test
	void anAgentIdThatIsNotAFileNameIsStillUsable() {
		transcripts.append("weird/id:with*chars", "content");
		assertEquals("content", transcripts.tail("weird/id:with*chars", 100));
	}
}
