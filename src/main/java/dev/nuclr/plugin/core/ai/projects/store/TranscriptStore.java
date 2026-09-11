package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal transcripts, kept per agent and deliberately kept across restarts.
 *
 * <p>None of the CLI agents can be reattached after Commander exits, so a
 * restored window shows a dead process. What it can still show is everything
 * that process said, which is most of the continuity a user actually wants.
 *
 * <p>Each transcript is capped: when it grows past {@link #MAX_BYTES} the head
 * is dropped and the tail kept, because the recent end of a session is the part
 * worth reading. Trimming happens on write, so a long-running agent cannot fill
 * a disk overnight.
 */
public final class TranscriptStore {

	/** Largest transcript kept per agent, in bytes. */
	public static final long MAX_BYTES = 2L * 1024 * 1024;

	/** How much is trimmed back to once the cap is passed. */
	private static final long TRIM_TO_BYTES = 1024 * 1024;

	private final ProjectPaths paths;
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * Create a store over a project's transcript directory.
	 *
	 * @param paths the project's paths
	 */
	public TranscriptStore(ProjectPaths paths) {
		this.paths = paths;
	}

	/**
	 * Append output produced by an agent.
	 *
	 * @param agentId the agent
	 * @param text    the text to append; ignored when blank
	 */
	public void append(String agentId, String text) {
		if (agentId == null || text == null || text.isEmpty()) {
			return;
		}
		synchronized (lock(agentId)) {
			var file = paths.transcriptFile(agentId);
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				trim(file);
			} catch (IOException e) {
				// A transcript is a convenience. Losing a write must never take the agent
				// down with it, and reporting it once per line would be worse than useless.
				return;
			}
		}
	}

	/**
	 * Record a line of the plugin's own, such as a start or exit notice, so the
	 * transcript reads as a continuous history rather than a series of fragments.
	 *
	 * @param agentId the agent
	 * @param note    the note; a newline is added
	 */
	public void appendNote(String agentId, String note) {
		append(agentId, System.lineSeparator() + "[nuclr] " + note + System.lineSeparator());
	}

	/**
	 * Read back the tail of an agent's transcript.
	 *
	 * @param agentId   the agent
	 * @param maxChars  how much of the end to return
	 * @return the transcript tail, or an empty string when there is none
	 */
	public String tail(String agentId, int maxChars) {
		if (agentId == null) {
			return "";
		}
		synchronized (lock(agentId)) {
			var file = paths.transcriptFile(agentId);
			if (!Files.isRegularFile(file)) {
				return "";
			}
			try {
				var content = Files.readString(file, StandardCharsets.UTF_8);
				return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
			} catch (IOException | RuntimeException e) {
				return "";
			}
		}
	}

	/**
	 * The file an agent's transcript is kept in, so it can be opened elsewhere.
	 *
	 * @param agentId the agent
	 * @return the path, whether or not anything has been written to it yet
	 */
	public Path fileFor(String agentId) {
		return paths.transcriptFile(agentId);
	}

	/**
	 * Discard an agent's transcript, when the agent itself is deleted.
	 *
	 * @param agentId the agent
	 */
	public void delete(String agentId) {
		if (agentId == null) {
			return;
		}
		synchronized (lock(agentId)) {
			try {
				Files.deleteIfExists(paths.transcriptFile(agentId));
			} catch (IOException e) {
				return;
			}
		}
		locks.remove(agentId);
	}

	private void trim(Path file) throws IOException {
		if (Files.size(file) <= MAX_BYTES) {
			return;
		}
		var bytes = Files.readAllBytes(file);
		var keep = (int) Math.min(TRIM_TO_BYTES, bytes.length);
		var tail = new byte[keep];
		System.arraycopy(bytes, bytes.length - keep, tail, 0, keep);
		Files.write(file, tail);
	}

	private Object lock(String agentId) {
		return locks.computeIfAbsent(agentId, key -> new Object());
	}
}
