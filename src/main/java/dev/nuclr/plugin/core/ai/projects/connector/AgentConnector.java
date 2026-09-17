package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;

/**
 * Everything the plugin knows about driving one agent CLI, in one place: which
 * models and reasoning efforts it offers, which access modes it can honour, and
 * the flags that select each.
 *
 * <p>Deliberately the bare minimum for now - models, thinking and permissions.
 * The same connector will later start sessions, which is why the discovery and
 * the flags live together: the code that asks a CLI what it accepts is the code
 * that tells it what to do, so the two cannot drift apart.
 *
 * <p>Discovery uses each CLI's own structured protocol, not its human-readable
 * output: Codex's app-server, Pi's RPC mode, Claude Code's control protocol.
 */
public interface AgentConnector {

	/** How long discovery may take before giving up. */
	Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);

	/** The provider this drives. */
	AgentProvider provider();

	/** Every reasoning effort the CLI accepts, lowest first; a model may accept fewer. */
	List<String> efforts();

	/** How the effort reaches the CLI, for the editor's hint, e.g. {@code --effort}. */
	String effortSetting();

	/**
	 * The flags that select a model.
	 *
	 * @param model the model id, as the CLI takes it
	 * @return the arguments
	 */
	List<String> modelArguments(String model);

	/**
	 * The flags that select a reasoning effort.
	 *
	 * @param effort the effort, in the CLI's own vocabulary
	 * @return the arguments
	 */
	List<String> effortArguments(String effort);

	/**
	 * The flags that put the CLI in an access mode.
	 *
	 * @param mode the mode
	 * @return the arguments, empty for {@link AccessMode#CUSTOM}; absent when the CLI
	 *         cannot honour the mode
	 */
	Optional<List<String>> accessArguments(AccessMode mode);

	/**
	 * Why the CLI cannot honour a mode.
	 *
	 * @param mode the unsupported mode
	 * @return one sentence
	 */
	String unsupportedReason(AccessMode mode);

	/**
	 * One built-in tool.
	 *
	 * @param name        the name the CLI's tool flags take
	 * @param description one line about what it does
	 */
	record Tool(String name, String description) {
	}

	/** The CLI's built-in tools, for suggestions; tools from MCP servers or extensions are not listed. */
	List<Tool> tools();

	/**
	 * Whether the CLI can be limited to a chosen set of tools. When it cannot, the
	 * allowed list only switches optional tools on.
	 */
	boolean canRestrictTools();

	/** What the allowed list means for this CLI, one or two sentences. */
	String allowedToolsMeaning();

	/** What the blocked list means for this CLI, one sentence. */
	String blockedToolsMeaning();

	/**
	 * The flags for an allowed-tools list.
	 *
	 * @param names the tools, already validated; empty for none
	 * @return the arguments, empty when the list is empty
	 */
	List<String> allowedToolArguments(List<String> names);

	/**
	 * The flags for a blocked-tools list.
	 *
	 * @param names the tools, already validated; empty for none
	 * @return the arguments, empty when the list is empty
	 */
	List<String> blockedToolArguments(List<String> names);

	/**
	 * Everything that would make these tool lists wrong for this CLI.
	 *
	 * @param allowed the allowed tools
	 * @param blocked the blocked tools
	 * @param mode    the access mode in force
	 * @return messages, empty when the lists can be used
	 */
	List<String> toolProblems(List<String> allowed, List<String> blocked, AccessMode mode);

	/**
	 * Whether a name - ignoring any {@code (pattern)} after it - is one of {@link #tools()}.
	 *
	 * @param name the entry
	 * @return whether the CLI documents it as a built-in tool
	 */
	default boolean isBuiltInTool(String name) {
		if (name == null) {
			return false;
		}
		var bare = name.strip();
		var open = bare.indexOf('(');
		if (open > 0) {
			bare = bare.substring(0, open).strip();
		}
		var wanted = bare;
		return tools().stream().anyMatch(tool -> tool.name().equals(wanted));
	}

	// ------------------------------------------------------------------ MCP servers

	/** Whether the CLI can use MCP servers at all. */
	boolean supportsMcp();

	/** Whether the CLI can be limited to the profile's servers, ignoring the ones the user configured. */
	boolean canRestrictMcpServers();

	/** Whether servers the user configured can be switched off by name. */
	boolean canSwitchOffMcpServers();

	/** How the profile's servers reach the CLI, one sentence for the editor. */
	String mcpMeaning();

	/**
	 * One secret handed to the agent process in an environment variable.
	 *
	 * @param variable the variable the agent process gets
	 * @param secret   where the value comes from: the credential store, or another
	 *                 environment variable
	 */
	record SecretBinding(String variable, dev.nuclr.plugin.core.ai.projects.model.McpSecret secret) {
	}

	/**
	 * What a launch needs for MCP: arguments, possibly a configuration file those
	 * arguments name, and the secrets to put in the agent's environment.
	 *
	 * <p>Secrets never appear in the arguments or the file - only the names of the
	 * variables that carry them - so neither the command line nor anything on disk
	 * holds one. Definitions go in a file rather than on the command line to keep
	 * them intact through Windows argument quoting.
	 *
	 * @param arguments         the arguments
	 * @param configFileName    the file to write before launching, or {@code null} for none
	 * @param configFileContent its content, or {@code null}
	 * @param secrets           the variables to set on the agent process
	 */
	record McpSetup(List<String> arguments, String configFileName, String configFileContent,
			List<SecretBinding> secrets) {

		/** Nothing to pass. */
		public static McpSetup none() {
			return new McpSetup(List.of(), null, null, List.of());
		}
	}

	/**
	 * What to pass for a profile's MCP servers.
	 *
	 * @param servers            the profile's servers; disabled ones are left out
	 * @param onlyProfileServers whether to ignore servers the user configured
	 * @param switchedOff        servers the user configured to switch off, by name
	 * @param runtimeDirectory   where a configuration file would be written
	 * @return the setup, already validated
	 */
	McpSetup mcpSetup(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers, boolean onlyProfileServers,
			List<String> switchedOff, java.nio.file.Path runtimeDirectory);

	/**
	 * Everything that would make these MCP settings wrong for this CLI.
	 *
	 * @param servers            the profile's servers
	 * @param onlyProfileServers whether to ignore servers the user configured
	 * @param switchedOff        servers the user configured to switch off
	 * @return messages, empty when the settings can be used
	 */
	List<String> mcpProblems(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff);

	/**
	 * The MCP servers the user configured for this CLI, by name, for suggestions.
	 *
	 * @param executable the resolved executable
	 * @param timeout    how long to allow
	 * @return the names; empty when the CLI cannot say
	 * @throws IOException when the CLI cannot be asked
	 */
	default List<String> configuredMcpServers(String executable, Duration timeout) throws IOException {
		return List.of();
	}

	/**
	 * The profile's servers that take part: switched on, with a name.
	 *
	 * @param servers the profile's servers, possibly {@code null}
	 * @return the enabled ones
	 */
	static List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> enabledServers(
			List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers) {
		return servers == null ? List.of() : servers.stream()
				.filter(server -> server != null && server.isEnabled() && server.getName() != null
						&& !server.getName().isBlank())
				.toList();
	}

	/**
	 * Ask the installed CLI what it offers.
	 *
	 * @param executable the resolved executable to run
	 * @param timeout    how long to allow
	 * @return a live catalogue
	 * @throws IOException when the CLI cannot be asked or answers with something unusable
	 */
	ModelCatalog discover(String executable, Duration timeout) throws IOException;

	/**
	 * What is known without asking, for when the CLI cannot be.
	 *
	 * @param reason why it is being used, shown to the user
	 * @return a catalogue marked as not live
	 */
	ModelCatalog builtIn(String reason);

	/**
	 * Whether the CLI can honour an access mode.
	 *
	 * @param mode the mode
	 * @return whether {@link #accessArguments} has an answer
	 */
	default boolean supports(AccessMode mode) {
		return accessArguments(mode).isPresent();
	}

	/** Ask wherever the CLI can ask, so nothing happens unseen by default; otherwise full access. */
	default AccessMode defaultAccessMode() {
		return supports(AccessMode.ASK) ? AccessMode.ASK : AccessMode.FULL_ACCESS;
	}

	/**
	 * The flags for a model, effort and access mode together; blank values are left out.
	 *
	 * @param model  the model, or blank for the CLI's default
	 * @param effort the effort, or blank for the model's default
	 * @param mode   the access mode, or {@code null} for {@link #defaultAccessMode()}
	 * @return the arguments, in that order
	 * @throws IllegalArgumentException when the CLI cannot honour the access mode
	 */
	default List<String> launchArguments(String model, String effort, AccessMode mode) {
		return launchArguments(model, effort, mode, List.of(), List.of());
	}

	/**
	 * The flags for a model, effort, access mode and tool lists together.
	 *
	 * @param model   the model, or blank for the CLI's default
	 * @param effort  the effort, or blank for the model's default
	 * @param mode    the access mode, or {@code null} for {@link #defaultAccessMode()}
	 * @param allowed the allowed tools, possibly empty
	 * @param blocked the blocked tools, possibly empty
	 * @return the arguments, in that order
	 * @throws IllegalArgumentException when the access mode or the tool lists cannot be honoured
	 */
	default List<String> launchArguments(String model, String effort, AccessMode mode, List<String> allowed,
			List<String> blocked) {
		var arguments = new ArrayList<String>();
		if (model != null && !model.isBlank()) {
			arguments.addAll(modelArguments(model.trim()));
		}
		if (effort != null && !effort.isBlank()) {
			arguments.addAll(effortArguments(effort.trim()));
		}
		var access = mode == null ? defaultAccessMode() : mode;
		var problems = toolProblems(allowed, blocked, access);
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException(problems.getFirst());
		}
		arguments.addAll(accessArguments(access, allowed)
				.orElseThrow(() -> new IllegalArgumentException(unsupportedReason(access))));
		arguments.addAll(allowedToolArguments(allowed));
		arguments.addAll(blockedToolArguments(blocked));
		return List.copyOf(arguments);
	}

	/**
	 * The access flags when an allowed-tools list is also in force. Most CLIs keep
	 * the two apart; one whose access mode is itself a tool list (Pi) folds them
	 * together here.
	 *
	 * @param mode    the mode
	 * @param allowed the allowed tools
	 * @return the arguments, or empty when the mode is unsupported
	 */
	default Optional<List<String>> accessArguments(AccessMode mode, List<String> allowed) {
		return accessArguments(mode);
	}

	/**
	 * Split a tool entry into its name and, when present, its {@code (pattern)}.
	 *
	 * @param entry the entry
	 * @return the bare name
	 */
	static String toolName(String entry) {
		var bare = entry.strip();
		var open = bare.indexOf('(');
		return open > 0 ? bare.substring(0, open).strip() : bare;
	}
}
