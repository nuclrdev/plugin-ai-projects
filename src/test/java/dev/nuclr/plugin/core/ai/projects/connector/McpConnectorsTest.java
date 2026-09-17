package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * A profile's MCP servers as each connector passes them. The Codex arguments
 * below were checked against a real {@code codex mcp list --json}, including
 * Windows paths with spaces.
 */
class McpConnectorsTest {

	private static final AgentConnector CLAUDE = AgentConnectors.of(AgentProvider.CLAUDE_CODE);
	private static final AgentConnector CODEX = AgentConnectors.of(AgentProvider.CODEX);
	private static final AgentConnector PI = AgentConnectors.of(AgentProvider.PI);

	private static McpServerSpec server() {
		var server = McpServerSpec.of("files", "C:\\Program Files\\Tools\\files.exe", List.of("--root", "C:\\work dir"));
		var env = new LinkedHashMap<String, String>();
		env.put("TOKEN", "abc def");
		server.setEnv(env);
		return server;
	}

	private static McpServerSpec disabled() {
		var server = McpServerSpec.of("old", "old-server", List.of());
		server.setEnabled(false);
		return server;
	}

	@Test
	void claudeGetsAConfigFileAndCanBeLimitedToIt() throws IOException {
		var setup = CLAUDE.mcpSetup(List.of(server(), disabled()), true, List.of(), Path.of("run"));

		assertEquals(List.of("--mcp-config", Path.of("run", "nuclr-mcp.json").toString(), "--strict-mcp-config"),
				setup.arguments());
		assertEquals("nuclr-mcp.json", setup.configFileName());
		var config = Json.fromJson(setup.configFileContent(), JsonNode.class).path("mcpServers");
		assertEquals("C:\\Program Files\\Tools\\files.exe", config.path("files").path("command").asString());
		assertEquals("C:\\work dir", config.path("files").path("args").get(1).asString());
		assertEquals("abc def", config.path("files").path("env").path("TOKEN").asString());
		assertFalse(config.has("old"), "disabled servers are left out");

		var nothing = CLAUDE.mcpSetup(List.of(), true, List.of(), Path.of("run"));
		assertEquals(List.of("--mcp-config", Path.of("run", "nuclr-mcp.json").toString(), "--strict-mcp-config"),
				nothing.arguments(), "no MCP servers at all - still with a file, empty, for strict mode");
		var none = Json.fromJson(nothing.configFileContent(), JsonNode.class).path("mcpServers");
		assertTrue(none.isObject() && none.isEmpty(), nothing.configFileContent());
	}

	@Test
	void claudeCannotSwitchOffConfiguredServers() {
		assertFalse(CLAUDE.mcpProblems(List.of(), false, List.of("github")).isEmpty());
		var spaced = McpServerSpec.of("my files", "x", List.of());
		assertFalse(CLAUDE.mcpProblems(List.of(spaced), false, List.of()).isEmpty());
		assertTrue(CLAUDE.mcpProblems(List.of(server()), true, List.of()).isEmpty());
	}

	@Test
	void codexGetsLiteralStringOverridesAndSwitchesConfiguredServersOff() {
		var setup = CODEX.mcpSetup(List.of(server(), disabled()), false, List.of("node_repl"), Path.of("run"));

		assertEquals(List.of(
				"-c", "mcp_servers.files.command='C:\\Program Files\\Tools\\files.exe'",
				"-c", "mcp_servers.files.args=['--root', 'C:\\work dir']",
				"-c", "mcp_servers.files.env={ TOKEN = 'abc def' }",
				"-c", "mcp_servers.node_repl.enabled=false"), setup.arguments());
		assertNull(setup.configFileName());
	}

	@Test
	void codexRefusesWhatItCannotTake() {
		assertFalse(CODEX.mcpProblems(List.of(), true, List.of()).isEmpty(), "cannot ignore configured servers");
		var quoted = McpServerSpec.of("files", "server", List.of("it's"));
		assertFalse(CODEX.mcpProblems(List.of(quoted), false, List.of()).isEmpty(), "no quote in a literal string");
		assertFalse(CODEX.mcpProblems(List.of(server()), false, List.of("files")).isEmpty(), "both added and off");
		assertTrue(CODEX.mcpProblems(List.of(server()), false, List.of("node_repl")).isEmpty());
	}

	@Test
	void codexConfiguredServersAreReadFromItsJson() throws IOException {
		var json = """
				[{"name": "node_repl", "enabled": true, "transport": {"type": "stdio", "command": "node_repl.exe"}},
				 {"name": "github", "enabled": false, "transport": {"type": "streamable_http", "url": "https://x"}}]""";
		assertEquals(List.of("node_repl", "github"), CodexConnector.parseConfiguredServers(json));
		assertThrows(IOException.class, () -> CodexConnector.parseConfiguredServers("{}"));
	}

	@Test
	void piHasNoMcpAndSaysSo() {
		assertFalse(PI.supportsMcp());
		assertEquals(List.of(), PI.mcpSetup(List.of(server()), false, List.of(), Path.of("run")).arguments());
		assertFalse(PI.mcpProblems(List.of(server()), false, List.of()).isEmpty());
		assertTrue(PI.mcpProblems(List.of(disabled()), false, List.of()).isEmpty(), "a switched-off definition is harmless");
	}

	@Test
	void theProfileValidatorAsksTheProvidersConnector() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().getSwitchedOffMcpServers().add("node_repl");
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("choose a provider"));

		profile.getHarness().setProvider("codex");
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());

		profile.getHarness().setProvider("claude-code");
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());

		profile.getHarness().setSwitchedOffMcpServers(List.of());
		profile.getHarness().setMcpAccess(Profile.Harness.TOOL_ACCESS_ONLY);
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
		assertTrue(profile.getHarness().restrictsMcpServers());
	}
}
