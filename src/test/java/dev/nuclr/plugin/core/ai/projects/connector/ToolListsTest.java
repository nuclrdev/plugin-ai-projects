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

/** Allowed and blocked tools, as each connector turns them into flags - or refuses them. */
class ToolListsTest {

	private static final AgentConnector CLAUDE = AgentConnectors.of(AgentProvider.CLAUDE_CODE);
	private static final AgentConnector CODEX = AgentConnectors.of(AgentProvider.CODEX);
	private static final AgentConnector PI = AgentConnectors.of(AgentProvider.PI);

	@Test
	void claudeLimitsToTheAllowedToolsAndDeniesPatterns() {
		assertEquals(List.of("--tools", "Read,Grep,Bash", "--disallowedTools", "Bash(git push *),WebFetch"),
				CLAUDE.launchArguments(null, null, AccessMode.CUSTOM, List.of("Read", "Grep", "Bash"),
						List.of("Bash(git push *)", "WebFetch")));
		assertTrue(CLAUDE.isBuiltInTool("Bash(npm run *)"));
		assertFalse(CLAUDE.isBuiltInTool("mcp__github__create_issue"));
		assertTrue(CLAUDE.toolProblems(List.of(), List.of("mcp__github__create_issue"), AccessMode.ASK).isEmpty(),
				"MCP tools may be blocked by name");
		assertFalse(CLAUDE.toolProblems(List.of("Bash(git *)"), List.of(), AccessMode.ASK).isEmpty(),
				"patterns only make sense as blocks");
	}

	@Test
	void piNarrowsReadOnlyInsteadOfPassingTwoToolLists() {
		assertEquals(List.of("--tools", "read,grep"),
				PI.launchArguments(null, null, AccessMode.READ_ONLY, List.of("read", "grep"), List.of()));
		assertEquals(List.of("--tools", "read,grep,find,ls", "--exclude-tools", "ls"),
				PI.launchArguments(null, null, AccessMode.READ_ONLY, List.of(), List.of("ls")));
		assertEquals(List.of("--tools", "read,bash", "--exclude-tools", "write"),
				PI.launchArguments(null, null, AccessMode.FULL_ACCESS, List.of("read", "bash"), List.of("write")));
		assertThrows(IllegalArgumentException.class,
				() -> PI.launchArguments(null, null, AccessMode.READ_ONLY, List.of("bash"), List.of()),
				"read-only can only be narrowed, never widened");
	}

	@Test
	void codexCanOnlySwitchOptionalToolsOnAndOff() {
		assertEquals(List.of("--search", "--enable", "multi_agent", "--disable", "shell_tool"),
				CODEX.launchArguments(null, null, AccessMode.CUSTOM, List.of("web_search", "multi_agent"),
						List.of("shell_tool")));
		assertEquals(List.of(), CODEX.blockedToolArguments(List.of("web_search")), "off unless allowed, so no flag");
		assertFalse(CODEX.toolProblems(List.of("Bash"), List.of(), AccessMode.ASK).isEmpty());
		assertFalse(CODEX.toolProblems(List.of("shell_tool"), List.of("shell_tool"), AccessMode.ASK).isEmpty());
	}

	@Test
	void theProfileValidatorAsksTheProvidersConnector() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().getAllowedTools().add("Bash");
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("choose a provider"));

		profile.getHarness().setProvider("pi");
		profile.getHarness().setAccessMode("read-only");
		profile.getHarness().setToolAccess(Profile.Harness.TOOL_ACCESS_ONLY);
		profile.getHarness().setAllowedTools(List.of("bash"));
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());

		profile.getHarness().setAllowedTools(List.of("read"));
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
	}

	@Test
	void onlySelectedToolsNeedsAToolAndAProviderThatCanBeLimited() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("claude-code");
		profile.getHarness().setToolAccess(Profile.Harness.TOOL_ACCESS_ONLY);
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("no tool is selected"),
				"\"only these\" with nothing chosen is a mistake, not \"all\"");

		profile.getHarness().setAllowedTools(List.of("Read"));
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());

		profile.getHarness().setProvider("codex");
		profile.getHarness().setAllowedTools(List.of("web_search"));
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("cannot be limited"));

		profile.getHarness().setToolAccess(null);
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty(), "for Codex the list switches tools on");
	}

	@Test
	void aListLeftFromOnlySelectedToolsIsNotInForceUnderAllTools() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("pi");
		profile.getHarness().setAccessMode("read-only");
		profile.getHarness().setAllowedTools(List.of("bash"));
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
		assertTrue(CLAUDE.canRestrictTools() && PI.canRestrictTools() && !CODEX.canRestrictTools());
	}

	@Test
	void theOldFreeTextToolRecordsBecomeTheAllowedList() throws IOException {
		var profile = Json.fromJson("""
				{"name": "Old", "harness": {"provider": "claude-code", "tools": [
				  {"kind": "TEXT", "text": "Read", "enabled": true},
				  {"kind": "TEXT", "text": "Bash", "enabled": false},
				  {"kind": "TEXT", "text": " Grep ", "enabled": true}]}}""", Profile.class);
		assertEquals(List.of("Read", "Grep"), profile.getHarness().getAllowedTools());
		assertTrue(profile.getHarness().restrictsTools(), "they were a list of the tools to use");
		assertFalse(Json.toJson(profile).contains("\"tools\""), "written back in the new shape only");
	}
}
