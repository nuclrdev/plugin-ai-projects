package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Remote MCP servers and their secrets: kept in the credential store, referred to
 * by key, and handed to agents only through environment variables. The
 * {@code ${VARIABLE}} expansion and Codex's {@code bearer_token_env_var},
 * {@code env_http_headers} and {@code env_vars} were each checked against the real
 * CLIs.
 */
class McpSecretsTest {

	@TempDir
	Path home;

	/** A credential store in memory, as the OS keychain would behave. */
	static final class MemoryStore implements NuclrCredentialStore {

		final Map<String, String> entries = new LinkedHashMap<>();
		boolean unavailable;

		@Override
		public Optional<String> get(String key) throws NuclrCredentialException {
			check();
			return Optional.ofNullable(entries.get(key));
		}

		@Override
		public void set(String key, String secret) throws NuclrCredentialException {
			check();
			entries.put(key, secret);
		}

		@Override
		public void delete(String key) throws NuclrCredentialException {
			check();
			entries.remove(key);
		}

		private void check() throws NuclrCredentialException {
			if (unavailable) {
				throw new NuclrCredentialException(NuclrCredentialException.Reason.UNAVAILABLE, "no keyring");
			}
		}
	}

	private static McpServerSpec github(McpSecret token) {
		var server = McpServerSpec.remote("github", McpServerSpec.HTTP, "https://api.githubcopilot.com/mcp/");
		server.setAuth(McpServerSpec.AUTH_BEARER);
		server.setBearerToken(token);
		var headers = new LinkedHashMap<String, String>();
		headers.put("X-Team", "nuclr");
		server.setHeaders(headers);
		var secretHeaders = new LinkedHashMap<String, McpSecret>();
		secretHeaders.put("X-Api-Key", McpSecret.environment("EXAMPLE_KEY"));
		server.setSecretHeaders(secretHeaders);
		return server;
	}

	private static Profile profileWith(McpServerSpec... servers) {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setMcpServers(new java.util.ArrayList<>(List.of(servers)));
		return profile;
	}

	// ------------------------------------------------------------------ connectors

	@Test
	void claudeWritesOnlyVariableNamesIntoItsConfigAndBindsTheStoredToken() throws IOException {
		var setup = AgentConnectors.of(AgentProvider.CLAUDE_CODE)
				.mcpSetup(List.of(github(McpSecret.stored("profile-secret/abc"))), false, List.of(), Path.of("run"));

		var server = Json.fromJson(setup.configFileContent(), JsonNode.class).path("mcpServers").path("github");
		assertEquals("http", server.path("type").asString());
		assertEquals("https://api.githubcopilot.com/mcp/", server.path("url").asString());
		assertEquals("Bearer ${NUCLR_MCP_GITHUB_TOKEN}", server.path("headers").path("Authorization").asString());
		assertEquals("nuclr", server.path("headers").path("X-Team").asString());
		assertEquals("${EXAMPLE_KEY}", server.path("headers").path("X-Api-Key").asString(),
				"a secret from the environment is read from its own variable");
		assertEquals(1, setup.secrets().size(), "only the stored secret needs handing over");
		assertEquals("NUCLR_MCP_GITHUB_TOKEN", setup.secrets().getFirst().variable());
		assertEquals("profile-secret/abc", setup.secrets().getFirst().secret().getKey());
	}

	@Test
	void codexNamesTheVariablesAndNeverTheSecrets() {
		var setup = AgentConnectors.of(AgentProvider.CODEX)
				.mcpSetup(List.of(github(McpSecret.stored("profile-secret/abc"))), false, List.of(), Path.of("run"));

		assertEquals(List.of(
				"-c", "mcp_servers.github.url='https://api.githubcopilot.com/mcp/'",
				"-c", "mcp_servers.github.bearer_token_env_var='NUCLR_MCP_GITHUB_TOKEN'",
				"-c", "mcp_servers.github.http_headers={ X-Team = 'nuclr' }",
				"-c", "mcp_servers.github.env_http_headers={ X-Api-Key = 'EXAMPLE_KEY' }"), setup.arguments());
		assertEquals("NUCLR_MCP_GITHUB_TOKEN", setup.secrets().getFirst().variable());
	}

	@Test
	void aLocalServersSecretVariablesTravelInTheEnvironment() {
		var local = McpServerSpec.of("gh", "npx", List.of("-y", "github-mcp"));
		var secretEnv = new LinkedHashMap<String, McpSecret>();
		secretEnv.put("GITHUB_TOKEN", McpSecret.stored("profile-secret/k"));
		local.setSecretEnv(secretEnv);

		var codex = AgentConnectors.of(AgentProvider.CODEX).mcpSetup(List.of(local), false, List.of(), Path.of("run"));
		assertTrue(codex.arguments().contains("mcp_servers.gh.env_vars=['GITHUB_TOKEN']"), codex.arguments().toString());
		assertEquals("GITHUB_TOKEN", codex.secrets().getFirst().variable(), "Codex passes it through by its own name");

		var claude = AgentConnectors.of(AgentProvider.CLAUDE_CODE).mcpSetup(List.of(local), false, List.of(), Path.of("run"));
		assertTrue(claude.configFileContent().contains("\"GITHUB_TOKEN\" : \"${NUCLR_MCP_GH_ENV_GITHUB_TOKEN}\""),
				claude.configFileContent());
	}

	@Test
	void remoteServersAreCheckedWhereverTheyAreUsed() {
		var codex = AgentConnectors.of(AgentProvider.CODEX);
		var sse = McpServerSpec.remote("events", McpServerSpec.SSE, "https://x.example/sse");
		assertFalse(codex.mcpProblems(List.of(sse), false, List.of()).isEmpty(), "Codex has no SSE");
		assertTrue(AgentConnectors.of(AgentProvider.CLAUDE_CODE).mcpProblems(List.of(sse), false, List.of()).isEmpty());

		var noUrl = McpServerSpec.remote("x", McpServerSpec.HTTP, "mcp.example.com");
		assertFalse(McpSupport.problems(noUrl).isEmpty());

		var leaky = McpServerSpec.remote("x", McpServerSpec.HTTP, "https://x.example/mcp");
		leaky.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "Bearer ghp_real_token")));
		assertFalse(McpSupport.problems(leaky).isEmpty(), "a token typed as a plain header is refused");

		var toEnter = github(McpSecret.stored(null));
		assertTrue(McpSupport.problems(toEnter).isEmpty(), "a secret still to enter does not stop a save");
		assertTrue(toEnter.getBearerToken().needsEntry());
	}

	@Test
	void anExistingLocalServerIsWrittenExactlyAsBefore() throws IOException {
		var json = Json.toJson(McpServerSpec.of("files", "mcp-files", List.of("/srv")));
		var fields = Json.fromJson(json, JsonNode.class).propertyNames();
		assertEquals(java.util.Set.of("name", "command", "args", "env", "enabled"), java.util.Set.copyOf(fields),
				"no new field - not even a computed one - may appear in files written before remote servers existed");
	}

	// ------------------------------------------------------------------ credential store

	@Test
	void entered_secrets_reach_the_store_only_when_the_profile_is_saved() throws Exception {
		var store = new MemoryStore();
		var original = profileWith(github(McpSecret.stored(null)));
		var session = new SecretSession(new ProfileSecrets(store), original);

		var kept = session.stage("ghp_kept");
		var abandoned = session.stage("ghp_replaced_before_saving");
		assertTrue(store.entries.isEmpty(), "nothing is written while editing");

		var edited = profileWith(github(McpSecret.stored(kept)));
		var written = session.writeStaged(edited);

		assertEquals(List.of(kept), written);
		assertEquals("ghp_kept", store.entries.get(kept));
		assertFalse(store.entries.containsKey(abandoned), "a secret the profile no longer uses is never written");

		session.rollback(written);
		assertTrue(store.entries.isEmpty());
	}

	@Test
	void savingDeletesTheSecretsTheProfileStoppedUsing() throws Exception {
		var store = new MemoryStore();
		store.entries.put("profile-secret/old", "ghp_old");
		var original = profileWith(github(McpSecret.stored("profile-secret/old")));
		var session = new SecretSession(new ProfileSecrets(store), original);

		var edited = profileWith(github(McpSecret.environment("GITHUB_TOKEN")));
		session.writeStaged(edited);
		session.finish(edited);

		assertTrue(store.entries.isEmpty());
	}

	@Test
	void aDuplicateGetsSecretsOfItsOwnAndDeletingOneLeavesTheOther() throws Exception {
		var store = new MemoryStore();
		store.entries.put("profile-secret/one", "ghp_one");
		var secrets = new ProfileSecrets(store);
		var original = profileWith(github(McpSecret.stored("profile-secret/one")));

		var copy = original.copy();
		secrets.copyInto(copy);
		var copyKey = copy.getHarness().getMcpServers().getFirst().getBearerToken().getKey();

		assertNotEquals("profile-secret/one", copyKey);
		assertEquals("ghp_one", store.entries.get(copyKey));
		secrets.deleteAll(original);
		assertEquals("ghp_one", store.entries.get(copyKey));
		assertFalse(store.entries.containsKey("profile-secret/one"));
	}

	@Test
	void exportAndImportLeaveTheKeysBehind() throws Exception {
		var profiles = ProfileStore.inCommanderHome(home);
		var original = profiles.create(profileWith(github(McpSecret.stored("profile-secret/mine"))));
		var file = home.resolve("shared.profile.json");

		profiles.exportTo(original, file);
		assertFalse(java.nio.file.Files.readString(file).contains("profile-secret/mine"));
		assertEquals("profile-secret/mine",
				original.getHarness().getMcpServers().getFirst().getBearerToken().getKey(), "the original is untouched");

		var imported = profiles.importFrom(file);
		var token = imported.getHarness().getMcpServers().getFirst().getBearerToken();
		assertNull(token.getKey());
		assertTrue(token.needsEntry());
	}

	@Test
	void aStoreThatIsNotThereSaysSo() {
		var store = new MemoryStore();
		store.unavailable = true;
		var original = profileWith();
		var session = new SecretSession(new ProfileSecrets(store), original);
		var key = session.stage("x");

		var failure = assertThrows(NuclrCredentialException.class,
				() -> session.writeStaged(profileWith(github(McpSecret.stored(key)))));
		assertEquals(NuclrCredentialException.Reason.UNAVAILABLE, failure.getReason());
		assertFalse(new SecretSession(new ProfileSecrets(null), original).available());
	}
}
