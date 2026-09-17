package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import tools.jackson.databind.JsonNode;

/**
 * Pi, the multi-provider coding agent.
 *
 * <p>Discovery runs {@code pi --mode rpc} and sends {@code get_available_models},
 * which lists the models of every provider Pi is signed in to. Pi fronts many
 * providers, so a model is named {@code provider/id}, the form {@code --model}
 * accepts.
 *
 * <p>Pi has no approval prompts and no sandbox. It can be made read-only by
 * allowing only its reading tools; anything else is full access.
 */
final class PiConnector implements AgentConnector {

	/** The levels every thinking model has unless its map removes them. */
	private static final List<String> STANDARD_LEVELS = List.of("off", "minimal", "low", "medium", "high");

	/** The levels a model has only when its map names them. */
	private static final List<String> EXTENDED_LEVELS = List.of("xhigh", "max");

	@Override
	public AgentProvider provider() {
		return AgentProvider.PI;
	}

	@Override
	public List<String> efforts() {
		return List.of("off", "minimal", "low", "medium", "high", "xhigh", "max");
	}

	@Override
	public String effortSetting() {
		return "--thinking";
	}

	@Override
	public List<String> modelArguments(String model) {
		return List.of("--model", model);
	}

	@Override
	public List<String> effortArguments(String effort) {
		return List.of("--thinking", effort);
	}

	@Override
	public Optional<List<String>> accessArguments(AccessMode mode) {
		return Optional.ofNullable(switch (mode) {
			// Pi's own documented read-only setup.
			case READ_ONLY -> List.of("--tools", "read,grep,find,ls");
			case FULL_ACCESS, CUSTOM -> List.<String>of();
			case ASK, AUTO -> null;
		});
	}

	@Override
	public String unsupportedReason(AccessMode mode) {
		return "Pi has no approval prompts or sandbox, so it cannot "
				+ (mode == AccessMode.ASK ? "ask first" : "review actions")
				+ ". Choose Read-only, or Full access in an isolated environment.";
	}

	/** Pi's built-in tools, as its README lists them. */
	private static final List<Tool> TOOLS = List.of(
			new Tool("read", "Reads files"),
			new Tool("bash", "Executes shell commands"),
			new Tool("powershell", "Executes PowerShell commands (Windows)"),
			new Tool("edit", "Edits files"),
			new Tool("write", "Creates or overwrites files"),
			new Tool("grep", "Searches file contents"),
			new Tool("find", "Finds files"),
			new Tool("ls", "Lists directories"));

	/** The tools Read-only mode keeps. */
	private static final List<String> READ_ONLY_TOOLS = List.of("read", "grep", "find", "ls");

	@Override
	public List<Tool> tools() {
		return TOOLS;
	}

	@Override
	public boolean canRestrictTools() {
		return true;
	}

	@Override
	public String allowedToolsMeaning() {
		return "Only these tools are available, extension tools included. Every other one - including tools "
				+ "a later Pi adds - is not.";
	}

	@Override
	public String blockedToolsMeaning() {
		return "These tools are disabled, including extension tools.";
	}

	@Override
	public List<String> allowedToolArguments(List<String> names) {
		return names.isEmpty() ? List.of() : List.of("--tools", String.join(",", names));
	}

	@Override
	public List<String> blockedToolArguments(List<String> names) {
		return names.isEmpty() ? List.of() : List.of("--exclude-tools", String.join(",", names));
	}

	@Override
	public List<String> toolProblems(List<String> allowed, List<String> blocked, AccessMode mode) {
		var problems = new ArrayList<String>();
		for (var name : concat(allowed, blocked)) {
			if (name.isBlank() || name.contains(",") || name.contains("(") || name.chars().anyMatch(Character::isWhitespace)) {
				problems.add("Tools: \"" + name + "\" - Pi takes plain tool names, without spaces, commas or patterns.");
			}
		}
		if (mode == AccessMode.READ_ONLY) {
			for (var name : allowed) {
				if (!READ_ONLY_TOOLS.contains(name)) {
					problems.add("Tools: Read-only access keeps only " + String.join(", ", READ_ONLY_TOOLS)
							+ ", so \"" + name + "\" cannot be allowed. Remove it or choose another access mode.");
				}
			}
		}
		return problems;
	}

	@Override
	public Optional<List<String>> accessArguments(AccessMode mode, List<String> allowed) {
		// Read-only is itself a tool list; an allowed list can only narrow it, and
		// passing both would give Pi two --tools flags.
		if (mode == AccessMode.READ_ONLY && !allowed.isEmpty()) {
			return Optional.of(List.of());
		}
		return accessArguments(mode);
	}

	private static List<String> concat(List<String> first, List<String> second) {
		var all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	@Override
	public boolean supportsMcp() {
		return false;
	}

	@Override
	public boolean canRestrictMcpServers() {
		return false;
	}

	@Override
	public boolean canSwitchOffMcpServers() {
		return false;
	}

	@Override
	public String mcpMeaning() {
		return "Pi has no MCP support by design. Use skills or CLI tools instead, or a Pi extension that adds MCP.";
	}

	@Override
	public McpSetup mcpSetup(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff, java.nio.file.Path runtimeDirectory) {
		return McpSetup.none();
	}

	@Override
	public List<String> mcpProblems(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff) {
		if (AgentConnector.enabledServers(servers).isEmpty() && !onlyProfileServers && switchedOff.isEmpty()) {
			return List.of();
		}
		return List.of("MCP servers: Pi has no MCP support, so these servers would be ignored. "
				+ "Remove them, or choose Claude Code or Codex.");
	}

	@Override
	public ModelCatalog discover(String executable, Duration timeout) throws IOException {
		// --no-session keeps discovery from leaving a session behind; --offline skips
		// start-up network checks, which the model list does not need.
		try (var process = JsonLineProcess.start(List.of(executable, "--mode", "rpc", "--no-session", "--offline"))) {
			process.send(Map.of("id", "nuclr-models", "type", "get_available_models"));
			var response = process.await(message -> "nuclr-models".equals(message.path("id").asString("")),
					timeout, "get_available_models response");
			if (!response.path("success").asBoolean(false)) {
				throw new IOException("get_available_models failed: " + response.path("error").asString("unknown error"));
			}
			var models = parseModels(response.path("data"));
			if (models.isEmpty()) {
				throw new IOException("Pi is not signed in to any provider");
			}
			return new ModelCatalog(provider(), models, true,
					"Models from the providers Pi is signed in to (RPC get_available_models). "
							+ models.size() + " available.");
		}
	}

	@Override
	public ModelCatalog builtIn(String reason) {
		return new ModelCatalog(provider(), List.of(), false, reason);
	}

	/**
	 * Read {@code get_available_models} data.
	 *
	 * @param data the response's {@code data}
	 * @return the models, as {@code provider/id}
	 * @throws IOException when it is not that shape
	 */
	static List<ModelCatalog.Model> parseModels(JsonNode data) throws IOException {
		var entries = data.has("models") ? data.get("models") : data;
		if (entries == null || !entries.isArray()) {
			throw new IOException("unexpected get_available_models data from Pi");
		}
		var models = new ArrayList<ModelCatalog.Model>();
		for (var entry : entries) {
			var id = CodexConnector.text(entry, "id");
			var provider = CodexConnector.text(entry, "provider");
			if (id == null || provider == null) {
				continue;
			}
			var name = CodexConnector.text(entry, "name");
			models.add(new ModelCatalog.Model(provider + "/" + id,
					(name == null ? id : name) + "  ·  " + provider, null, thinkingLevels(entry), null, null));
		}
		return models;
	}

	/**
	 * The thinking levels a model accepts, as Pi's models documentation defines them.
	 *
	 * <p>A model that cannot reason only has {@code off}. For one that can,
	 * {@code thinkingLevelMap} is tristate per level: a {@code null} value removes
	 * a level, a string value makes it available, and an omitted level is
	 * available only if it is a standard one - {@code xhigh} and {@code max} must be
	 * named to exist.
	 *
	 * @param model one entry from the model list
	 * @return the levels, lowest first
	 */
	static List<String> thinkingLevels(JsonNode model) {
		if (!model.path("reasoning").asBoolean(false)) {
			return List.of("off");
		}
		var map = model.path("thinkingLevelMap");
		var levels = new ArrayList<String>();
		for (var level : STANDARD_LEVELS) {
			if (!(map.has(level) && map.get(level).isNull())) {
				levels.add(level);
			}
		}
		for (var level : EXTENDED_LEVELS) {
			if (map.has(level) && !map.get(level).isNull()) {
				levels.add(level);
			}
		}
		return levels;
	}
}
