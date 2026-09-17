package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic's Claude Code.
 *
 * <p>Discovery starts {@code claude} in stream-JSON mode and sends the control
 * protocol's {@code initialize} request - the handshake the Agent SDK performs.
 * Its answer lists the models this account can use, each with the effort levels
 * it supports and whether it supports auto mode. No prompt is sent, so nothing
 * is billed, and the session is neither saved nor given the user's MCP servers.
 */
final class ClaudeCodeConnector implements AgentConnector {

	private static final String REQUEST_ID = "nuclr-initialize";

	/** The entry Claude Code lists for "no --model", which a blank model already means. */
	private static final String DEFAULT_ENTRY = "default";

	@Override
	public AgentProvider provider() {
		return AgentProvider.CLAUDE_CODE;
	}

	@Override
	public List<String> efforts() {
		return List.of("low", "medium", "high", "xhigh", "max");
	}

	@Override
	public String effortSetting() {
		return "--effort";
	}

	@Override
	public List<String> modelArguments(String model) {
		return List.of("--model", model);
	}

	@Override
	public List<String> effortArguments(String effort) {
		return List.of("--effort", effort);
	}

	@Override
	public Optional<List<String>> accessArguments(AccessMode mode) {
		return Optional.of(switch (mode) {
			case READ_ONLY -> List.of("--permission-mode", "plan");
			case ASK -> List.of("--permission-mode", "manual");
			case AUTO -> List.of("--permission-mode", "auto");
			case FULL_ACCESS -> List.of("--permission-mode", "bypassPermissions");
			case CUSTOM -> List.<String>of();
		});
	}

	@Override
	public String unsupportedReason(AccessMode mode) {
		return mode == AccessMode.AUTO
				? "This Claude model does not support auto mode. Choose another model, or Ask."
				: "Claude Code does not support " + mode.label() + ".";
	}

	/** Claude Code's documented built-in tools (tools reference, Claude Code 2.1). */
	private static final List<Tool> TOOLS = List.of(
			new Tool("Agent", "Spawns a subagent with its own context window"),
			new Tool("Artifact", "Publishes an HTML or Markdown file as a private page"),
			new Tool("AskUserQuestion", "Asks multiple-choice questions to clarify requirements"),
			new Tool("Bash", "Executes shell commands"),
			new Tool("CronCreate", "Schedules a recurring or one-shot prompt in the session"),
			new Tool("CronDelete", "Cancels a scheduled task"),
			new Tool("CronList", "Lists scheduled tasks"),
			new Tool("Edit", "Makes targeted edits to files"),
			new Tool("EndConversation", "Ends the session on sustained abusive input"),
			new Tool("EnterPlanMode", "Switches to plan mode"),
			new Tool("EnterWorktree", "Creates an isolated git worktree and switches into it"),
			new Tool("ExitPlanMode", "Presents a plan for approval and leaves plan mode"),
			new Tool("ExitWorktree", "Leaves a worktree session"),
			new Tool("Glob", "Finds files by pattern"),
			new Tool("Grep", "Searches file contents"),
			new Tool("ListAgents", "Lists the agents it can message"),
			new Tool("ListMcpResourcesTool", "Lists resources from MCP servers"),
			new Tool("LSP", "Code intelligence through language servers"),
			new Tool("Monitor", "Runs a command in the background and watches its output"),
			new Tool("NotebookEdit", "Modifies Jupyter notebook cells"),
			new Tool("PowerShell", "Executes PowerShell commands"),
			new Tool("PushNotification", "Sends a desktop and phone notification"),
			new Tool("Read", "Reads files"),
			new Tool("ReadMcpResourceTool", "Reads an MCP resource"),
			new Tool("RemoteTrigger", "Manages Routines on claude.ai"),
			new Tool("ReportFindings", "Reports code-review findings"),
			new Tool("ScheduleWakeup", "Reschedules a self-paced loop"),
			new Tool("SendFeedback", "Drafts a feedback report about Claude Code"),
			new Tool("SendMessage", "Messages another agent or session"),
			new Tool("SendUserFile", "Sends files to your device"),
			new Tool("ShareOnboardingGuide", "Uploads an onboarding guide and returns a link"),
			new Tool("Skill", "Runs a skill"),
			new Tool("SubagentHandback", "Delivers a subagent's final report"),
			new Tool("TaskCreate", "Creates a task in the task list"),
			new Tool("TaskGet", "Reads a task"),
			new Tool("TaskList", "Lists tasks"),
			new Tool("TaskOutput", "Reads a background task's output"),
			new Tool("TaskStop", "Stops a background task"),
			new Tool("TaskUpdate", "Updates or deletes a task"),
			new Tool("TodoWrite", "Manages the session checklist"),
			new Tool("ToolSearch", "Finds and loads deferred tools"),
			new Tool("WaitForMcpServers", "Waits for MCP servers still connecting"),
			new Tool("WebFetch", "Fetches a URL"),
			new Tool("WebSearch", "Searches the web"),
			new Tool("Workflow", "Runs a workflow of many subagents"),
			new Tool("Write", "Creates or overwrites files"));

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
		return "Only these built-in tools are available. Every other one - including tools a later "
				+ "Claude Code adds - is not.";
	}

	@Override
	public String blockedToolsMeaning() {
		return "These are denied. A pattern narrows it, e.g. Bash(git push *), WebFetch(domain:example.com); "
				+ "MCP tools can be named as mcp__server__tool.";
	}

	@Override
	public List<String> allowedToolArguments(List<String> names) {
		return names.isEmpty() ? List.of() : List.of("--tools", String.join(",", names));
	}

	@Override
	public List<String> blockedToolArguments(List<String> names) {
		return names.isEmpty() ? List.of() : List.of("--disallowedTools", String.join(",", names));
	}

	@Override
	public List<String> toolProblems(List<String> allowed, List<String> blocked, AccessMode mode) {
		var problems = new ArrayList<String>();
		for (var name : allowed) {
			if (name.contains("(") || name.contains(",") || name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
				problems.add("Tools: \"" + name + "\" - the allowed list takes plain tool names; patterns belong in Blocked.");
			}
		}
		for (var name : blocked) {
			if (name.isBlank() || name.contains(",")) {
				problems.add("Tools: \"" + name + "\" cannot contain a comma.");
			} else if (name.contains("(") && !name.strip().endsWith(")")) {
				problems.add("Tools: \"" + name + "\" - a pattern must end with a closing parenthesis.");
			}
		}
		return problems;
	}

	@Override
	public String sandboxNetworkMeaning(AccessMode mode) {
		// Blocking WebFetch and WebSearch is not isolation: curl, wget, Python and the like still run in the shell.
		return "Claude Code does not sandbox commands by default, so they already reach the network. A profile "
				+ "cannot guarantee network isolation for Claude Code: blocking WebFetch and WebSearch still leaves "
				+ "curl, wget, scripts and every other command free to connect. That needs the shell tools (Bash, "
				+ "PowerShell) blocked on the Tools tab, or an OS-level sandbox or firewall.";
	}

	@Override
	public List<String> sandboxNetworkArguments(AccessMode mode) {
		return List.of();
	}

	@Override
	public String extraFoldersMeaning(AccessMode mode) {
		return "Claude Code's file tools can work in these as they do in the project folder; "
				+ "the access mode still decides what they may change.";
	}

	@Override
	public List<String> extraFolderArguments(List<String> folders) {
		return AgentConnector.addDirArguments(folders);
	}

	/** The tools that run commands; a command rule is written for each. */
	private static final List<String> SHELL_TOOLS = List.of("Bash", "PowerShell");

	@Override
	public boolean supportsCommandRules() {
		return true;
	}

	@Override
	public String allowedCommandsMeaning() {
		return "These run without asking, in Ask and Auto modes; other commands still ask. "
				+ "Full access already runs everything.";
	}

	@Override
	public String blockedCommandsMeaning() {
		return "These never run, in every access mode, including Full access.";
	}

	@Override
	public List<String> allowedCommandArguments(List<String> commands) {
		return commands.isEmpty() ? List.of() : List.of("--allowedTools", String.join(",", commandPatterns(commands)));
	}

	@Override
	public List<String> blockedCommandPatterns(List<String> commands) {
		return commandPatterns(commands);
	}

	@Override
	public List<String> commandProblems(List<String> allowed, List<String> blocked, List<String> allowedTools) {
		var problems = new ArrayList<>(AgentConnector.super.commandProblems(allowed, blocked, allowedTools));
		if (!allowed.isEmpty() && !allowedTools.isEmpty() && SHELL_TOOLS.stream().noneMatch(allowedTools::contains)) {
			problems.add("Commands: agents are limited to tools that cannot run commands. "
					+ "Allow Bash or PowerShell on the Tools tab, or remove the allowed commands.");
		}
		return problems;
	}

	/**
	 * Each command as a rule for each shell tool: the command on its own, and with
	 * any arguments after it - so blocking {@code git push} also stops a bare
	 * {@code git push}, without also stopping {@code git pushx}.
	 */
	private static List<String> commandPatterns(List<String> commands) {
		var patterns = new ArrayList<String>();
		for (var tool : SHELL_TOOLS) {
			for (var command : commands) {
				var prefix = command.strip();
				patterns.add(tool + "(" + prefix + ")");
				patterns.add(tool + "(" + prefix + " *)");
			}
		}
		return patterns;
	}

	/** The file the profile's servers are written to; {@code --mcp-config} names it. */
	static final String MCP_CONFIG_FILE = "nuclr-mcp.json";

	private static final java.util.regex.Pattern SERVER_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

	@Override
	public boolean supportsMcp() {
		return true;
	}

	@Override
	public boolean canRestrictMcpServers() {
		return true;
	}

	@Override
	public boolean canSwitchOffMcpServers() {
		return false;
	}

	@Override
	public String mcpMeaning() {
		return "The profile's servers are written to " + MCP_CONFIG_FILE + " and passed with --mcp-config.";
	}

	@Override
	public McpSetup mcpSetup(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff, java.nio.file.Path runtimeDirectory) {
		var enabled = AgentConnector.enabledServers(servers);
		var arguments = new ArrayList<String>();
		var bindings = new ArrayList<SecretBinding>();
		String content = null;
		// Strict mode is documented together with --mcp-config, and without one a Claude Code
		// has been seen to go on loading the servers configured elsewhere. So "only the
		// profile's servers" always passes a file - an empty one when there are none.
		if (!enabled.isEmpty() || onlyProfileServers) {
			var definitions = new java.util.LinkedHashMap<String, Object>();
			for (var server : enabled) {
				definitions.put(McpSupport.text(server.getName()), definition(server, bindings));
			}
			content = dev.nuclr.plugin.core.ai.projects.store.Json.toJson(Map.of("mcpServers", definitions));
			arguments.add("--mcp-config");
			arguments.add(runtimeDirectory.resolve(MCP_CONFIG_FILE).toString());
		}
		if (onlyProfileServers) {
			arguments.add("--strict-mcp-config");
		}
		return new McpSetup(List.copyOf(arguments), content == null ? null : MCP_CONFIG_FILE, content,
				List.copyOf(bindings));
	}

	/**
	 * One server as Claude Code's MCP configuration has it. Secrets are written as
	 * {@code ${VARIABLE}}, which Claude Code expands from its own environment.
	 */
	private static Map<String, Object> definition(dev.nuclr.plugin.core.ai.projects.model.McpServerSpec server,
			List<SecretBinding> bindings) {
		var definition = new java.util.LinkedHashMap<String, Object>();
		if (server.remote()) {
			definition.put("type", server.transportOrDefault());
			definition.put("url", McpSupport.text(server.getUrl()));
			var headers = new java.util.LinkedHashMap<String, String>(McpSupport.nullToEmpty(server.getHeaders()));
			if (dev.nuclr.plugin.core.ai.projects.model.McpServerSpec.AUTH_BEARER.equals(server.authOrDefault())
					&& server.getBearerToken() != null) {
				headers.put("Authorization", "Bearer ${" + bind(server, "TOKEN", server.getBearerToken(), bindings) + "}");
			}
			McpSupport.nullToEmpty(server.getSecretHeaders()).forEach((name, secret) ->
					headers.put(name, "${" + bind(server, "HEADER_" + name, secret, bindings) + "}"));
			if (!headers.isEmpty()) {
				definition.put("headers", headers);
			}
		} else {
			definition.put("type", "stdio");
			definition.put("command", server.getCommand());
			definition.put("args", server.getArgs() == null ? List.of() : server.getArgs());
			var env = new java.util.LinkedHashMap<String, String>(McpSupport.nullToEmpty(server.getEnv()));
			McpSupport.nullToEmpty(server.getSecretEnv()).forEach((name, secret) ->
					env.put(name, "${" + bind(server, "ENV_" + name, secret, bindings) + "}"));
			if (!env.isEmpty()) {
				definition.put("env", env);
			}
		}
		return definition;
	}

	/** The variable a secret is read from; a stored secret is also bound to it for launch. */
	private static String bind(dev.nuclr.plugin.core.ai.projects.model.McpServerSpec server, String part,
			dev.nuclr.plugin.core.ai.projects.model.McpSecret secret, List<SecretBinding> bindings) {
		var variable = McpSupport.variableFor(server, part, secret);
		if (!secret.fromEnvironment()) {
			bindings.add(new SecretBinding(variable, secret));
		}
		return variable;
	}

	@Override
	public List<String> mcpProblems(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff) {
		var problems = new ArrayList<String>();
		if (!switchedOff.isEmpty()) {
			problems.add("MCP servers: Claude Code cannot switch off servers configured outside the profile. "
					+ "Choose \"Only the profile's servers\", or block their tools on the Tools tab.");
		}
		for (var server : AgentConnector.enabledServers(servers)) {
			if (!SERVER_NAME.matcher(McpSupport.text(server.getName())).matches()) {
				problems.add("MCP servers: \"" + McpSupport.text(server.getName())
						+ "\" - use letters, digits, '-' and '_' only, so its tools can be named mcp__server__tool.");
			}
			problems.addAll(McpSupport.problems(server));
		}
		problems.addAll(McpSupport.bindingConflicts(
				mcpSetup(servers, onlyProfileServers, List.of(), java.nio.file.Path.of("")).secrets()));
		return problems;
	}

	@Override
	public ModelCatalog discover(String executable, Duration timeout) throws IOException {
		try (var process = JsonLineProcess.start(List.of(executable, "-p",
				"--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
				"--no-session-persistence", "--strict-mcp-config"))) {
			process.send(Map.of("type", "control_request", "request_id", REQUEST_ID,
					"request", Map.of("subtype", "initialize")));
			var message = process.await(each -> "control_response".equals(each.path("type").asString(""))
					&& REQUEST_ID.equals(each.path("response").path("request_id").asString("")),
					timeout, "initialize response");
			var response = message.path("response");
			if (!"success".equals(response.path("subtype").asString(""))) {
				throw new IOException("initialize failed: " + response.path("error").asString("unknown error"));
			}
			var models = parseModels(response.path("response"));
			if (models.isEmpty()) {
				throw new IOException("Claude Code reported no models");
			}
			return new ModelCatalog(provider(), models, true,
					"Models this account can use, from Claude Code (initialize). " + models.size() + " available.");
		}
	}

	@Override
	public ModelCatalog builtIn(String reason) {
		return new ModelCatalog(provider(), List.of(
				ModelCatalog.Model.named("fable", "Fable (latest)"),
				ModelCatalog.Model.named("opus", "Opus (latest)"),
				ModelCatalog.Model.named("sonnet", "Sonnet (latest)"),
				ModelCatalog.Model.named("haiku", "Haiku (latest)")), false, reason);
	}

	/**
	 * Read the models from an {@code initialize} response.
	 *
	 * @param initialize the inner response object
	 * @return the models, without the "default" entry
	 * @throws IOException when there is no model list
	 */
	static List<ModelCatalog.Model> parseModels(JsonNode initialize) throws IOException {
		var entries = initialize.path("models");
		if (!entries.isArray()) {
			throw new IOException("unexpected initialize response from Claude Code");
		}
		var models = new ArrayList<ModelCatalog.Model>();
		for (var entry : entries) {
			var id = CodexConnector.text(entry, "value");
			if (id == null || DEFAULT_ENTRY.equals(id)) {
				continue;
			}
			List<String> efforts = List.of();
			if (entry.path("supportsEffort").asBoolean(false)) {
				efforts = null;
				var levels = entry.get("supportedEffortLevels");
				if (levels != null && levels.isArray()) {
					efforts = new ArrayList<>();
					for (var level : levels) {
						efforts.add(level.asString());
					}
				}
			}
			var modes = EnumSet.allOf(AccessMode.class);
			if (!entry.path("supportsAutoMode").asBoolean(false)) {
				modes.remove(AccessMode.AUTO);
			}
			var label = CodexConnector.text(entry, "displayName");
			var resolved = CodexConnector.text(entry, "resolvedModel");
			if (label != null && resolved != null && !resolved.equals(id)) {
				label = label + "  ·  " + resolved;
			}
			models.add(new ModelCatalog.Model(id, label == null ? id : label, CodexConnector.text(entry, "description"),
					efforts, null, modes));
		}
		return models;
	}
}
