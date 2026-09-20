package dev.nuclr.plugin.core.ai.projects.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/** A profile decides the window kind, except where it cannot speak for it. */
class AgentDialogsKindTest {

	private static final List<dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider> PROVIDERS =
			new AgentWindowRegistry(executable -> Optional.empty()).providers();

	private static Profile profileFor(String providerId) {
		var profile = new Profile();
		profile.setId("p1");
		profile.getHarness().setProvider(providerId);
		return profile;
	}

	@Test
	void profileDecidesTheKindOfItsProvider() {
		assertEquals(Optional.of(AgentProvider.CODEX),
				AgentDialogs.decidedBy(profileFor("codex"), "terminal.claude-code", PROVIDERS));
	}

	@Test
	void noProfileLeavesTheChoiceToTheUser() {
		assertTrue(AgentDialogs.decidedBy(null, "terminal.codex", PROVIDERS).isEmpty());
	}

	@Test
	void aProfileWithoutAProviderDecidesNothing() {
		assertTrue(AgentDialogs.decidedBy(profileFor(null), "terminal.shell", PROVIDERS).isEmpty());
		assertTrue(AgentDialogs.decidedBy(profileFor("something-else"), "terminal.shell", PROVIDERS).isEmpty());
	}

	@Test
	void aWindowThatIsNotATerminalIsNotTheProfilesBusiness() {
		assertTrue(AgentDialogs.decidedBy(profileFor("codex"), "board.tasks", PROVIDERS).isEmpty());
	}

	@Test
	void aConversationWindowStaysWhenTheProfileIsForItsCli() {
		assertEquals(Optional.of(AgentProvider.CLAUDE_CODE),
				AgentDialogs.decidedBy(profileFor("claude-code"), "chat.claude-code", PROVIDERS));
	}

	@Test
	void theKindAProfileDecidesIsAConversationAndNotATerminal() {

		// A profile names a CLI, and a CLI is talked to rather than watched: the dialog
		// has one kind to offer for it, so there is nothing left for the user to pick.
		assertEquals(List.of("chat.claude-code"),
				AgentDialogs.kindsFor(AgentProvider.CLAUDE_CODE, PROVIDERS).stream().map(each -> each.kind()).toList());
		assertEquals(List.of("chat.codex"),
				AgentDialogs.kindsFor(AgentProvider.CODEX, PROVIDERS).stream().map(each -> each.kind()).toList());
	}

	@Test
	void aProviderWithNoWindowKindInstalledDecidesNothing() {
		assertTrue(AgentDialogs.decidedBy(profileFor("codex"), "terminal.shell", List.of()).isEmpty());
	}
}
