package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;

/**
 * One running conversational agent, behind whatever protocol its CLI speaks.
 *
 * <p>Events are delivered to the sink given at construction, on the session's own
 * reader thread; the window moves them to the event dispatch thread. The methods
 * here may be called from any thread.
 */
interface AgentSession extends AutoCloseable {

	/**
	 * Start the process and begin reading it.
	 *
	 * @throws IOException when it cannot be started
	 */
	void start() throws IOException;

	/**
	 * Send the user's next message.
	 *
	 * @param text the prompt
	 * @throws IOException when the process is no longer reading
	 */
	void prompt(String text) throws IOException;

	/**
	 * Stop the current turn, keeping the session.
	 *
	 * @throws IOException when the process is no longer reading
	 */
	void interrupt() throws IOException;

	/**
	 * Answer a {@link AgentEvent.PermissionRequest}.
	 *
	 * @param requestId the request
	 * @param optionId  the chosen {@link AgentEvent.PermissionOption}'s id
	 * @throws IOException when the process is no longer reading
	 */
	void answerPermission(String requestId, String optionId) throws IOException;

	/** The process id, or 0 before it starts. */
	long pid();

	/** End the session and its process tree. Safe to call more than once. */
	@Override
	void close();
}
