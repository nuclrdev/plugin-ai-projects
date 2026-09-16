package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.ui.profile.ProfileForm;

/** Providers, the catalogues their connectors answer with, and the profile fields built on them. */
class ModelCatalogsTest {

	static ModelCatalog codex() {
		return new ModelCatalog(AgentProvider.CODEX, List.of(
				new ModelCatalog.Model("gpt-6-astra", "GPT-6-Astra", null, List.of("low", "medium", "ultra"), "low", null),
				new ModelCatalog.Model("gpt-5.5", "GPT-5.5", null, List.of("low", "xhigh"), "medium", null)),
				true, "codex");
	}

	static ModelCatalog claude() {
		var noAuto = EnumSet.allOf(AccessMode.class);
		noAuto.remove(AccessMode.AUTO);
		return new ModelCatalog(AgentProvider.CLAUDE_CODE, List.of(
				new ModelCatalog.Model("opus", "Opus", null, List.of("low", "high", "max"), null,
						EnumSet.allOf(AccessMode.class)),
				new ModelCatalog.Model("haiku", "Haiku", null, List.of(), null, noAuto)),
				true, "claude");
	}

	static ModelCatalogs answering() {
		return ModelCatalogs.answering((provider, executable) -> switch (provider) {
			case CODEX -> codex();
			case CLAUDE_CODE -> claude();
			case PI -> new ModelCatalog(provider, List.of(ModelCatalog.Model.named("anthropic/claude-opus-5", "Opus")),
					true, "pi");
		});
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	/** Let the invokeLater a finished catalogue queues run before looking. */
	private static void flushEdt() throws Exception {
		onEdt(() -> { });
		onEdt(() -> { });
	}

	@Test
	void providersAreFoundByTheirStoredId() {
		assertEquals(AgentProvider.CLAUDE_CODE, AgentProvider.byId("claude-code").orElseThrow());
		assertEquals(AgentProvider.CODEX, AgentProvider.byId(" Codex ").orElseThrow());
		assertTrue(AgentProvider.byId("anthropic").isEmpty());
		assertTrue(AgentProvider.byId(null).isEmpty());
		assertEquals("Extra high", AgentProvider.effortLabel("xhigh"));
	}

	@Test
	void eachProviderIsAskedOnceAndRefreshAsksAgain() throws Exception {
		var calls = new ArrayList<String>();
		var catalogs = ModelCatalogs.answering((provider, executable) -> {
			calls.add(provider.id() + " " + executable);
			return codex();
		});

		catalogs.catalog(AgentProvider.CODEX, "").get();
		catalogs.catalog(AgentProvider.CODEX, "").get();
		catalogs.catalog(AgentProvider.CODEX, "/opt/codex").get();
		catalogs.refresh(AgentProvider.CODEX, "").get();

		assertEquals(List.of("codex codex", "codex /opt/codex", "codex codex"), calls);
	}

	@Test
	void aCliThatCannotBeAskedFallsBackToTheConnectorsBuiltInListWithAReason() throws Exception {
		var catalogs = ModelCatalogs.answering((provider, executable) -> {
			throw new IOException("exit code 1: not logged in");
		});

		var codex = catalogs.catalog(AgentProvider.CODEX, "").get();
		var claude = catalogs.catalog(AgentProvider.CLAUDE_CODE, "").get();

		assertFalse(codex.live());
		assertTrue(codex.note().contains("not logged in"), codex.note());
		assertTrue(claude.models().stream().anyMatch(model -> model.id().equals("opus")));
	}

	@Test
	void theFormOffersTheProvidersModelsAndClearsModelAndEffortWhenTheProviderChanges() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("codex");
		profile.getHarness().setModel("gpt-5.5");
		profile.getHarness().setEffort("xhigh");

		var forms = new ProfileForm[1];
		onEdt(() -> forms[0] = new ProfileForm(profile, answering()));
		flushEdt();

		var before = new Profile[1];
		onEdt(() -> before[0] = forms[0].toProfile());
		assertEquals("codex", before[0].getHarness().getProvider());
		assertEquals("gpt-5.5", before[0].getHarness().getModel());
		assertEquals("xhigh", before[0].getHarness().getEffort());

		var after = new Profile[1];
		onEdt(() -> box(forms[0], AgentProvider.CLAUDE_CODE).setSelectedItem(AgentProvider.CLAUDE_CODE));
		flushEdt();
		onEdt(() -> after[0] = forms[0].toProfile());
		assertEquals("claude-code", after[0].getHarness().getProvider());
		assertNull(after[0].getHarness().getModel());
		assertNull(after[0].getHarness().getEffort());
	}

	@Test
	void choosingAModelWithoutAutoModeLetsGoOfAuto() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("claude-code");
		profile.getHarness().setModel("opus");
		profile.getHarness().setAccessMode("auto");

		var forms = new ProfileForm[1];
		onEdt(() -> forms[0] = new ProfileForm(profile, answering()));
		flushEdt();
		var results = new Object[3];
		onEdt(() -> {
			results[0] = forms[0].toProfile().getHarness().getAccessMode();
			box(forms[0], "haiku-model-box").setSelectedItem("haiku");
			var read = forms[0].toProfile().getHarness();
			results[1] = read.getAccessMode();
			results[2] = box(forms[0], "effort-box").isEnabled();
		});
		assertEquals("auto", results[0]);
		assertNull(results[1], "Haiku has no auto mode, so the default applies");
		assertEquals(false, results[2], "and no effort setting");
	}

	@Test
	void anUnsupportedProviderIsReportedNotDropped() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("anthropic");
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());

		var read = new Profile[1];
		onEdt(() -> read[0] = new ProfileForm(profile, answering()).toProfile());
		assertEquals("anthropic", read[0].getHarness().getProvider());
	}

	/**
	 * A combo box on the form: the provider box (marker is a provider), the model box
	 * ("haiku-model-box") or the effort box ("effort-box").
	 */
	@SuppressWarnings("unchecked")
	static JComboBox<Object> box(java.awt.Container root, Object marker) {
		var result = new Object[1];
		walk(root, component -> {
			if (result[0] != null || !(component instanceof JComboBox<?> box)) {
				return;
			}
			var matches = switch (marker) {
				case AgentProvider provider -> box.getItemCount() > 1 && box.getItemAt(1) == AgentProvider.CLAUDE_CODE;
				case String name when name.equals("haiku-model-box") -> box.isEditable();
				case String name when name.equals("effort-box") ->
						!box.isEditable() && box.getItemCount() > 0 && box.getItemAt(0) instanceof String
								&& (box.getItemCount() == 1 || box.getItemAt(1) instanceof String);
				default -> box.getItemCount() > 1 && box.getItemAt(1) == marker;
			};
			if (matches) {
				result[0] = box;
			}
		});
		return (JComboBox<Object>) result[0];
	}

	private static void walk(java.awt.Component component, java.util.function.Consumer<java.awt.Component> visit) {
		visit.accept(component);
		if (component instanceof java.awt.Container container) {
			for (var child : container.getComponents()) {
				walk(child, visit);
			}
		}
	}
}
