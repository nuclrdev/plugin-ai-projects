package dev.nuclr.plugin.core.ai.projects.agent;

import javax.swing.JComponent;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * The live contents of one internal frame on the project desktop.
 *
 * <p>A window is not necessarily a process. A terminal agent runs one; a log
 * viewer or a task board would not, and would report {@link AgentStatus#STOPPED}
 * for its whole life while still being a perfectly good window. That is why
 * {@link #start()} and {@link #stop()} are allowed to do nothing.
 *
 * <p>Every method is called on the event dispatch thread except where noted, and
 * {@link #close()} must be safe to call more than once.
 */
public interface AgentWindow extends AutoCloseable {

	/** The component shown inside the internal frame; the same instance every time. */
	JComponent component();

	/** The agent's current status. */
	AgentStatus status();

	/**
	 * Start the agent, if this kind of window has anything to start. Called when
	 * the user asks, and on project open for agents that were running before.
	 */
	void start();

	/** Stop the agent, leaving the window in place showing what it produced. */
	void stop();

	/** Stop and start again. */
	default void restart() {
		stop();
		start();
	}

	/**
	 * A short line describing the current session, shown under a stopped window
	 * so a restored project says what happened rather than looking merely empty.
	 *
	 * @return the description, never {@code null}
	 */
	default String sessionSummary() {
		return status().label();
	}

	/** Whether {@link #sendInstruction(String)} will do anything. */
	default boolean canSendInstruction() {
		return false;
	}

	/**
	 * Send a line of text to the agent, as if typed.
	 *
	 * @param instruction the text; a newline is appended by the implementation
	 */
	default void sendInstruction(String instruction) {
		// Not every kind of window has somewhere to send text.
	}

	/** Give keyboard focus to the window's real content, not its frame. */
	default void focusContent() {
		component().requestFocusInWindow();
	}

	/** Re-read colours and fonts from the current look and feel. */
	default void updateTheme() {
		// Windows that draw with UIManager colours need do nothing here.
	}

	/** Whether this window's text can be enlarged and shrunk. */
	default boolean canZoom() {
		return false;
	}

	/**
	 * Change the text size.
	 *
	 * @param steps positive to enlarge, negative to shrink
	 */
	default void zoom(int steps) {
		// Windows with no text of their own have nothing to scale.
	}

	/** Return the text to its default size. */
	default void resetZoom() {
		// Windows with no text of their own have nothing to scale.
	}

	/** Whether this window has a screen that can be cleared. */
	default boolean canClear() {
		return false;
	}

	/** Clear what is displayed, without destroying any stored history. */
	default void clearScreen() {
		// Nothing to clear.
	}

	/**
	 * Everything this window has produced, for the clipboard.
	 *
	 * @return the text, or an empty string when the window keeps none
	 */
	default String outputForCopy() {
		return "";
	}

	/**
	 * The file this window's output is kept in, so the user can open it elsewhere.
	 *
	 * @return the path, or {@code null} when the window keeps no file
	 */
	default java.nio.file.Path transcriptFile() {
		return null;
	}

	/** Discard the stored history. Destructive, and asked about before it is called. */
	default void clearTranscript() {
		// Nothing stored.
	}

	/**
	 * Release everything the window holds: processes, readers, streams, timers.
	 * Called when the frame is closed or the project is closed, and possibly
	 * twice.
	 */
	@Override
	void close();
}
