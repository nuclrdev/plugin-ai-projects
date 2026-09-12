package dev.nuclr.plugin.core.ai.projects.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/** One MCP server (or tool provider) an agent is allowed to reach. */
@Data
public class McpServerSpec {

	/** Name the agent sees, and the key used when an agent overrides one server. */
	private String name;

	/** Executable that starts the server. */
	private String command;

	/** Arguments passed to {@link #command}. */
	private List<String> args = List.of();

	/** Extra environment for the server process. */
	private Map<String, String> env = new LinkedHashMap<>();

	/** Disabled entries stay in the file so a project can turn one off without losing it. */
	private boolean enabled = true;

	/** Creates an empty spec. */
	public McpServerSpec() {}

	/**
	 * Convenience factory used by the built-in templates and by tests.
	 *
	 * @param name    server name
	 * @param command executable
	 * @param args    arguments
	 * @return a new enabled spec
	 */
	public static McpServerSpec of(String name, String command, List<String> args) {
		var spec = new McpServerSpec();
		spec.setName(name);
		spec.setCommand(command);
		spec.setArgs(copyArguments(args));
		return spec;
	}

	/** A defensive copy, so merging never aliases the project's own list. */
	public McpServerSpec copy() {
		var copy = new McpServerSpec();
		copy.name = name;
		copy.command = command;
		copy.args = copyArguments(args);
		copy.env = new LinkedHashMap<>();
		if (env != null) {
			env.forEach((key, value) -> {
				if (key != null && value != null) {
					copy.env.put(key, value);
				}
			});
		}
		copy.enabled = enabled;
		return copy;
	}

	/** Command line as shown in the harness and resolved-context views. */
	public String displayCommandLine() {
		var text = new StringBuilder(command == null ? "" : command);
		if (args != null) {
			for (var arg : args) {
				text.append(' ').append(arg);
			}
		}
		return text.toString();
	}

	private static List<String> copyArguments(List<String> arguments) {
		return arguments == null ? List.of()
				: arguments.stream().filter(java.util.Objects::nonNull).toList();
	}
}
