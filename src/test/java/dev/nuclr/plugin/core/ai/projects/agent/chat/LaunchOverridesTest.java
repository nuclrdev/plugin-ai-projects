package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

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
}
