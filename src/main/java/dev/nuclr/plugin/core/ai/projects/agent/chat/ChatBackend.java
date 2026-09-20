package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.agent.AgentLaunch;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * One CLI a conversation window can drive: how to start it in its protocol, and the
 * session that speaks that protocol.
 *
 * <p>Everything CLI-specific about a conversation window is here, so adding an agent
 * is adding a backend - and one that speaks ACP needs only its command line, since
 * {@link AcpSession} speaks for all of them.
 */
interface ChatBackend {

	/** The CLI's id, the suffix of its window kind, e.g. {@code claude-code}. */
	String id();

	/** Name shown in menus. */
	String displayName();

	/** One line for menus and tooltips. */
	String description();

	/** The provider whose profiles can start it, or {@code null} when no profile can. */
	AgentProvider provider();

	/** The command started when there is no profile, executable first. */
	List<String> defaultCommand();

	/**
	 * The command line for a launch, executable first.
	 *
	 * @param planned        the command as planned from a profile, or {@link #defaultCommand()}
	 * @param conversationId the conversation to resume, or {@code null} for a new one
	 * @return the command that starts the CLI in its protocol
	 * @throws AgentLaunch.Refused when the planned command cannot be run this way
	 */
	List<String> command(List<String> planned, String conversationId) throws AgentLaunch.Refused;

	/**
	 * The session for a started launch. Nothing runs until {@link AgentSession#start()}.
	 *
	 * @param launched         the command line, executable resolved
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param conversationId   the conversation to resume, or {@code null}
	 * @param sink             receives every event, on the session's reader thread
	 * @param onExit           receives the exit status once the process has ended
	 * @return the session
	 */
	AgentSession open(List<String> launched, Map<String, String> environment, Path workingDirectory,
			String conversationId, Consumer<AgentEvent> sink, IntConsumer onExit);

	/** The backends this plugin ships, in menu order. */
	List<ChatBackend> BUILT_IN = List.of(new ClaudeCodeBackend(), new CodexBackend(), new PiBackend(),
			AcpBackend.OPENCODE);
}
