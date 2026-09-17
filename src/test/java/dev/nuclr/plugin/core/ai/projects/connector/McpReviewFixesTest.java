package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/** Regressions for the issues found in review of remote MCP servers and their secrets. */
class McpReviewFixesTest {

	private static final AgentConnector CLAUDE = AgentConnectors.of(AgentProvider.CLAUDE_CODE);
	private static final AgentConnector CODEX = AgentConnectors.of(AgentProvider.CODEX);

	private static McpServerSpec localWithSecret(String name, String variable, McpSecret secret) {
		var server = McpServerSpec.of(name, "npx", List.of("-y", name + "-mcp"));
		var secretEnv = new LinkedHashMap<String, McpSecret>();
		secretEnv.put(variable, secret);
		server.setSecretEnv(secretEnv);
		return server;
	}

	// 1 - strict mode always comes with a config file

	@Test
	void onlyTheProfilesServersPassesAnEmptyConfigWhenThereAreNone() throws IOException {
		var disabled = McpServerSpec.remote("off", McpServerSpec.HTTP, "https://x.example/mcp");
		disabled.setEnabled(false);

		var setup = CLAUDE.mcpSetup(List.of(disabled), true, List.of(), Path.of("run"));

		assertEquals(List.of("--mcp-config", Path.of("run", "nuclr-mcp.json").toString(), "--strict-mcp-config"),
				setup.arguments());
		var servers = Json.fromJson(setup.configFileContent(), JsonNode.class).path("mcpServers");
		assertTrue(servers.isObject() && servers.isEmpty(), setup.configFileContent());
		assertEquals("nuclr-mcp.json", setup.configFileName());

		assertTrue(CLAUDE.mcpSetup(List.of(), false, List.of(), Path.of("run")).arguments().isEmpty(),
				"without strict mode, no servers means nothing to pass");
	}

	// 3 - two secrets in one environment variable

	@Test
	void codexRefusesTwoServersForwardingTheSameVariableFromDifferentSecrets() {
		var first = localWithSecret("one", "API_KEY", McpSecret.stored("profile-secret/one"));
		var second = localWithSecret("two", "API_KEY", McpSecret.stored("profile-secret/two"));
		var problems = CODEX.mcpProblems(List.of(first, second), false, List.of());
		assertTrue(problems.stream().anyMatch(problem -> problem.contains("API_KEY")), problems.toString());

		var shared = localWithSecret("two", "API_KEY", McpSecret.stored("profile-secret/one"));
		assertTrue(CODEX.mcpProblems(List.of(first, shared), false, List.of()).isEmpty(),
				"the same secret twice is fine");

		var fromEnvironment = localWithSecret("two", "API_KEY", McpSecret.environment("OTHER_KEY"));
		assertFalse(CODEX.mcpProblems(List.of(first, fromEnvironment), false, List.of()).isEmpty());
	}

	@Test
	void serverNamesThatOnlyDifferInPunctuationCannotShareAVariable() {
		var dashed = McpServerSpec.remote("my-server", McpServerSpec.HTTP, "https://a.example/mcp");
		dashed.setAuth(McpServerSpec.AUTH_BEARER);
		dashed.setBearerToken(McpSecret.stored("profile-secret/a"));
		var underscored = McpServerSpec.remote("my_server", McpServerSpec.HTTP, "https://b.example/mcp");
		underscored.setAuth(McpServerSpec.AUTH_BEARER);
		underscored.setBearerToken(McpSecret.stored("profile-secret/b"));

		for (var connector : List.of(CLAUDE, CODEX)) {
			var problems = connector.mcpProblems(List.of(dashed, underscored), false, List.of());
			assertTrue(problems.stream().anyMatch(problem -> problem.contains("NUCLR_MCP_MY_SERVER_TOKEN")),
					connector.provider() + ": " + problems);
		}
	}

	// 4 - previews and checks survive servers that do not validate

	@Test
	void setupAndChecksTolerateIncompleteServers() {
		var noUrl = McpServerSpec.remote("broken", McpServerSpec.HTTP, null);
		noUrl.setAuth(McpServerSpec.AUTH_BEARER);
		noUrl.setBearerToken(McpSecret.environment(null));
		var noCommand = McpServerSpec.of("local", null, null);
		var servers = List.of(noUrl, noCommand);

		for (var connector : List.of(CLAUDE, CODEX)) {
			assertDoesNotThrow(() -> connector.mcpSetup(servers, true, List.of(), Path.of("run")),
					connector.provider().displayName());
			var problems = assertDoesNotThrow(() -> connector.mcpProblems(servers, false, List.of()));
			assertFalse(problems.isEmpty(), connector.provider() + " still reports them");
		}
	}

	@Test
	void theProfileEditorOpensAProfileWithAnIncompleteServer() throws Exception {
		var profile = new Profile();
		profile.setName("Imported");
		profile.getHarness().setProvider("claude-code");
		profile.getHarness().getMcpServers().add(McpServerSpec.remote("broken", McpServerSpec.HTTP, null));

		SwingUtilities.invokeAndWait(() -> assertDoesNotThrow(() -> new dev.nuclr.plugin.core.ai.projects.ui.profile
				.ProfileForm(profile, dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs.answering(
						(provider, executable) -> provider.connector().builtIn("test"))).toProfile()));
	}
}
