package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * An agent that speaks the Agent Client Protocol, started by its own command.
 *
 * <p>Adding another ACP agent is one more constant: its id, its name and the command
 * that puts it into ACP mode. No profile starts one - profiles are for the CLIs this
 * plugin has a connector for - so it runs with its own configuration.
 *
 * @param id          the window kind's suffix
 * @param displayName name shown in menus
 * @param description one line for menus
 * @param command     the command that starts it speaking ACP, executable first
 */
record AcpBackend(String id, String displayName, String description, List<String> command) implements ChatBackend {

	/** OpenCode, whose {@code acp} command speaks the protocol natively. */
	static final AcpBackend OPENCODE = new AcpBackend("opencode", "OpenCode (conversation)",
			"OpenCode in a conversation window, over the Agent Client Protocol.", List.of("opencode", "acp"));

	@Override
	public AgentProvider provider() {
		return null;
	}

	@Override
	public List<String> defaultCommand() {
		return command;
	}

	@Override
	public List<String> command(List<String> planned, String conversationId) {
		return planned;
	}

	@Override
	public AgentSession open(List<String> launched, Map<String, String> environment, Path workingDirectory,
			String conversationId, Consumer<AgentEvent> sink, IntConsumer onExit) {
		return new AcpSession(launched, environment, workingDirectory, conversationId, sink, onExit);
	}
}
