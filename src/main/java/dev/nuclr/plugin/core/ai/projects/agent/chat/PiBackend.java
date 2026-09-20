package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * Pi over {@code pi --mode rpc}. Pi's own flags work in RPC mode, so the planned
 * command is kept and only the mode added. The conversation is named up front with
 * {@code --session-id}, which Pi creates when it does not exist yet and continues
 * when it does, so a new conversation and a resumed one start the same way.
 */
final class PiBackend implements ChatBackend {

	@Override
	public String id() {
		return AgentProvider.PI.id();
	}

	@Override
	public String displayName() {
		return "Pi (conversation)";
	}

	@Override
	public String description() {
		return "Pi in a conversation window, over its RPC mode: replies, reasoning and tool calls drawn natively.";
	}

	@Override
	public AgentProvider provider() {
		return AgentProvider.PI;
	}

	@Override
	public List<String> defaultCommand() {
		return List.of(AgentProvider.PI.defaultExecutable());
	}

	@Override
	public List<String> command(List<String> planned, String conversationId) {
		var command = new ArrayList<>(planned);
		command.addAll(List.of("--mode", "rpc", "--session-id",
				conversationId != null ? conversationId : UUID.randomUUID().toString()));
		return command;
	}

	@Override
	public AgentSession open(List<String> launched, Map<String, String> environment, Path workingDirectory,
			String conversationId, Consumer<AgentEvent> sink, IntConsumer onExit) {
		return new PiSession(launched, environment, workingDirectory, sink, onExit);
	}
}
