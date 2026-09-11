package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guesses, from what a terminal last printed, whether the agent is sitting at a
 * prompt - and separately, whether it is asking for a decision.
 *
 * <p>No CLI agent reports "I am waiting for you", so with five or ten agents on
 * a desktop the alternative to guessing is a wall of windows that all look
 * identical. The guess is made explicit rather than hidden: the status it
 * produces is labelled as a heuristic in the UI, and it never claims a process
 * is anything other than alive.
 *
 * <p>Two questions, deliberately separate:
 * <ul>
 *   <li>{@link #looksLikePrompt(String)} - the last line looks like an input
 *       prompt. Common and unremarkable; a shell sits at one all day. Drives the
 *       {@code WAITING_INPUT} status only.</li>
 *   <li>{@link #looksLikeConfirmation(String)} - the agent is asking permission.
 *       That is worth interrupting the user for, so it drives the attention
 *       flag on the frame and on the project row.</li>
 * </ul>
 */
public final class PromptWaitDetector {

	/** How long output must have been quiet before a prompt is believed. */
	public static final long QUIET_MILLIS = 1_500;

	/** Trailing characters that end an input prompt across shells and agent CLIs. */
	private static final Pattern PROMPT_TAIL =
			Pattern.compile(".*[>$#:?\\u276f\\u203a\\u00bb\\u2771]\\s*$");

	/** Phrases that mean the agent wants a decision rather than merely input. */
	private static final List<String> CONFIRMATION_PHRASES = List.of(
			"(y/n)", "[y/n]", "y/n?", "(yes/no)", "[yes/no]",
			"do you want", "would you like", "proceed?", "continue?",
			"allow", "approve", "confirm", "permission to",
			"press enter to", "are you sure", "overwrite?");

	private PromptWaitDetector() {
	}

	/**
	 * Whether the tail of the output looks like an input prompt.
	 *
	 * @param output recent terminal output; may be {@code null}
	 * @return whether the last non-blank line ends like a prompt
	 */
	public static boolean looksLikePrompt(String output) {
		var line = lastMeaningfulLine(output);
		return line != null && PROMPT_TAIL.matcher(line).matches();
	}

	/**
	 * Whether the tail of the output is asking the user to decide something.
	 *
	 * @param output recent terminal output; may be {@code null}
	 * @return whether a confirmation phrase appears near the end
	 */
	public static boolean looksLikeConfirmation(String output) {
		if (output == null || output.isBlank()) {
			return false;
		}
		// Only the tail matters: a confirmation the user answered ten minutes ago is
		// still somewhere in the transcript and must not keep raising the flag.
		var tail = output.length() <= 400 ? output : output.substring(output.length() - 400);
		var normalised = strip(tail).toLowerCase(Locale.ROOT);
		return CONFIRMATION_PHRASES.stream().anyMatch(normalised::contains);
	}

	/**
	 * The last line with something on it, with escape sequences removed.
	 *
	 * @param output terminal output; may be {@code null}
	 * @return the line, or {@code null} when there is none
	 */
	public static String lastMeaningfulLine(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		var lines = strip(output).split("\\R");
		for (var index = lines.length - 1; index >= 0; index--) {
			var line = lines[index].strip();
			if (!line.isEmpty()) {
				return line;
			}
		}
		return null;
	}

	/**
	 * Remove ANSI escape sequences and carriage returns, so the tests below see
	 * the text a user would.
	 *
	 * @param text raw terminal output
	 * @return the same text without control sequences
	 */
	public static String strip(String text) {
		if (text == null) {
			return "";
		}
		return text
				.replaceAll("\\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)", "")
				.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
				.replaceAll("\\u001B[@-Z\\\\-_]", "")
				.replace("\r", "");
	}
}
