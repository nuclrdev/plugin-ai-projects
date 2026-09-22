package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;

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
	 * The command line with an access mode chosen in the conversation in place of the
	 * one it was planned with.
	 *
	 * <p>Whatever access the command already asked for is taken out first, so the CLI is
	 * not left to settle two modes between itself. A flag that is the CLI's access dial -
	 * one that more than one mode sets, such as Claude Code's {@code --permission-mode} -
	 * goes whatever its value. A flag only one mode uses goes only when it says exactly
	 * what that mode says: Pi's read-only mode is a {@code --tools} list, and the same flag
	 * with another list is the profile's tool choice, which is not this command's to drop.
	 *
	 * @param command   the command as planned, executable first, before any protocol
	 *                  translation
	 * @param connector the CLI's connector
	 * @param mode      the chosen mode
	 * @return the command to run, or empty when the CLI cannot run in that mode
	 */
	static Optional<List<String>> applyAccess(List<String> command, AgentConnector connector, AccessMode mode) {
		var arguments = connector.accessArguments(mode);
		if (arguments.isEmpty() || command.isEmpty()) {
			return arguments.map(ignored -> command);
		}
		var sequences = new ArrayList<List<String>>();
		for (var each : AccessMode.values()) {
			connector.accessArguments(each).ifPresent(words -> sequences.addAll(flagSequences(words)));
		}
		var variants = new HashMap<String, Set<List<String>>>();
		for (var sequence : sequences) {
			variants.computeIfAbsent(sequence.getFirst(), flag -> new HashSet<>()).add(sequence);
		}

		var result = new ArrayList<String>(command.size() + arguments.get().size());
		result.add(command.getFirst());
		next:
		for (var index = 1; index < command.size(); index++) {
			for (var sequence : sequences) {
				var flag = sequence.getFirst();
				if (!command.get(index).equals(flag) || index + sequence.size() > command.size()) {
					continue;
				}
				var words = command.subList(index, index + sequence.size());
				if (variants.get(flag).size() > 1 || words.equals(sequence)) {
					index += sequence.size() - 1;
					continue next;
				}
			}
			result.add(command.get(index));
		}
		result.addAll(arguments.get());
		return Optional.of(List.copyOf(result));
	}

	/** Cut a connector's access arguments into flags, each with the values that follow it. */
	private static List<List<String>> flagSequences(List<String> words) {
		var sequences = new ArrayList<List<String>>();
		List<String> current = null;
		for (var word : words) {
			if (word.startsWith("-")) {
				current = new ArrayList<>();
				sequences.add(current);
			}
			if (current != null) {
				current.add(word);
			}
		}
		return sequences;
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
