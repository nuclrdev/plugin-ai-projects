package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.ui.profile.ProfileForm;

/** One access mode, mapped onto each provider's own flags - or refused. */
class AccessModeTest {

	@Test
	void eachProviderGetsItsOwnFlags() {
		assertEquals(List.of("--permission-mode", "manual"),
				AgentProvider.CLAUDE_CODE.accessArguments(AccessMode.ASK).orElseThrow());
		assertEquals(List.of("--permission-mode", "plan"),
				AgentProvider.CLAUDE_CODE.accessArguments(AccessMode.READ_ONLY).orElseThrow());
		assertEquals(List.of("--sandbox", "workspace-write", "--ask-for-approval", "on-request"),
				AgentProvider.CODEX.accessArguments(AccessMode.ASK).orElseThrow());
		assertEquals(List.of("--dangerously-bypass-approvals-and-sandbox"),
				AgentProvider.CODEX.accessArguments(AccessMode.FULL_ACCESS).orElseThrow());
		assertEquals(List.of("--tools", "read,grep,find,ls"),
				AgentProvider.PI.accessArguments(AccessMode.READ_ONLY).orElseThrow());
		assertEquals(List.of(), AgentProvider.PI.accessArguments(AccessMode.FULL_ACCESS).orElseThrow());
	}

	@Test
	void piCannotAskOrReviewAndIsNeverPretendedTo() {
		assertFalse(AgentProvider.PI.supports(AccessMode.ASK));
		assertFalse(AgentProvider.PI.supports(AccessMode.AUTO));
		assertTrue(AgentProvider.PI.supports(AccessMode.CUSTOM));
		for (var provider : AgentProvider.values()) {
			assertEquals(List.of(), provider.accessArguments(AccessMode.CUSTOM).orElseThrow());
		}
	}

	@Test
	void theDefaultAsksWhereverTheProviderCan() {
		assertEquals(AccessMode.ASK, AgentProvider.CLAUDE_CODE.defaultAccessMode());
		assertEquals(AccessMode.ASK, AgentProvider.CODEX.defaultAccessMode());
		assertEquals(AccessMode.FULL_ACCESS, AgentProvider.PI.defaultAccessMode());
	}

	@Test
	void anUnsupportedOrUnknownModeStopsTheSave() {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("pi");
		profile.getHarness().setAccessMode("ask");
		var problems = ProfileValidator.validate(profile, List.of());
		assertEquals(1, problems.size());
		assertTrue(problems.getFirst().message().startsWith("Access mode: Pi has no approval prompts"),
				problems.getFirst().message());

		profile.getHarness().setAccessMode("sometimes");
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());

		profile.getHarness().setAccessMode("read-only");
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
	}

	@Test
	void switchingToAProviderThatCannotHonourTheModeResetsIt() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("codex");
		profile.getHarness().setAccessMode("auto");
		var forms = new ProfileForm[1];
		var before = new Profile[1];
		var after = new Profile[1];
		SwingUtilities.invokeAndWait(() -> {
			forms[0] = new ProfileForm(profile, ModelCatalogsTest.answering());
			before[0] = forms[0].toProfile();
			box(forms[0], AgentProvider.CLAUDE_CODE).setSelectedItem(AgentProvider.PI);
			after[0] = forms[0].toProfile();
		});
		assertEquals("auto", before[0].getHarness().getAccessMode());
		assertEquals("pi", after[0].getHarness().getProvider());
		assertNull(after[0].getHarness().getAccessMode());
	}

	@Test
	void aModeTheProviderCannotHonourCannotBeChosen() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("pi");
		profile.getHarness().setAccessMode("read-only");
		var after = new Profile[1];
		SwingUtilities.invokeAndWait(() -> {
			var form = new ProfileForm(profile, ModelCatalogsTest.answering());
			box(form, AccessMode.READ_ONLY).setSelectedItem(AccessMode.ASK);
			after[0] = form.toProfile();
		});
		assertEquals("read-only", after[0].getHarness().getAccessMode());
	}

	/** The combo box whose second entry is {@code marker}. */
	@SuppressWarnings("unchecked")
	private static JComboBox<Object> box(Container root, Object marker) {
		var found = new Object[1];
		walk(root, component -> {
			if (found[0] == null && component instanceof JComboBox<?> box && box.getItemCount() > 1
					&& box.getItemAt(1) == marker) {
				found[0] = box;
			}
		});
		return (JComboBox<Object>) found[0];
	}

	private static void walk(Component component, Consumer<Component> visit) {
		visit.accept(component);
		if (component instanceof Container container) {
			for (var child : container.getComponents()) {
				walk(child, visit);
			}
		}
	}
}
