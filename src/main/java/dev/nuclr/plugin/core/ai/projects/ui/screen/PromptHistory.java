package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The instructions sent during this session, most recent first.
 *
 * <p>Prompts get retyped constantly - the same nudge sent to a second agent, the
 * same question asked again after a restart - and retyping a paragraph is the
 * kind of friction that makes a feature feel unfinished.
 *
 * <p>Session-scoped on purpose. Prompts routinely carry snippets of whatever the
 * user was working on, and writing them into the project directory would put
 * that in a place they did not choose and might well commit.
 */
public final class PromptHistory {

	/** How many prompts are remembered. */
	private static final int LIMIT = 25;

	private final Deque<String> entries = new ArrayDeque<>();

	/**
	 * Remember a prompt, moving a repeat back to the front rather than duplicating it.
	 *
	 * @param prompt the text that was sent; blank text is ignored
	 */
	public void remember(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return;
		}
		var trimmed = prompt.strip();
		entries.remove(trimmed);
		entries.addFirst(trimmed);
		while (entries.size() > LIMIT) {
			entries.removeLast();
		}
	}

	/** The remembered prompts, most recent first. */
	public List<String> entries() {
		return List.copyOf(entries);
	}

	/** Whether anything has been sent yet. */
	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/**
	 * A one-line label for a prompt, for the recall menu.
	 *
	 * @param prompt the remembered text
	 * @return its first line, shortened
	 */
	public static String label(String prompt) {
		var firstLine = prompt.lines().findFirst().orElse(prompt).strip();
		var multiLine = prompt.lines().count() > 1;
		if (firstLine.length() > 60) {
			return firstLine.substring(0, 57) + "...";
		}
		return multiLine ? firstLine + " ..." : firstLine;
	}
}
