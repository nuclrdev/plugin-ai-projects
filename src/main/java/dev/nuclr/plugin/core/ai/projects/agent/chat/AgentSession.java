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

	/**
	 * Change the model this conversation runs on, without ending it.
	 *
	 * <p>Every CLI this plugin drives can do it - Claude Code takes a {@code set_model}
	 * control request, Codex a {@code thread/settings/update}, Pi a {@code set_model}
	 * command - but a session that cannot says so, and the window starts the agent again
	 * with the model on its command line instead.
	 *
	 * @param model the model, as the CLI names it
	 * @return whether the session took it; {@code false} means nothing was sent
	 * @throws IOException when the process is no longer reading
	 */
	default boolean setModel(String model) throws IOException {
		return false;
	}

	/**
	 * Change how hard the model thinks, without ending the conversation.
	 *
	 * @param effort the level, in the CLI's own vocabulary
	 * @return whether the session took it; {@code false} means nothing was sent
	 * @throws IOException when the process is no longer reading
	 */
	default boolean setEffort(String effort) throws IOException {
		return false;
	}

	/**
	 * Run one of the CLI's own commands, for a CLI whose commands are protocol calls
	 * rather than text - Codex compacts a thread with {@code thread/compact/start}, not
	 * with the word "/compact".
	 *
	 * @param name     the command, without its slash
	 * @param argument what was typed after it, possibly empty
	 * @return whether it was sent; {@code false} when this session has no such command
	 *         or nothing to run it against yet
	 * @throws IOException when the process is no longer reading
	 */
	default boolean runCommand(String name, String argument) throws IOException {
		return false;
	}

	/** The process id, or 0 before it starts. */
	long pid();

	/** End the session and its process tree. Safe to call more than once. */
	@Override
	void close();
}
