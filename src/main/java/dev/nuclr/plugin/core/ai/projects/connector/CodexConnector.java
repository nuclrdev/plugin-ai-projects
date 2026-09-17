package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI's Codex CLI.
 *
 * <p>Discovery runs {@code codex app-server}, which speaks JSON-RPC over stdio:
 * an {@code initialize} request and {@code initialized} notification, then
 * {@code model/list}, followed page by page. Each model reports the reasoning
 * efforts it supports and its default.
 *
 * <p>Access is two settings in Codex - a sandbox ({@code read-only},
 * {@code workspace-write}, {@code danger-full-access}) and who approves - and
 * each mode picks a pair.
 */
final class CodexConnector implements AgentConnector {

	/** Pages of models read at most, so a misbehaving server cannot loop forever. */
	private static final int MAX_PAGES = 20;

	@Override
	public AgentProvider provider() {
		return AgentProvider.CODEX;
	}

	@Override
	public List<String> efforts() {
		return List.of("low", "medium", "high", "xhigh", "max", "ultra");
	}

	@Override
	public String effortSetting() {
		return "-c model_reasoning_effort";
	}

	@Override
	public List<String> modelArguments(String model) {
		return List.of("--model", model);
	}

	@Override
	public List<String> effortArguments(String effort) {
		// Not quoted: Codex parses the value as TOML and falls back to the raw string,
		// which avoids quoting that would not survive a Windows command line.
		return List.of("-c", "model_reasoning_effort=" + effort);
	}

	@Override
	public Optional<List<String>> accessArguments(AccessMode mode) {
		return Optional.of(switch (mode) {
			case READ_ONLY -> List.of("--sandbox", "read-only");
			case ASK -> List.of("--sandbox", "workspace-write", "--ask-for-approval", "on-request");
			case AUTO -> List.of("--sandbox", "workspace-write", "--approve-for-me");
			case FULL_ACCESS -> List.of("--dangerously-bypass-approvals-and-sandbox");
			case CUSTOM -> List.<String>of();
		});
	}

	@Override
	public String unsupportedReason(AccessMode mode) {
		return "Codex does not support " + mode.label() + ".";
	}

	/**
	 * The Codex tools that can be switched on or off: live web search, which has a
	 * flag of its own, and the stable features that are tools.
	 */
	private static final List<Tool> TOOLS = List.of(
			new Tool("web_search", "Live web search; off unless allowed"),
			new Tool("shell_tool", "Runs shell commands"),
			new Tool("image_generation", "Generates images"),
			new Tool("browser_use", "Drives a browser"),
			new Tool("computer_use", "Controls the computer"),
			new Tool("multi_agent", "Delegates work to sub-agents"));

	private static final String WEB_SEARCH = "web_search";

	@Override
	public List<Tool> tools() {
		return TOOLS;
	}

	@Override
	public boolean canRestrictTools() {
		return false;
	}

	@Override
	public String allowedToolsMeaning() {
		return "These optional tools are switched on; every other tool keeps its default.";
	}

	@Override
	public String blockedToolsMeaning() {
		return "These tools are switched off.";
	}

	@Override
	public List<String> allowedToolArguments(List<String> names) {
		var arguments = new ArrayList<String>();
		for (var name : names) {
			if (WEB_SEARCH.equals(name)) {
				arguments.add("--search");
			} else {
				arguments.add("--enable");
				arguments.add(name);
			}
		}
		return arguments;
	}

	@Override
	public List<String> blockedToolArguments(List<String> names) {
		var arguments = new ArrayList<String>();
		for (var name : names) {
			// Web search is off unless --search is given, so blocking it needs no flag.
			if (!WEB_SEARCH.equals(name)) {
				arguments.add("--disable");
				arguments.add(name);
			}
		}
		return arguments;
	}

	@Override
	public List<String> toolProblems(List<String> allowed, List<String> blocked, AccessMode mode) {
		var problems = new ArrayList<String>();
		for (var name : allowed) {
			if (!isBuiltInTool(name) || name.contains("(")) {
				problems.add("Tools: Codex has no tool called \"" + name + "\" that can be switched on.");
			} else if (blocked.contains(name)) {
				problems.add("Tools: \"" + name + "\" is both allowed and blocked.");
			}
		}
		for (var name : blocked) {
			if (!isBuiltInTool(name) || name.contains("(")) {
				problems.add("Tools: Codex has no tool called \"" + name + "\" that can be switched off.");
			}
		}
		return problems;
	}

	@Override
	public SkillLoading skillLoading() {
		// Checked against Codex 0.154: skills come from the repository's .agents/skills and the user's own
		// folders; no flag or -c override adds a folder for one session.
		return SkillLoading.BRIEFING;
	}

	@Override
	public String skillsMeaning() {
		return "Codex cannot be given skill folders for one session, so they are listed in its instructions with "
				+ "their descriptions, for the agent to read when relevant. To load them as real skills, put them "
				+ "in the repository's .agents/skills.";
	}

	@Override
	public String sandboxNetworkMeaning(AccessMode mode) {
		return switch (mode) {
			case ASK, AUTO -> "Codex's sandbox blocks the network for commands unless this is on.";
			case CUSTOM -> "Codex's sandbox blocks the network for commands unless this is on, "
					+ "if the startup arguments choose the workspace-write sandbox.";
			case READ_ONLY -> "Read-only keeps commands off the network whatever this says.";
			case FULL_ACCESS -> "Full access has no sandbox, so commands already reach the network.";
		};
	}

	@Override
	public List<String> sandboxNetworkArguments(AccessMode mode) {
		return switch (mode) {
			case ASK, AUTO, CUSTOM -> List.of("-c", "sandbox_workspace_write.network_access=true");
			case READ_ONLY, FULL_ACCESS -> List.of();
		};
	}

	@Override
	public String extraFoldersMeaning(AccessMode mode) {
		return switch (mode) {
			case ASK, AUTO -> "Codex's sandbox lets agents write to these as well as the project folder.";
			case READ_ONLY -> "Read-only: nothing is writable, so these make no difference until Ask or Auto is chosen.";
			case FULL_ACCESS -> "Full access: every folder is already writable, so these make no difference.";
			case CUSTOM -> "Codex's sandbox lets agents write to these, if the startup arguments choose a sandbox that writes.";
		};
	}

	@Override
	public List<String> extraFolderArguments(List<String> folders) {
		return AgentConnector.addDirArguments(folders);
	}

	@Override
	public boolean supportsCommandRules() {
		return false;
	}

	@Override
	public String allowedCommandsMeaning() {
		// Codex's prefix rules live in rules files under its home folder; no flag or -c override takes them.
		return "Codex reads command rules only from its own rules files (~/.codex/rules), "
				+ "so they cannot be set per profile. Use Read-only or Ask access instead.";
	}

	@Override
	public String blockedCommandsMeaning() {
		return allowedCommandsMeaning();
	}

	/** A TOML bare key: the only server and variable names that need no quoting in a {@code -c} path. */
	private static final java.util.regex.Pattern BARE_KEY = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

	@Override
	public boolean supportsMcp() {
		return true;
	}

	@Override
	public boolean canRestrictMcpServers() {
		return false;
	}

	@Override
	public boolean canSwitchOffMcpServers() {
		return true;
	}

	@Override
	public String mcpMeaning() {
		return "The profile's servers are added with -c mcp_servers.<name> overrides, on top of the servers "
				+ "configured in Codex.";
	}

	@Override
	public McpSetup mcpSetup(List<dev.nuclr.plugin.core.ai.projects.model.McpServerSpec> servers,
			boolean onlyProfileServers, List<String> switchedOff, java.nio.file.Path runtimeDirectory) {
		var arguments = new ArrayList<String>();
		var bindings = new ArrayList<SecretBinding>();
		for (var server : AgentConnector.enabledServers(servers)) {
			var key = "mcp_servers." + McpSupport.text(server.getName());
			if (server.remote()) {
				override(arguments, key + ".url", literal(McpSupport.text(server.getUrl())));
				if (dev.nuclr.plugin.core.ai.projects.model.McpServerSpec.AUTH_BEARER.equals(server.authOrDefault())
						&& server.getBearerToken() != null) {
					override(arguments, key + ".bearer_token_env_var",
							literal(bind(server, "TOKEN", server.getBearerToken(), bindings)));
				}
				var headers = McpSupport.nullToEmpty(server.getHeaders());
				if (!headers.isEmpty()) {
					override(arguments, key + ".http_headers", table(headers));
				}
				var secretHeaders = new java.util.LinkedHashMap<String, String>();
				McpSupport.nullToEmpty(server.getSecretHeaders()).forEach((name, secret) ->
						secretHeaders.put(name, bind(server, "HEADER_" + name, secret, bindings)));
				if (!secretHeaders.isEmpty()) {
					override(arguments, key + ".env_http_headers", table(secretHeaders));
				}
			} else {
				override(arguments, key + ".command", literal(server.getCommand()));
				if (server.getArgs() != null && !server.getArgs().isEmpty()) {
					override(arguments, key + ".args", "[" + String.join(", ",
							server.getArgs().stream().map(CodexConnector::literal).toList()) + "]");
				}
				if (server.getEnv() != null && !server.getEnv().isEmpty()) {
					override(arguments, key + ".env", table(server.getEnv()));
				}
				// Codex passes named variables through from its own environment, so a secret
				// variable is set on the agent process under exactly the name the server expects.
				// That makes the variable global to the Codex process: two servers wanting API_KEY
				// from different secrets would overwrite one another, which the checks refuse.
				var passThrough = new ArrayList<String>();
				McpSupport.nullToEmpty(server.getSecretEnv()).forEach((name, secret) -> {
					if (secret != null) {
						bindings.add(new SecretBinding(name, secret));
					}
					passThrough.add(literal(name));
				});
				if (!passThrough.isEmpty()) {
					override(arguments, key + ".env_vars", "[" + String.join(", ", passThrough) + "]");
				}
			}
		}
		for (var name : switchedOff) {
			override(arguments, "mcp_servers." + McpSupport.text(name) + ".enabled", "false");
		}
		return new McpSetup(List.copyOf(arguments), null, null, List.copyOf(bindings));
	}

	private static void override(List<String> arguments, String path, String value) {
		arguments.add("-c");
		arguments.add(path + "=" + value);
	}

	/** A TOML inline table of literal strings. */
	private static String table(Map<String, String> values) {
		return "{ " + String.join(", ", values.entrySet().stream()
				.map(entry -> entry.getKey() + " = " + literal(entry.getValue())).toList()) + " }";
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
		if (onlyProfileServers) {
			problems.add("MCP servers: Codex always loads the servers configured in it. "
					+ "Switch off the ones agents should not use instead.");
		}
		var profileNames = new java.util.HashSet<String>();
		for (var server : AgentConnector.enabledServers(servers)) {
			var name = McpSupport.text(server.getName());
			profileNames.add(name);
			if (!BARE_KEY.matcher(name).matches()) {
				problems.add("MCP servers: \"" + name + "\" - Codex needs letters, digits, '-' and '_' only.");
			}
			if (dev.nuclr.plugin.core.ai.projects.model.McpServerSpec.SSE.equals(server.transportOrDefault())) {
				problems.add("MCP servers: \"" + name + "\" uses SSE, which Codex does not support. "
						+ "Use HTTP if the server offers it.");
			}
			problems.addAll(McpSupport.problems(server));
			var keys = new ArrayList<String>();
			keys.addAll(McpSupport.nullToEmpty(server.getEnv()).keySet());
			keys.addAll(McpSupport.nullToEmpty(server.getHeaders()).keySet());
			keys.addAll(McpSupport.nullToEmpty(server.getSecretHeaders()).keySet());
			for (var key : keys) {
				if (!BARE_KEY.matcher(key).matches()) {
					problems.add("MCP servers: \"" + name + "\" \"" + key
							+ "\" - Codex needs letters, digits, '-' and '_' only.");
				}
			}
			var values = new ArrayList<String>();
			values.add(server.getCommand() == null ? "" : server.getCommand());
			values.add(server.getUrl() == null ? "" : server.getUrl());
			if (server.getArgs() != null) {
				values.addAll(server.getArgs());
			}
			values.addAll(McpSupport.nullToEmpty(server.getEnv()).values());
			values.addAll(McpSupport.nullToEmpty(server.getHeaders()).values());
			if (values.stream().anyMatch(value -> value != null
					&& (value.contains("'") || value.contains("\n") || value.contains("\r")))) {
				problems.add("MCP servers: \"" + name + "\" - Codex cannot take a single quote or line break in a "
						+ "command, URL, argument, header or environment value.");
			}
		}
		problems.addAll(McpSupport.bindingConflicts(
				mcpSetup(servers, onlyProfileServers, List.of(), java.nio.file.Path.of("")).secrets()));
		for (var name : switchedOff) {
			if (!BARE_KEY.matcher(name.strip()).matches()) {
				problems.add("MCP servers: \"" + name.strip() + "\" is not a server name Codex can switch off.");
			} else if (profileNames.contains(name.strip())) {
				problems.add("MCP servers: \"" + name.strip() + "\" is both a profile server and switched off.");
			}
		}
		return problems;
	}

	/**
	 * Read the servers configured in Codex, from {@code codex mcp list --json}.
	 */
	@Override
	public List<String> configuredMcpServers(String executable, Duration timeout) throws IOException {
		var process = new ProcessBuilder(executable, "mcp", "list", "--json").redirectErrorStream(false).start();
		process.getOutputStream().close();
		var output = new java.io.ByteArrayOutputStream();
		var reader = Thread.ofVirtual().start(() -> {
			try (var in = process.getInputStream()) {
				in.transferTo(output);
			} catch (IOException e) {
				// What was read is what there is.
			}
		});
		Thread.ofVirtual().start(() -> {
			try (var err = process.getErrorStream()) {
				err.transferTo(java.io.OutputStream.nullOutputStream());
			} catch (IOException e) {
				// Ignored.
			}
		});
		try {
			if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				throw new IOException("codex mcp list did not answer within " + timeout.toSeconds() + " seconds");
			}
			reader.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted", e);
		}
		if (process.exitValue() != 0) {
			throw new IOException("codex mcp list exited with " + process.exitValue());
		}
		return parseConfiguredServers(output.toString(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Read the names from {@code codex mcp list --json}.
	 *
	 * @param json the command's output
	 * @return the server names, in order
	 * @throws IOException when it is not a list of servers
	 */
	static List<String> parseConfiguredServers(String json) throws IOException {
		var root = dev.nuclr.plugin.core.ai.projects.store.Json.fromJson(json, JsonNode.class);
		if (root == null || !root.isArray()) {
			throw new IOException("unexpected output from codex mcp list");
		}
		var names = new ArrayList<String>();
		for (var server : root) {
			var name = text(server, "name");
			if (name != null && !name.isBlank()) {
				names.add(name);
			}
		}
		return names;
	}

	/** A TOML literal string: no escapes, so Windows paths pass through unchanged. */
	private static String literal(String value) {
		return "'" + (value == null ? "" : value) + "'";
	}

	@Override
	public ModelCatalog discover(String executable, Duration timeout) throws IOException {
		var deadline = System.nanoTime() + timeout.toNanos();
		try (var process = JsonLineProcess.start(List.of(executable, "app-server"))) {

			process.send(request(1, "initialize", Map.of("clientInfo",
					Map.of("name", "nuclr-commander", "title", "Nuclr Commander", "version", "1"))));
			failOnError(process.await(message -> message.path("id").asInt(-1) == 1, remaining(deadline),
					"initialize response"), "initialize");
			process.send(Map.of("jsonrpc", "2.0", "method", "initialized"));

			var models = new ArrayList<ModelCatalog.Model>();
			String cursor = null;
			for (var page = 0; page < MAX_PAGES; page++) {
				var id = 2 + page;
				var params = new LinkedHashMap<String, Object>();
				if (cursor != null) {
					params.put("cursor", cursor);
				}
				process.send(request(id, "model/list", params));
				var response = failOnError(process.await(message -> message.path("id").asInt(-1) == id,
						remaining(deadline), "model/list response"), "model/list");
				models.addAll(parseModels(response.path("result")));
				var next = response.path("result").path("nextCursor");
				cursor = next.isString() && !next.asString().isBlank() ? next.asString() : null;
				if (cursor == null) {
					break;
				}
			}
			if (models.isEmpty()) {
				throw new IOException("Codex reported no models");
			}
			return new ModelCatalog(provider(), models, true,
					"Models reported by Codex (app-server model/list). " + models.size() + " available.");
		}
	}

	@Override
	public ModelCatalog builtIn(String reason) {
		return new ModelCatalog(provider(), List.of(), false, reason);
	}

	/**
	 * Read one page of {@code model/list}. Hidden models are internal and left out.
	 *
	 * @param result the response's {@code result}
	 * @return the models on the page
	 * @throws IOException when it is not that shape
	 */
	static List<ModelCatalog.Model> parseModels(JsonNode result) throws IOException {
		var data = result.path("data");
		if (!data.isArray()) {
			throw new IOException("unexpected model/list result from Codex");
		}
		var models = new ArrayList<ModelCatalog.Model>();
		for (var entry : data) {
			if (entry.path("hidden").asBoolean(false)) {
				continue;
			}
			var id = text(entry, "model");
			if (id == null) {
				id = text(entry, "id");
			}
			if (id == null) {
				continue;
			}
			List<String> efforts = null;
			var supported = entry.get("supportedReasoningEfforts");
			if (supported != null && supported.isArray()) {
				efforts = new ArrayList<>();
				for (var option : supported) {
					var effort = option.isString() ? option.asString() : text(option, "reasoningEffort");
					if (effort != null && !effort.isBlank()) {
						efforts.add(effort);
					}
				}
			}
			var label = text(entry, "displayName");
			var description = text(entry, "description");
			if (entry.path("isDefault").asBoolean(false)) {
				description = description == null ? "Codex's default model." : description + " Codex's default model.";
			}
			models.add(new ModelCatalog.Model(id, label == null ? id : label, description, efforts,
					text(entry, "defaultReasoningEffort"), null));
		}
		return models;
	}

	private static Map<String, Object> request(int id, String method, Object params) {
		var message = new LinkedHashMap<String, Object>();
		message.put("jsonrpc", "2.0");
		message.put("id", id);
		message.put("method", method);
		message.put("params", params);
		return message;
	}

	private static JsonNode failOnError(JsonNode response, String method) throws IOException {
		var error = response.get("error");
		if (error != null && !error.isNull()) {
			throw new IOException(method + " failed: " + error.path("message").asString("unknown error"));
		}
		return response;
	}

	static Duration remaining(long deadline) {
		return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
	}

	static String text(JsonNode node, String field) {
		var value = node == null ? null : node.get(field);
		return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
	}
}
