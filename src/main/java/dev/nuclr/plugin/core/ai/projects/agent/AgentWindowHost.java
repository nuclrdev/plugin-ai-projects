package dev.nuclr.plugin.core.ai.projects.agent;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * What an {@link AgentWindow} may tell the desktop about itself.
 *
 * <p>Kept to a handful of callbacks on purpose. A window provider should be able
 * to be written without knowing anything about internal frames, sidebars or
 * session files - it reports what happened and the desktop decides what that
 * looks like.
 *
 * <p>Implementations are called from whatever thread the window is using,
 * including reader threads, and must not assume the event dispatch thread.
 */
public interface AgentWindowHost {

	/**
	 * The agent's status changed.
	 *
	 * @param agentId the agent
	 * @param status  its new status
	 */
	void statusChanged(String agentId, AgentStatus status);

	/**
	 * The agent needs the user: it is waiting at a prompt, or it failed.
	 *
	 * <p>The desktop flags the internal frame and the project's row in the file
	 * panel. Call it when the condition begins, not repeatedly while it lasts.
	 *
	 * @param agentId the agent
	 * @param reason  one short line for the tooltip
	 */
	void attentionRequested(String agentId, String reason);

	/**
	 * The agent finished what it was asked to do and nothing is blocked.
	 *
	 * <p>The opposite end of {@link #attentionRequested}: that one says the agent
	 * cannot go on without the user, this one says it has stopped because it is
	 * done. Both are worth telling someone who is in another window entirely, and
	 * neither is worth telling twice, so call this once per finished turn.
	 *
	 * <p>A window whose work has no end - a shell, a log viewer - never calls it,
	 * which is why it does nothing by default.
	 *
	 * @param agentId the agent
	 * @param summary one short line about the result, for the notification
	 */
	default void taskCompleted(String agentId, String summary) {
		// Not every window has a task that finishes.
	}

	/**
	 * The agent's session record was changed and should be persisted.
	 *
	 * @param agentId the agent
	 */
	void sessionUpdated(String agentId);

	/**
	 * Ask for a still thumbnail of a file, drawn by whichever Quick View plugin would
	 * preview it - the first page of a document, the head of a paste.
	 *
	 * <p>The answer comes at most once, on the event dispatch thread, with the picture or
	 * {@code null}. It may never come at all - a host that cannot draw thumbnails, as by
	 * default, stays silent - so a caller shows its own stand-in until it does.
	 *
	 * @param file      the file to draw
	 * @param maxWidth  the widest the picture may be, in pixels
	 * @param maxHeight the tallest it may be
	 * @param cancelled set when the picture is no longer wanted
	 * @param answer    given the picture, or {@code null} when none could be drawn
	 */
	default void thumbnail(Path file, int maxWidth, int maxHeight, AtomicBoolean cancelled,
			Consumer<BufferedImage> answer) {
		// No Quick View plugins to ask.
	}

	/**
	 * Ask whether any Quick View plugin can show a file - whether a dropped file is worth
	 * attaching with a picture, rather than naming by its path.
	 *
	 * <p>The answer comes at most once. A host that can tell answers later, on the event
	 * dispatch thread; one that cannot, as by default, answers {@code false} at once, on the
	 * caller's thread. A host may also never answer, so a caller settles for {@code false}
	 * after a while.
	 *
	 * @param file   the file
	 * @param answer given whether a Quick View plugin can show it
	 */
	default void quickViewSupports(Path file, Consumer<Boolean> answer) {
		answer.accept(false);
	}
}
