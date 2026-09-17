package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;

/** Starting an agent from a profile: what the command, environment, files and briefing are. */
class LaunchPlanTest {

	@TempDir
	Path home;

	private static final class MemoryStore implements NuclrCredentialStore {

		final Map<String, String> entries = new HashMap<>();

		@Override
		public Optional<String> get(String key) {
			return Optional.ofNullable(entries.get(key));
		}

		@Override
		public void set(String key, String secret) {
			entries.put(key, secret);
		}

		@Override
		public void delete(String key) {
			entries.remove(key);
		}
	}

	private Profile claude() {
		var profile = new Profile();
		profile.setName("Team Claude");
		var harness = profile.getHarness();
		harness.setProvider("claude-code");
		harness.setModel("opus");
		harness.setEffort("high");
		harness.setAccessMode("ask");
		harness.setStartupArgs(List.of("--verbose"));
		harness.getBlockedTools().add("WebFetch");
		harness.getBlockedCommands().add("git push");
		harness.getExtraFolders().add(ProfileRecord.file(null, "~/.m2"));
		var github = McpServerSpec.remote("github", McpServerSpec.HTTP, "https://api.example.com/mcp");
		github.setAuth(McpServerSpec.AUTH_BEARER);
		github.setBearerToken(McpSecret.stored("profile-secret/github"));
		harness.getMcpServers().add(github);
		harness.getEnvironment().add(ProfileRecord.text("MAVEN_OPTS", "-Xmx2g"));
		return profile;
	}

	private String underHome(String rest) {
		return home + File.separator + rest;
	}

	@Test
	void aClaudeProfileBecomesOneCommandLine() {
		var runtime = home.resolve("runtime");
		var plan = LaunchPlan.of(claude(), runtime, home.toString(), home);

		assertEquals("claude", plan.executable());
		assertEquals(List.of("--verbose",
				"--model", "opus", "--effort", "high", "--permission-mode", "manual",
				"--disallowedTools", "WebFetch,Bash(git push),Bash(git push *),PowerShell(git push),PowerShell(git push *)",
				"--add-dir", underHome(".m2"),
				"--mcp-config", runtime.resolve("nuclr-mcp.json").toString()), plan.arguments());
		assertEquals(runtime.resolve("nuclr-mcp.json"), plan.configFile());
		assertTrue(plan.configFileContent().contains("${NUCLR_MCP_GITHUB_TOKEN}"), plan.configFileContent());
		assertEquals(List.of("NUCLR_MCP_GITHUB_TOKEN"),
				plan.secrets().stream().map(AgentConnector.SecretBinding::variable).toList());
		assertEquals(Map.of("MAVEN_OPTS", "-Xmx2g"), plan.environment());
		assertEquals("", plan.briefing());
		assertTrue(plan.notices().isEmpty());
	}

	@Test
	void aProfileThatDoesNotValidateOrNamesNoProviderIsRefused() {
		var noProvider = new Profile();
		noProvider.setName("Empty");
		var refused = assertThrows(IllegalArgumentException.class,
				() -> LaunchPlan.of(noProvider, home, home.toString(), home));
		assertTrue(refused.getMessage().contains("no provider"), refused.getMessage());

		var codex = new Profile();
		codex.setName("Codex");
		codex.getHarness().setProvider("codex");
		codex.getHarness().getBlockedCommands().add("rm -rf");
		refused = assertThrows(IllegalArgumentException.class, () -> LaunchPlan.of(codex, home, home.toString(), home));
		assertTrue(refused.getMessage().startsWith("Profile \"Codex\" cannot be used yet: Commands"), refused.getMessage());
	}

	@Test
	void codexGetsItsSandboxNetworkAndPiItsSkills() {
		var codex = new Profile();
		codex.setName("Codex");
		codex.getHarness().setProvider("codex");
		codex.getHarness().setAccessMode("auto");
		codex.getHarness().setSandboxNetworkAccess(true);
		var arguments = LaunchPlan.of(codex, home, home.toString(), home).arguments();
		assertEquals(List.of("--sandbox", "workspace-write", "--approve-for-me",
				"-c", "sandbox_workspace_write.network_access=true"), arguments);

		var pi = new Profile();
		pi.setName("Pi");
		pi.getHarness().setProvider("pi");
		pi.getContext().getSkills().add(ProfileRecord.file("Review", "~/skills/review"));
		var plan = LaunchPlan.of(pi, home, home.toString(), home);
		assertEquals(List.of("--skill", underHome("skills/review".replace('/', File.separatorChar))),
				plan.arguments());
		assertEquals("", plan.briefing(), "a skill Pi loads itself is not repeated in the briefing");
	}

	@Test
	void theContextBecomesABriefingAndWhatCannotBeReadIsSaid() throws IOException {
		Files.writeString(home.resolve("style.md"), "Use British English.");
		var profile = claude();
		var context = profile.getContext();
		context.getInstructions().add(ProfileRecord.text("Rules", "No force pushes."));
		context.getInstructions().add(ProfileRecord.file("Style", "~/style.md"));
		context.getInstructions().add(ProfileRecord.file("Gone", "~/gone.md"));
		context.getInstructions().add(ProfileRecord.git("Shared", "https://git.example.com/rules.git", "main", "RULES.md"));
		var off = ProfileRecord.text("Off", "Not this.");
		off.setEnabled(false);
		context.getInstructions().add(off);
		context.getSkills().add(ProfileRecord.file("Review", home.resolve("skills").toString()));
		context.getKnowledge().add(ProfileRecord.git("Docs", "https://git.example.com/docs.git", "v2", "api"));

		var plan = LaunchPlan.of(profile, home, home.toString(), home);
		var briefing = plan.briefing();

		assertTrue(briefing.startsWith("# Profile: Team Claude"), briefing);
		assertTrue(briefing.contains("### Rules\n\nNo force pushes."), briefing);
		assertTrue(briefing.contains("### Style\n\nUse British English."), briefing);
		assertFalse(briefing.contains("Not this."), "switched off");
		assertTrue(briefing.contains("- Review: " + home.resolve("skills") + " - read its SKILL.md"), briefing);
		assertTrue(briefing.contains("- Docs: https://git.example.com/docs.git at v2, api"), briefing);
		assertTrue(briefing.contains("## Not found"), briefing);
		assertEquals(2, plan.notices().size(), plan.notices().toString());
		assertTrue(plan.notices().get(1).contains("not fetched yet"), plan.notices().toString());
	}

	@Test
	void environmentFilesAreReadAndAMissingOneStopsTheLaunch() throws IOException {
		Files.writeString(home.resolve(".env"), """
				# comment
				export API_URL=https://x.example
				QUOTED="a b"
				SINGLE='c'
				not a pair
				""");
		var profile = claude();
		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, "~/.env"));
		var environment = LaunchPlan.of(profile, home, home.toString(), home).environment();
		assertEquals(Map.of("MAVEN_OPTS", "-Xmx2g", "API_URL", "https://x.example", "QUOTED", "a b", "SINGLE", "c"),
				new LinkedHashMap<>(environment));

		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, "~/missing.env"));
		assertThrows(IllegalArgumentException.class, () -> LaunchPlan.of(profile, home, home.toString(), home));
	}

	@Test
	void relativePathsAreResolvedAgainstTheAgentsWorkingDirectoryNotCommanders() throws IOException {
		var workingDirectory = Files.createDirectories(home.resolve("project"));
		Files.writeString(workingDirectory.resolve(".env"), "FROM_PROJECT=yes");
		Files.createDirectories(workingDirectory.resolve("docs"));
		Files.writeString(workingDirectory.resolve("docs/rules.md"), "Project rules.");

		var profile = claude();
		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, ".env"));
		profile.getContext().getInstructions().add(ProfileRecord.file("Rules", "docs/rules.md"));
		profile.getContext().getKnowledge().add(ProfileRecord.file("Specs", "../specs"));

		var plan = LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), workingDirectory);

		assertEquals("yes", plan.environment().get("FROM_PROJECT"));
		assertTrue(plan.briefing().contains("### Rules\n\nProject rules."), plan.briefing());
		assertTrue(plan.briefing().contains("- Specs: " + home.resolve("specs")), plan.briefing());
		assertTrue(plan.notices().isEmpty(), plan.notices().toString());

		var pi = new Profile();
		pi.setName("Pi");
		pi.getHarness().setProvider("pi");
		pi.getContext().getSkills().add(ProfileRecord.file("Review", "skills/review"));
		assertEquals(List.of("--skill", workingDirectory.resolve("skills").resolve("review").toString()),
				LaunchPlan.of(pi, home, home.toString(), workingDirectory).arguments());
	}

	@Test
	void aHugeLinkedFileIsReadOnlyUpToTheLimit() throws IOException {
		var big = home.resolve("big.md");
		Files.writeString(big, "a".repeat(LaunchPlan.DOCUMENT_LIMIT * 3));

		assertEquals(LaunchPlan.DOCUMENT_LIMIT + 1, LaunchPlan.readBounded(big, LaunchPlan.DOCUMENT_LIMIT).length());
		assertEquals(5, LaunchPlan.readBounded(big, 4).length());

		var profile = claude();
		profile.getContext().getInstructions().add(ProfileRecord.file("Big", big.toString()));
		var briefing = LaunchPlan.of(profile, home, home.toString(), home).briefing();
		assertTrue(briefing.contains("[... truncated; read " + big), briefing.substring(briefing.length() - 200));
		assertTrue(briefing.length() < LaunchPlan.DOCUMENT_LIMIT + 1_000);

		var hugeEnvironment = home.resolve("huge.env");
		Files.writeString(hugeEnvironment, "A=" + "x".repeat(LaunchPlan.ENVIRONMENT_FILE_LIMIT));
		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, hugeEnvironment.toString()));
		var refused = assertThrows(IllegalArgumentException.class,
				() -> LaunchPlan.of(profile, home, home.toString(), home));
		assertTrue(refused.getMessage().contains("too large"), refused.getMessage());
	}

	@Test
	void secretsAreReadAtLaunchAndNeverNamedInAMessage() {
		var store = new MemoryStore();
		var secrets = new ProfileSecrets(store);
		var plan = LaunchPlan.of(claude(), home, home.toString(), home);

		var missing = assertThrows(IllegalStateException.class,
				() -> LaunchPlan.resolveSecrets(plan.secrets(), secrets, Map.of()));
		assertTrue(missing.getMessage().contains("missing"), missing.getMessage());

		store.entries.put("profile-secret/github", "ghp_s3cret");
		assertEquals(Map.of("NUCLR_MCP_GITHUB_TOKEN", "ghp_s3cret"),
				LaunchPlan.resolveSecrets(plan.secrets(), secrets, Map.of()));

		var unentered = List.of(new AgentConnector.SecretBinding("X", McpSecret.stored(null)));
		assertThrows(IllegalStateException.class, () -> LaunchPlan.resolveSecrets(unentered, secrets, Map.of()));
		var fromEnvironment = List.of(new AgentConnector.SecretBinding("TOKEN", McpSecret.environment("TOKEN")));
		assertEquals(Map.of("TOKEN", "t"), LaunchPlan.resolveSecrets(fromEnvironment, secrets, Map.of("TOKEN", "t")));
		var unset = assertThrows(IllegalStateException.class,
				() -> LaunchPlan.resolveSecrets(fromEnvironment, secrets, Map.of()));
		assertTrue(unset.getMessage().contains("TOKEN"));
	}

	@Test
	void theConfigFileIsWrittenOnlyWhenThereIsOne() throws IOException {
		var runtime = home.resolve("runtime");
		var plan = LaunchPlan.of(claude(), runtime, home.toString(), home);
		plan.writeConfigFile();
		assertFalse(Files.readString(plan.configFile()).contains("ghp_"), "no secret on disk");

		var pi = new Profile();
		pi.setName("Pi");
		pi.getHarness().setProvider("pi");
		var none = LaunchPlan.of(pi, home.resolve("other"), home.toString(), home);
		assertNull(none.configFile());
		none.writeConfigFile();
		assertFalse(Files.exists(home.resolve("other")));
	}
}
