package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * The three connectors, read against responses recorded from the real CLIs
 * (Codex 0.154, Pi 0.85, Claude Code 2.1), trimmed of account details.
 */
class AgentConnectorsTest {

	static JsonNode fixture(String name) throws IOException {
		try (var in = AgentConnectorsTest.class.getResourceAsStream("/connectors/" + name)) {
			return Json.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
		}
	}

	private static List<String> ids(List<ModelCatalog.Model> models) {
		return models.stream().map(ModelCatalog.Model::id).toList();
	}

	// ------------------------------------------------------------------ Codex

	@Test
	void codexModelsCarryTheirOwnEffortsAndDefault() throws IOException {
		var models = CodexConnector.parseModels(fixture("codex-model-list.json").path("result"));

		assertEquals(List.of("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"), ids(models));
		var astra = models.getFirst();
		assertEquals("GPT-6-Astra", astra.label());
		assertEquals(List.of("low", "medium", "high", "xhigh", "max", "ultra"), astra.efforts());
		assertEquals("low", astra.defaultEffort());
		assertTrue(astra.description().endsWith("Codex's default model."), astra.description());
		assertEquals(List.of("low", "medium", "high", "xhigh"), models.getLast().efforts());
		assertThrows(IOException.class, () -> CodexConnector.parseModels(Json.fromJson("{}", JsonNode.class)));
	}

	@Test
	void codexFlags() {
		var codex = AgentConnectors.of(AgentProvider.CODEX);
		assertEquals(List.of("--model", "gpt-5.5", "-c", "model_reasoning_effort=high",
				"--sandbox", "workspace-write", "--ask-for-approval", "on-request"),
				codex.launchArguments("gpt-5.5", "high", AccessMode.ASK));
		assertEquals(List.of("--dangerously-bypass-approvals-and-sandbox"),
				codex.launchArguments(" ", null, AccessMode.FULL_ACCESS));
	}

	// ------------------------------------------------------------------ Pi

	@Test
	void piModelsAreProviderQualifiedWithThinkingLevelsFromTheirMap() throws IOException {
		var models = PiConnector.parseModels(fixture("pi-available-models.json").path("data"));
		var byId = models.stream().collect(java.util.stream.Collectors.toMap(ModelCatalog.Model::id, model -> model));

		// Thinking cannot be switched off, and the extended levels are named.
		assertEquals(List.of("minimal", "low", "medium", "high", "xhigh", "max"),
				byId.get("anthropic/claude-fable-5-1").efforts());
		// No map: the standard levels only.
		assertEquals(List.of("off", "minimal", "low", "medium", "high"),
				byId.get("anthropic/claude-haiku-4-5").efforts());
		// Cannot reason at all.
		assertTrue(models.stream().anyMatch(model -> model.efforts().equals(List.of("off"))));
		assertTrue(byId.get("anthropic/claude-fable-5-1").label().contains("anthropic"));
	}

	@Test
	void piThinkingLevelsFollowTheDocumentedTristate() throws IOException {
		var holes = Json.fromJson("""
				{"reasoning": true, "thinkingLevelMap": {"minimal": null, "low": null, "medium": null,
				 "high": "high", "xhigh": null, "max": "max"}}""", JsonNode.class);
		assertEquals(List.of("off", "high", "max"), PiConnector.thinkingLevels(holes));
	}

	@Test
	void piCanOnlyBeReadOnlyOrFullyTrusted() {
		var pi = AgentConnectors.of(AgentProvider.PI);
		assertEquals(List.of("--model", "anthropic/claude-opus-5", "--thinking", "high", "--tools", "read,grep,find,ls"),
				pi.launchArguments("anthropic/claude-opus-5", "high", AccessMode.READ_ONLY));
		assertFalse(pi.supports(AccessMode.ASK));
		assertEquals(AccessMode.FULL_ACCESS, pi.defaultAccessMode());
		assertThrows(IllegalArgumentException.class, () -> pi.launchArguments(null, null, AccessMode.AUTO));
	}

	// ------------------------------------------------------------------ Claude Code

	@Test
	void claudeModelsComeFromTheAccountWithEffortAndAutoModeSupport() throws IOException {
		var models = ClaudeCodeConnector.parseModels(fixture("claude-initialize.json").path("response").path("response"));

		assertFalse(ids(models).contains("default"), "blank already means the default model");
		var opus = models.stream().filter(model -> model.id().equals("opus")).findFirst().orElseThrow();
		assertEquals(List.of("low", "medium", "high", "xhigh", "max"), opus.efforts());
		assertTrue(opus.accessModes().contains(AccessMode.AUTO));
		assertTrue(opus.label().contains("claude-opus-5"), opus.label());

		var haiku = models.stream().filter(model -> model.id().equals("haiku")).findFirst().orElseThrow();
		assertEquals(List.of(), haiku.efforts(), "Haiku has no effort setting");
		assertFalse(haiku.accessModes().contains(AccessMode.AUTO), "nor auto mode");

		var catalog = new ModelCatalog(AgentProvider.CLAUDE_CODE, models, true, "");
		assertFalse(catalog.supports("haiku", AccessMode.AUTO));
		assertTrue(catalog.supports("opus", AccessMode.AUTO));
		assertTrue(catalog.supports("typed-by-hand", AccessMode.AUTO));
		assertEquals(List.of(), catalog.effortsFor("haiku"));
		assertEquals(AgentProvider.CLAUDE_CODE.efforts(), catalog.effortsFor("typed-by-hand"));
	}

	@Test
	void claudeFlags() {
		var claude = AgentConnectors.of(AgentProvider.CLAUDE_CODE);
		assertEquals(List.of("--model", "opus", "--effort", "xhigh", "--permission-mode", "auto"),
				claude.launchArguments("opus", "xhigh", AccessMode.AUTO));
		assertEquals(List.of("--permission-mode", "manual"), claude.launchArguments(null, null, null));
	}

	@Test
	void builtInListsAreMarkedAsSuch() {
		var claude = AgentConnectors.of(AgentProvider.CLAUDE_CODE).builtIn("offline");
		assertFalse(claude.live());
		assertEquals("offline", claude.note());
		assertNull(claude.models().getFirst().efforts(), "unknown, so the provider's efforts apply");
		assertTrue(AgentConnectors.of(AgentProvider.CODEX).builtIn("x").models().isEmpty());
	}
}
