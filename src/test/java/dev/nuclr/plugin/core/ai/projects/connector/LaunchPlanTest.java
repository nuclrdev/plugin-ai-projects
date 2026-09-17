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
import java.util.ArrayList;
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
import dev.nuclr.plugin.core.ai.projects.store.TextFiles;

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

	/** Repositories as folders already on disk, recording what was asked for. */
	private static final class FakeGit implements GitSources {

		final Map<String, Path> copies = new HashMap<>();
		final List<String> asked = new ArrayList<>();

		@Override
		public Checkout checkout(String repository, String ref) throws IOException {
			asked.add(repository + "@" + ref);
			var copy = copies.get(repository);
			if (copy == null) {
				throw new IOException("could not resolve host");
			}
			return new Checkout(copy, false, null);
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

	private static Profile named(String provider) {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider(provider);
		return profile;
	}

	private LaunchPlan plan(Profile profile) {
		return LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), home, GitSources.NONE);
	}

	/** A skill folder with front matter, and a script beside it. */
	private Path skill(Path parent, String name, String description) throws IOException {
		var folder = Files.createDirectories(parent.resolve(name));
		Files.writeString(folder.resolve("SKILL.md"),
				"---\nname: " + name + "\ndescription: \"" + description + "\"\n---\n\nHow to " + name + ".\n");
		Files.createDirectories(folder.resolve("scripts"));
		Files.writeString(folder.resolve("scripts/run.sh"), "echo " + name);
		return folder;
	}

	private String underHome(String rest) {
		return home + File.separator + rest;
	}

	@Test
	void aClaudeProfileBecomesOneCommandLine() {
		var runtime = home.resolve("runtime");
		var plan = plan(claude());

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
		assertNull(plan.skillsPlugin());
	}

	@Test
	void aProfileThatDoesNotValidateOrNamesNoProviderIsRefused() {
		var noProvider = new Profile();
		noProvider.setName("Empty");
		var refused = assertThrows(IllegalArgumentException.class, () -> plan(noProvider));
		assertTrue(refused.getMessage().contains("no provider"), refused.getMessage());

		var codex = named("codex");
		codex.setName("Codex");
		codex.getHarness().getBlockedCommands().add("rm -rf");
		refused = assertThrows(IllegalArgumentException.class, () -> plan(codex));
		assertTrue(refused.getMessage().startsWith("Profile \"Codex\" cannot be used yet: Commands"), refused.getMessage());
	}

	@Test
	void codexGetsItsSandboxNetwork() {
		var codex = named("codex");
		codex.getHarness().setAccessMode("auto");
		codex.getHarness().setSandboxNetworkAccess(true);
		assertEquals(List.of("--sandbox", "workspace-write", "--approve-for-me",
				"-c", "sandbox_workspace_write.network_access=true"), plan(codex).arguments());
	}

	// ------------------------------------------------------------------ skills

	@Test
	void piLoadsEachSkillFolderWithItsOwnFlag() throws IOException {
		var review = skill(home.resolve("skills"), "review", "Reviews code");
		var pi = named("pi");
		pi.getContext().getSkills().add(ProfileRecord.file("Review", "~/skills/review"));
		// Pointing at the SKILL.md itself means its folder.
		pi.getContext().getSkills().add(ProfileRecord.file(null, "~/skills/review/SKILL.md".replace("review/", "review2/")));
		skill(home.resolve("skills"), "review2", "Also reviews");

		var plan = plan(pi);

		assertEquals(List.of("--skill", review.toString(), "--skill", home.resolve("skills").resolve("review2").toString()),
				plan.arguments());
		assertEquals("", plan.briefing(), "a skill loaded as one is not repeated in the briefing");
	}

	@Test
	void claudeLoadsSkillsFromAPluginFolderOfCopies() throws IOException {
		var review = skill(home.resolve("a"), "review", "Reviews code");
		var sameName = skill(home.resolve("b"), "review", "Another review");
		var profile = named("claude-code");
		profile.getContext().getSkills().add(ProfileRecord.file(null, review.toString()));
		profile.getContext().getSkills().add(ProfileRecord.file(null, sameName.toString()));
		var runtime = home.resolve("runtime");

		var plan = plan(profile);

		var pluginFolder = runtime.resolve("nuclr-profile");
		assertEquals(List.of("--permission-mode", "manual", "--plugin-dir", pluginFolder.toString()), plan.arguments());
		assertEquals(pluginFolder, plan.skillsPlugin());
		assertEquals("", plan.briefing());

		// A stale copy from an earlier start is removed, not merged with.
		Files.createDirectories(pluginFolder.resolve("skills/removed"));
		plan.writeFiles();

		assertTrue(Files.isRegularFile(pluginFolder.resolve("skills/review/SKILL.md")));
		assertTrue(Files.isRegularFile(pluginFolder.resolve("skills/review/scripts/run.sh")), "the whole folder");
		assertTrue(Files.readString(pluginFolder.resolve("skills/review-2/SKILL.md")).contains("Another review"),
				"two skills with one name do not overwrite each other");
		assertFalse(Files.exists(pluginFolder.resolve("skills/removed")));
	}

	@Test
	void codexListsSkillsWithTheirDescriptions() throws IOException {
		var review = skill(home.resolve("skills"), "review", "Reviews code for defects");
		var codex = named("codex");
		codex.getContext().getSkills().add(ProfileRecord.file("Mine", review.toString()));

		var briefing = plan(codex).briefing();

		assertTrue(briefing.contains("## Skills"), briefing);
		assertTrue(briefing.contains("- review: Reviews code for defects (" + review.resolve("SKILL.md") + ")"), briefing);
	}

	@Test
	void aFolderWithoutSkillMdIsNotLoadedAndSaysSo() throws IOException {
		var notASkill = Files.createDirectories(home.resolve("docs"));
		var profile = named("claude-code");
		profile.getContext().getSkills().add(ProfileRecord.file("Docs", notASkill.toString()));

		var plan = plan(profile);

		assertFalse(plan.arguments().contains("--plugin-dir"));
		assertTrue(plan.notices().getFirst().contains("has no SKILL.md"), plan.notices().toString());
		assertTrue(plan.briefing().contains("## Not found"), plan.briefing());
	}

	@Test
	void aSkillFolderTooLargeToCopyStopsTheLaunch() throws IOException {
		var huge = skill(home.resolve("skills"), "huge", "Too big");
		for (var index = 0; index <= LaunchPlan.SKILL_FILE_LIMIT; index++) {
			Files.writeString(huge.resolve("f" + index + ".txt"), "x");
		}
		var profile = named("claude-code");
		profile.getContext().getSkills().add(ProfileRecord.file(null, huge.toString()));

		var refused = assertThrows(IOException.class, () -> plan(profile).writeFiles());
		assertTrue(refused.getMessage().contains("too large"), refused.getMessage());
	}

	// ------------------------------------------------------------------ git

	@Test
	void gitSourcesAreReadFromTheirCheckoutAndEachRepositoryIsFetchedOnce() throws IOException {
		var copy = Files.createDirectories(home.resolve("copy"));
		Files.createDirectories(copy.resolve("docs"));
		Files.writeString(copy.resolve("docs/RULES.md"), "Shared rules.");
		skill(copy.resolve("skills"), "deploy", "Deploys");
		var git = new FakeGit();
		git.copies.put("https://git.example.com/team.git", copy);

		var profile = named("pi");
		var context = profile.getContext();
		context.getInstructions().add(ProfileRecord.git("Rules", "https://git.example.com/team.git", "main", "docs/RULES.md"));
		context.getSkills().add(ProfileRecord.git(null, "https://git.example.com/team.git", "main", "skills/deploy"));
		context.getKnowledge().add(ProfileRecord.git("Docs", "https://git.example.com/team.git", "main", "docs"));
		context.getKnowledge().add(ProfileRecord.git("Gone", "https://git.example.com/other.git", null, null));

		var plan = LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), home, git);

		assertEquals(List.of("https://git.example.com/team.git@main", "https://git.example.com/other.git@"), git.asked,
				"one fetch per repository and ref");
		assertTrue(plan.briefing().contains("### Rules\n\nShared rules."), plan.briefing());
		assertEquals(List.of("--skill", copy.resolve("skills").resolve("deploy").toString()), plan.arguments());
		assertTrue(plan.briefing().contains("- Docs: " + copy.resolve("docs") + " (a copy of https://git.example.com/team.git at main)"),
				plan.briefing());
		assertTrue(plan.notices().stream().anyMatch(notice -> notice.contains("could not be fetched")
				&& notice.contains("could not resolve host")), plan.notices().toString());
		// A path leaving its repository ("..") is refused by the validator before a launch gets here.
	}

	@Test
	void aCopyThatCouldNotBeUpdatedIsUsedAndSaidToBe() throws IOException {
		var copy = Files.createDirectories(home.resolve("copy"));
		Files.writeString(copy.resolve("RULES.md"), "Old rules.");
		GitSources stale = (repository, ref) -> new GitSources.Checkout(copy, true, "offline");
		var profile = named("claude-code");
		profile.getContext().getInstructions().add(ProfileRecord.git("Rules", "https://git.example.com/r.git", null, "RULES.md"));

		var plan = LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), home, stale);

		assertTrue(plan.briefing().contains("Old rules."));
		assertTrue(plan.notices().getFirst().contains("from an earlier start") && plan.notices().getFirst().contains("offline"),
				plan.notices().toString());
	}

	// ------------------------------------------------------------------ instructions and knowledge

	@Test
	void theContextBecomesABriefingAndWhatCannotBeReadIsSaid() throws IOException {
		Files.writeString(home.resolve("style.md"), "Use British English.");
		Files.createDirectories(home.resolve("specs"));
		var profile = claude();
		var context = profile.getContext();
		context.getInstructions().add(ProfileRecord.text("Rules", "No force pushes."));
		context.getInstructions().add(ProfileRecord.file("Style", "~/style.md"));
		context.getInstructions().add(ProfileRecord.file("Gone", "~/gone.md"));
		var off = ProfileRecord.text("Off", "Not this.");
		off.setEnabled(false);
		context.getInstructions().add(off);
		context.getKnowledge().add(ProfileRecord.file("Specs", "~/specs"));

		var plan = plan(profile);
		var briefing = plan.briefing();

		assertTrue(briefing.startsWith("# Profile: Team Claude"), briefing);
		assertTrue(briefing.contains("### Rules\n\nNo force pushes."), briefing);
		assertTrue(briefing.contains("### Style\n\nUse British English."), briefing);
		assertFalse(briefing.contains("Not this."), "switched off");
		assertTrue(briefing.contains("## Knowledge"), briefing);
		assertTrue(briefing.contains("- Specs: " + home.resolve("specs")), briefing);
		assertTrue(briefing.contains("## Not found"), briefing);
		assertEquals(1, plan.notices().size(), plan.notices().toString());
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
		var environment = plan(profile).environment();
		assertEquals(Map.of("MAVEN_OPTS", "-Xmx2g", "API_URL", "https://x.example", "QUOTED", "a b", "SINGLE", "c"),
				new LinkedHashMap<>(environment));

		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, "~/missing.env"));
		assertThrows(IllegalArgumentException.class, () -> plan(profile));
	}

	@Test
	void relativePathsAreResolvedAgainstTheAgentsWorkingDirectoryNotCommanders() throws IOException {
		var workingDirectory = Files.createDirectories(home.resolve("project"));
		Files.writeString(workingDirectory.resolve(".env"), "FROM_PROJECT=yes");
		Files.createDirectories(workingDirectory.resolve("docs"));
		Files.writeString(workingDirectory.resolve("docs/rules.md"), "Project rules.");
		Files.createDirectories(home.resolve("specs"));
		skill(workingDirectory.resolve("skills"), "review", "Reviews");

		var profile = named("pi");
		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, ".env"));
		profile.getContext().getInstructions().add(ProfileRecord.file("Rules", "docs/rules.md"));
		profile.getContext().getKnowledge().add(ProfileRecord.file("Specs", "../specs"));
		profile.getContext().getSkills().add(ProfileRecord.file("Review", "skills/review"));

		var plan = LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), workingDirectory, GitSources.NONE);

		assertEquals("yes", plan.environment().get("FROM_PROJECT"));
		assertTrue(plan.briefing().contains("### Rules\n\nProject rules."), plan.briefing());
		assertTrue(plan.briefing().contains("- Specs: " + home.resolve("specs")), plan.briefing());
		assertEquals(List.of("--skill", workingDirectory.resolve("skills").resolve("review").toString()), plan.arguments());
		assertTrue(plan.notices().isEmpty(), plan.notices().toString());
	}

	@Test
	void aPreviewWithoutAWorkingFolderLeavesRelativePathsForTheStart() {
		var profile = named("claude-code");
		profile.getContext().getInstructions().add(ProfileRecord.file("Rules", "docs/rules.md"));

		var plan = LaunchPlan.of(profile, home.resolve("runtime"), home.toString(), null, GitSources.NONE);

		assertTrue(plan.notices().getFirst().contains("found in the agent's working folder when it starts"),
				plan.notices().toString());
		assertFalse(plan.briefing().contains("Not found"), "not missing, only not looked for yet: " + plan.briefing());
	}

	@Test
	void aHugeLinkedFileIsReadOnlyUpToTheLimit() throws IOException {
		var big = home.resolve("big.md");
		Files.writeString(big, "a".repeat(LaunchPlan.DOCUMENT_LIMIT * 3));

		assertEquals(LaunchPlan.DOCUMENT_LIMIT + 1, TextFiles.readBounded(big, LaunchPlan.DOCUMENT_LIMIT).length());
		assertEquals(5, TextFiles.readBounded(big, 4).length());

		var profile = claude();
		profile.getContext().getInstructions().add(ProfileRecord.file("Big", big.toString()));
		var briefing = plan(profile).briefing();
		assertTrue(briefing.contains("[... truncated; read " + big), briefing.substring(briefing.length() - 200));
		assertTrue(briefing.length() < LaunchPlan.DOCUMENT_LIMIT + 1_000);

		var hugeEnvironment = home.resolve("huge.env");
		Files.writeString(hugeEnvironment, "A=" + "x".repeat(LaunchPlan.ENVIRONMENT_FILE_LIMIT));
		profile.getHarness().getEnvironment().add(ProfileRecord.file(null, hugeEnvironment.toString()));
		var refused = assertThrows(IllegalArgumentException.class, () -> plan(profile));
		assertTrue(refused.getMessage().contains("too large"), refused.getMessage());
	}

	// ------------------------------------------------------------------ secrets and files

	@Test
	void secretsAreReadAtLaunchAndNeverNamedInAMessage() {
		var store = new MemoryStore();
		var secrets = new ProfileSecrets(store);
		var plan = plan(claude());

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
		var plan = plan(claude());
		plan.writeFiles();
		assertFalse(Files.readString(plan.configFile()).contains("ghp_"), "no secret on disk");

		var none = LaunchPlan.of(named("pi"), home.resolve("other"), home.toString(), home, GitSources.NONE);
		assertNull(none.configFile());
		none.writeFiles();
		assertFalse(Files.exists(home.resolve("other")));
	}
}
