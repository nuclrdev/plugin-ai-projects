package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * Putting a model chosen in the conversation onto the next session's command line,
 * in place of whatever the profile asked for rather than beside it.
 */
class LaunchOverridesTest {

	private static final List<String> CLAUDE = List.of("claude", "--model", "sonnet-5", "-p", "--verbose");

	@Test
	void theChosenModelReplacesTheProfilesOwn() {
		assertEquals(List.of("claude", "-p", "--verbose", "--model", "opus-5"),
				LaunchOverrides.apply(CLAUDE, AgentProvider.CLAUDE_CODE.connector(), "opus-5", null));
	}

	@Test
	void aModelWrittenWithAnEqualsSignIsReplacedToo() {
		assertEquals(List.of("claude", "-p", "--model", "opus-5"),
				LaunchOverrides.apply(List.of("claude", "--model=sonnet-5", "-p"),
						AgentProvider.CLAUDE_CODE.connector(), "opus-5", null));
	}

	@Test
	void aCommandThatNamedNoModelSimplyGetsOne() {
		assertEquals(List.of("claude", "-p", "--model", "opus-5"),
				LaunchOverrides.apply(List.of("claude", "-p"), AgentProvider.CLAUDE_CODE.connector(), "opus-5", null));
	}

	@Test
	void aThinkingLevelIsAppliedOnTheSameTerms() {
		assertEquals(List.of("claude", "--model", "sonnet-5", "--effort", "high"),
				LaunchOverrides.apply(List.of("claude", "--model", "sonnet-5", "--effort", "low"),
						AgentProvider.CLAUDE_CODE.connector(), null, "high"));
	}

	@Test
	void anExecutableIsNeverMistakenForAFlagOrDropped() {
		assertEquals(List.of("--model", "--model", "opus-5"),
				LaunchOverrides.apply(List.of("--model"), AgentProvider.CLAUDE_CODE.connector(), "opus-5", null));
	}

	@Test
	void nothingChosenAndNothingKnownLeaveTheCommandAlone() {
		assertEquals(CLAUDE, LaunchOverrides.apply(CLAUDE, AgentProvider.CLAUDE_CODE.connector(), null, null));
		assertEquals(CLAUDE, LaunchOverrides.apply(CLAUDE, null, "opus-5", "high"));
		assertEquals(List.of(), LaunchOverrides.apply(List.of(), AgentProvider.CLAUDE_CODE.connector(), "opus-5", null));
	}

	@Test
	void fullAccessReplacesTheProfilesPermissionModeWhateverItWas() {
		assertEquals(List.of("claude", "-p", "--permission-mode", "bypassPermissions"),
				LaunchOverrides.applyAccess(List.of("claude", "--permission-mode", "acceptEdits", "-p"),
						AgentProvider.CLAUDE_CODE.connector(), AccessMode.FULL_ACCESS).orElseThrow());
	}

	@Test
	void aCommandWithNoAccessOfItsOwnSimplyGetsOne() {
		assertEquals(List.of("claude", "-p", "--permission-mode", "bypassPermissions"),
				LaunchOverrides.applyAccess(List.of("claude", "-p"), AgentProvider.CLAUDE_CODE.connector(),
						AccessMode.FULL_ACCESS).orElseThrow());
	}

	@Test
	void codexLosesItsSandboxAndApprovalFlagsForFullAccess() {
		assertEquals(List.of("codex", "--model", "gpt", "--dangerously-bypass-approvals-and-sandbox"),
				LaunchOverrides.applyAccess(
						List.of("codex", "--sandbox", "workspace-write", "--ask-for-approval", "on-request", "--model", "gpt"),
						AgentProvider.CODEX.connector(), AccessMode.FULL_ACCESS).orElseThrow());
	}

	@Test
	void piKeepsAToolListThatIsNotItsReadOnlyOne() {
		assertEquals(List.of("pi", "--tools", "read,bash"),
				LaunchOverrides.applyAccess(List.of("pi", "--tools", "read,bash"), AgentProvider.PI.connector(),
						AccessMode.FULL_ACCESS).orElseThrow());
		assertEquals(List.of("pi"),
				LaunchOverrides.applyAccess(List.of("pi", "--tools", "read,grep,find,ls"), AgentProvider.PI.connector(),
						AccessMode.FULL_ACCESS).orElseThrow());
	}

	@Test
	void aModeTheCliDoesNotHaveIsRefused() {
		assertTrue(LaunchOverrides.applyAccess(List.of("pi"), AgentProvider.PI.connector(), AccessMode.ASK).isEmpty());
	}

	@Test
	void accessIsNamedTheWaysPeopleTypeIt() {
		assertEquals(AccessMode.FULL_ACCESS, ChatAgentWindow.accessNamed("full").orElseThrow());
		assertEquals(AccessMode.FULL_ACCESS, ChatAgentWindow.accessNamed("Full access").orElseThrow());
		assertEquals(AccessMode.READ_ONLY, ChatAgentWindow.accessNamed("readonly").orElseThrow());
		assertEquals(AccessMode.ASK, ChatAgentWindow.accessNamed("ask").orElseThrow());
		assertTrue(ChatAgentWindow.accessNamed("custom").isEmpty());
		assertTrue(ChatAgentWindow.accessNamed("whatever").isEmpty());
	}
}
