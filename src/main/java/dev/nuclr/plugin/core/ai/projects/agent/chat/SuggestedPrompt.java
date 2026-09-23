package dev.nuclr.plugin.core.ai.projects.agent.chat;

/**
 * The message the agent expects the user to send next, taken out of the end of its reply.
 *
 * <p>No CLI offers this in a form every one of them shares, so the agent is asked for it:
 * its briefing ({@link #BRIEFING}) tells it to end each reply with a line wrapped in
 * {@link #OPEN} and {@link #CLOSE}. The reply streams in pieces and the tag can be cut
 * anywhere, so this reads the pieces as they come - passing on what is to be shown and
 * holding back what may yet turn out to be the tag - and never lets the tag reach the page.
 * An agent that ignores the instruction simply offers nothing.
 *
 * <p>One per window, on the event thread.
 */
final class SuggestedPrompt {

	static final String OPEN = "<nuclr-next>";
	static final String CLOSE = "</nuclr-next>";

	/** Longest suggestion taken; an unclosed tag longer than this was never one. */
	static final int MAX_LENGTH = 200;

	/** What the agent is told, as a section of its briefing. */
	static final String BRIEFING = """
			## Suggested next message

			You are talking to the user through Nuclr Commander's chat window. End every reply with one last \
			line of the form %s...%s holding the message the user is most likely to send you next, written as \
			they would type it: one short line, under 80 characters, no quotes. Leave the line out when there \
			is no obvious next step. The window removes the line and offers it to the user to accept with Tab, \
			so never mention it.
			""".formatted(OPEN, CLOSE);

	/** Text not shown yet: the start of what may be the tag, or the inside of one that has opened. */
	private final StringBuilder held = new StringBuilder();
	private boolean open;
	private String suggestion;

	/**
	 * Read the next piece of the agent's reply.
	 *
	 * @param chunk the piece
	 * @return what of it, and of what was held back before it, can be shown now; possibly empty
	 */
	String feed(String chunk) {
		held.append(chunk);
		var visible = new StringBuilder();
		while (true) {
			if (!open) {
				var at = held.indexOf(OPEN);
				if (at < 0) {
					var keep = partialOpen();
					visible.append(held, 0, held.length() - keep);
					held.delete(0, held.length() - keep);
					return visible.toString();
				}
				visible.append(held, 0, at);
				held.delete(0, at + OPEN.length());
				open = true;
			}
			var end = held.indexOf(CLOSE);
			if (end < 0) {
				if (held.length() > MAX_LENGTH + CLOSE.length()) {
					// Too long to be a suggestion: it was the agent's words after all.
					visible.append(OPEN).append(held);
					held.setLength(0);
					open = false;
				}
				return visible.toString();
			}
			suggestion = clean(held.substring(0, end));
			held.delete(0, end + CLOSE.length());
			open = false;
		}
	}

	/**
	 * The reply's text has ended - something other than its words came next - so what was
	 * held back is shown after all: a tag left open, or a start of one that never went on.
	 *
	 * @return the held-back text, possibly empty
	 */
	String flush() {
		var rest = (open ? OPEN : "") + held;
		held.setLength(0);
		open = false;
		return rest;
	}

	/**
	 * The last suggestion read, forgotten as it is taken.
	 *
	 * @return the suggestion, or {@code null} when the agent gave none since the last take
	 */
	String take() {
		var taken = suggestion;
		suggestion = null;
		return taken;
	}

	/** Forget everything, for a new process. */
	void reset() {
		held.setLength(0);
		open = false;
		suggestion = null;
	}

	/** How much of the end of what is held is a beginning of the tag, which the next piece may complete. */
	private int partialOpen() {
		for (var length = Math.min(held.length(), OPEN.length() - 1); length > 0; length--) {
			if (held.substring(held.length() - length).equals(OPEN.substring(0, length))) {
				return length;
			}
		}
		return 0;
	}

	/** One line, without the quotes an agent may put round it; {@code null} for nothing usable. */
	static String clean(String raw) {
		var line = raw.replaceAll("\\s+", " ").strip();
		if (line.length() >= 2 && (line.startsWith("\"") && line.endsWith("\"")
				|| line.startsWith("'") && line.endsWith("'") || line.startsWith("`") && line.endsWith("`"))) {
			line = line.substring(1, line.length() - 1).strip();
		}
		return line.isEmpty() || line.length() > MAX_LENGTH ? null : line;
	}
}
