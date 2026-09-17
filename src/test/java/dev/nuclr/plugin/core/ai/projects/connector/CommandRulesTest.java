package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/** Shell commands that run without asking, or never run, as each provider takes them. */
class CommandRulesTest {

	private static final AgentConnector CLAUDE = AgentConnectors.of(AgentProvider.CLAUDE_CODE);
	private static final AgentConnector CODEX = AgentConnectors.of(AgentProvider.CODEX);
	private static final AgentConnector PI = AgentConnectors.of(AgentProvider.PI);

	@Test
	void claudeGetsARuleForEachShellToolAndBlockedCommandsJoinTheBlockedTools() {
		var arguments = CLAUDE.launchArguments(null, null, AccessMode.ASK, List.of(), List.of("WebFetch"),
				List.of("git status"), List.of("git push"));

		assertEquals(List.of("--permission-mode", "manual",
				"--allowedTools", "Bash(git status),Bash(git status *),PowerShell(git status),PowerShell(git status *)",
				"--disallowedTools", "WebFetch,Bash(git push),Bash(git push *),PowerShell(git push),PowerShell(git push *)"),
				arguments);
	}

	@Test
	void claudeRefusesAllowedCommandsWhenNoToolCanRunThem() {
		assertFalse(CLAUDE.commandProblems(List.of("git status"), List.of(), List.of("Read", "Grep")).isEmpty());
		assertTrue(CLAUDE.commandProblems(List.of("git status"), List.of(), List.of("Read", "Bash")).isEmpty());
		assertTrue(CLAUDE.commandProblems(List.of("git status"), List.of(), List.of()).isEmpty(), "every tool");
		assertTrue(CLAUDE.commandProblems(List.of(), List.of("rm -rf"), List.of("Read")).isEmpty(),
				"blocking is harmless");
	}

	@Test
	void anEntryIsACommandPrefixAndNotAPattern() {
		for (var entry : List.of("git *", "Bash(git push)", "git,npm", " ", "git\npush")) {
			assertTrue(AgentConnector.commandEntryProblem(entry).isPresent(), entry);
		}
		assertTrue(AgentConnector.commandEntryProblem("git reset --hard").isEmpty());
		assertFalse(CLAUDE.commandProblems(List.of("git push"), List.of("git push"), List.of()).isEmpty(),
				"both allowed and blocked");
	}

	@Test
	void codexAndPiCannotTakeCommandRules() {
		for (var connector : List.of(CODEX, PI)) {
			assertFalse(connector.supportsCommandRules());
			assertTrue(connector.commandProblems(List.of(), List.of(), List.of()).isEmpty());
			assertFalse(connector.commandProblems(List.of(), List.of("rm -rf"), List.of()).isEmpty());
			assertThrows(IllegalArgumentException.class, () -> connector.launchArguments(null, null,
					AccessMode.FULL_ACCESS, List.of(), List.of(), List.of("git status"), List.of()));
		}
	}

	@Test
	void theValidatorChecksCommandsWithOrWithoutAProvider() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().getBlockedCommands().add("rm -rf");
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty(), "commands are the same for every provider");

		profile.getHarness().getBlockedCommands().add("rm -rf");
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("more than once"));

		profile.getHarness().setBlockedCommands(List.of("rm *"));
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().startsWith("Commands"));

		profile.getHarness().setBlockedCommands(List.of("rm -rf"));
		profile.getHarness().setProvider("codex");
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("Codex"));
	}

	@Test
	void oldSoftwareHardwareAndPermissionsEntriesAreDroppedOnRead() throws IOException {
		var json = """
				{"name": "Old", "harness": {
				  "software": [{"kind": "TEXT", "text": "git"}],
				  "hardware": [{"kind": "TEXT", "text": "gpu"}],
				  "permissions": [{"kind": "TEXT", "text": "Bash(*)"}]}}""";
		var profile = Json.fromJson(json, Profile.class);
		assertEquals("Old", profile.getName());
		assertFalse(Json.toJson(profile).contains("Bash(*)"), "nor written back");
		assertTrue(profile.getHarness().getAllowedCommands().isEmpty(), "never granted by a list that meant nothing");
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
	}
}
