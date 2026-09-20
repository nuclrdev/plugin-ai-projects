package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.agent.AgentLaunch;
import dev.nuclr.plugin.core.ai.projects.agent.ContextDelivery;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * Codex over {@code codex app-server}.
 *
 * <p>The app server takes configuration, not the interactive CLI's flags - only
 * {@code -c}, {@code --enable} and {@code --disable}. So the planned command is
 * rewritten flag by flag into the configuration each flag stands for, and a flag
 * with no such equivalent refuses the launch rather than being silently dropped:
 * a profile that says "read-only" must not start an agent that is not.
 */
final class CodexBackend implements ChatBackend {

	@Override
	public String id() {
		return AgentProvider.CODEX.id();
	}

	@Override
	public String displayName() {
		return "Codex (conversation)";
	}

	@Override
	public String description() {
		return "Codex in a conversation window, over its app server: replies, commands, edits and approvals drawn natively.";
	}

	@Override
	public AgentProvider provider() {
		return AgentProvider.CODEX;
	}

	@Override
	public List<String> defaultCommand() {
		return List.of(AgentProvider.CODEX.defaultExecutable());
	}

	@Override
	public List<String> command(List<String> planned, String conversationId) throws AgentLaunch.Refused {
		return appServerCommand(planned);
	}

	/**
	 * The app-server command for an interactive Codex command line.
	 *
	 * @param planned the interactive command, executable first
	 * @return {@code codex app-server} with the same settings
	 * @throws AgentLaunch.Refused naming the first argument with no app-server equivalent
	 */
	static List<String> appServerCommand(List<String> planned) throws AgentLaunch.Refused {
		var command = new ArrayList<String>();
		command.add(planned.getFirst());
		command.add("app-server");
		var writable = new ArrayList<String>();
		for (var index = 1; index < planned.size(); index++) {
			var argument = planned.get(index);
			switch (argument) {
				case "-c", "--config", "--enable", "--disable" -> {
					command.add(argument);
					command.add(value(planned, ++index, argument));
				}
				case "-m", "--model" -> config(command, "model", value(planned, ++index, argument));
				case "-s", "--sandbox" -> config(command, "sandbox_mode", value(planned, ++index, argument));
				case "-a", "--ask-for-approval" -> config(command, "approval_policy", value(planned, ++index, argument));
				case "--approve-for-me" -> {
					config(command, "sandbox_mode", "workspace-write");
					config(command, "approval_policy", "on-request");
					config(command, "approvals_reviewer", "auto_review");
				}
				case "--dangerously-bypass-approvals-and-sandbox", "--yolo" -> {
					config(command, "sandbox_mode", "danger-full-access");
					config(command, "approval_policy", "never");
				}
				case "--search" -> config(command, "web_search", "live");
				case "--add-dir" -> writable.add(value(planned, ++index, argument));
				default -> throw new AgentLaunch.Refused("The profile starts Codex with \"" + argument
						+ "\", which its app server - what a conversation window talks to - does not take. "
						+ "Remove it from the profile's startup arguments.");
			}
		}
		if (!writable.isEmpty()) {
			command.add("-c");
			command.add("sandbox_workspace_write.writable_roots=["
					+ String.join(", ", writable.stream().map(ContextDelivery::tomlString).toList()) + "]");
		}
		return command;
	}

	private static void config(List<String> command, String key, String value) {
		command.add("-c");
		command.add(key + "=" + ContextDelivery.tomlString(value));
	}

	private static String value(List<String> planned, int index, String flag) throws AgentLaunch.Refused {
		if (index >= planned.size()) {
			throw new AgentLaunch.Refused("The profile starts Codex with \"" + flag + "\" and no value after it.");
		}
		return planned.get(index);
	}

	@Override
	public AgentSession open(List<String> launched, Map<String, String> environment, Path workingDirectory,
			String conversationId, Consumer<AgentEvent> sink, IntConsumer onExit) {
		return new CodexSession(launched, environment, workingDirectory, conversationId, sink, onExit);
	}
}
