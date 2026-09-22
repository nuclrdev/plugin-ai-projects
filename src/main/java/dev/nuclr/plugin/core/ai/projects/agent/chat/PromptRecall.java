package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Up and Down in an empty composer walk back through what was already sent, the way
 * a shell does.
 *
 * <p>Only an empty field starts the walk, so the arrows keep moving the caret in a
 * message being written. Once a recalled prompt is edited it is the user's text, and
 * the arrows go back to moving the caret in it.
 */
final class PromptRecall {

	/** The prompts sent in this conversation, oldest first. */
	private final Supplier<List<String>> sent;

	/** The prompts being walked, most recent first; taken when the walk starts. */
	private List<String> prompts = List.of();

	/** Which of {@link #prompts} is in the field; -1 when not walking. */
	private int index = -1;

	PromptRecall(Supplier<List<String>> sent) {
		this.sent = sent;
	}

	/**
	 * The text for Up.
	 *
	 * @param current what the field holds now
	 * @return the older prompt to show, or null when the key should move the caret
	 */
	String older(String current) {
		if (!walking(current)) {
			if (!current.isBlank()) {
				return null;
			}
			prompts = newestFirst(sent.get());
			index = -1;
		}
		if (index + 1 >= prompts.size()) {
			// Already at the oldest, or nothing sent yet.
			return index < 0 ? null : current;
		}
		index++;
		return prompts.get(index);
	}

	/**
	 * The text for Down.
	 *
	 * @param current what the field holds now
	 * @return the newer prompt to show, "" past the newest, or null when the key should
	 *         move the caret
	 */
	String newer(String current) {
		if (!walking(current)) {
			return null;
		}
		index--;
		if (index < 0) {
			return "";
		}
		return prompts.get(index);
	}

	/** Whether the field still shows, untouched, the prompt the walk put there. */
	private boolean walking(String current) {
		return index >= 0 && index < prompts.size() && prompts.get(index).equals(current);
	}

	private static List<String> newestFirst(List<String> oldestFirst) {
		var distinct = new LinkedHashSet<String>();
		for (var i = oldestFirst.size() - 1; i >= 0; i--) {
			var prompt = oldestFirst.get(i);
			if (prompt != null && !prompt.isBlank()) {
				distinct.add(prompt.strip());
			}
		}
		return new ArrayList<>(distinct);
	}
}
