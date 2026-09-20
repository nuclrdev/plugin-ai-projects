package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/** Claude Code over stream-JSON: the planned command, put into the protocol, resumed with {@code --resume}. */
final class ClaudeCodeBackend implements ChatBackend {

	@Override
	public String id() {
		return AgentProvider.CLAUDE_CODE.id();
	}

	@Override
	public String displayName() {
		return "Claude Code (conversation)";
	}

	@Override
	public String description() {
		return "Claude Code in a conversation window: replies, tool calls and permission prompts drawn natively.";
	}

	@Override
	public AgentProvider provider() {
		return AgentProvider.CLAUDE_CODE;
	}

	@Override
	public List<String> defaultCommand() {
		return List.of(AgentProvider.CLAUDE_CODE.defaultExecutable());
	}

	@Override
	public List<String> command(List<String> planned, String conversationId) {
		var command = new ArrayList<>(planned);
		command.addAll(ClaudeCodeSession.PROTOCOL_ARGUMENTS);
		if (conversationId != null) {
			command.addAll(ClaudeCodeSession.resumeArguments(conversationId));
		}
		return command;
	}

	@Override
	public AgentSession open(List<String> launched, Map<String, String> environment, Path workingDirectory,
			String conversationId, Consumer<AgentEvent> sink, IntConsumer onExit) {
		return new ClaudeCodeSession(launched, environment, workingDirectory, sink, onExit);
	}
}
