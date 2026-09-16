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
