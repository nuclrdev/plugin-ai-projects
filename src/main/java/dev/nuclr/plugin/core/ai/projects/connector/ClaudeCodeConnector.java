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
