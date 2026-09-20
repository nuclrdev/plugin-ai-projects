package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;

/**
 * Putting a model or a thinking level chosen in the conversation onto the command line
 * the next session starts with.
 *
 * <p>Chosen with {@code /model}, this must win over whatever the agent's profile asked
 * for without editing the profile: a profile is often shared, and trying another model
 * for one conversation is not a decision about every agent that starts from it. So the
 * flag the profile contributed is taken back out and the chosen one put in its place,
 * rather than appended in the hope that the CLI prefers the last of two.
 */
final class LaunchOverrides {

	private LaunchOverrides() {
	}

	/**
	 * The command line with a chosen model and thinking level applied.
	 *
	 * @param command   the command as planned, executable first
	 * @param connector the CLI's connector, which knows its flags; {@code null} leaves
	 *                  the command alone
	 * @param model     the chosen model, or {@code null} to keep the planned one
	 * @param effort    the chosen thinking level, or {@code null} to keep the planned one
	 * @return the command to run
	 */
	static List<String> apply(List<String> command, AgentConnector connector, String model, String effort) {
		if (connector == null || command.isEmpty()) {
			return command;
		}
		var result = command;
		if (model != null && !model.isBlank()) {
			result = replace(result, connector.modelArguments(model.strip()));
		}
		if (effort != null && !effort.isBlank()) {
			result = replace(result, connector.effortArguments(effort.strip()));
		}
		return result;
	}

	/**
	 * Drop whatever the planned command said with this flag, and say it anew.
	 *
	 * @param command   the command
	 * @param arguments the flag and its value, as the connector writes them; nothing is
	 *                  changed when the connector passes the setting some other way
	 * @return the command
	 */
	private static List<String> replace(List<String> command, List<String> arguments) {
		if (arguments.isEmpty()) {
			return command;
		}
		var flag = arguments.getFirst();
		if (!flag.startsWith("-")) {
			// Not a flag at all - a positional argument or an environment variable's job.
			return command;
		}
		var result = new ArrayList<String>(command.size() + arguments.size());
		// From 1: the executable is never a flag, whatever it happens to be called.
		result.add(command.getFirst());
		for (var index = 1; index < command.size(); index++) {
			var word = command.get(index);
			if (word.equals(flag)) {
				// "--model opus": its value goes with it.
				index += arguments.size() - 1;
				continue;
			}
			if (word.startsWith(flag + "=")) {
				continue;
			}
			result.add(word);
		}
		result.addAll(arguments);
		return List.copyOf(result);
	}
}
