package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.ui.profile.ProfileForm;

/** Providers, the model lists read from their CLIs, and the profile fields built on them. */
class ModelCatalogsTest {

	static final String CODEX = """
			{"models":[
			  {"slug":"gpt-6-astra","display_name":"GPT-6-Astra","visibility":"list","default_reasoning_level":"low",
			   "supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"ultra"}]},
			  {"slug":"gpt-reserve","display_name":"GPT-Reserve","visibility":"hide",
			   "supported_reasoning_levels":[{"effort":"low"}]},
			  {"slug":"gpt-5.5","display_name":"GPT-5.5","visibility":"list","default_reasoning_level":"medium",
			   "supported_reasoning_levels":[{"effort":"low"},{"effort":"xhigh"}]}
			]}""";

	static final String PI = """
			provider    model                          context  max-out  thinking  images
			anthropic   claude-opus-5                  1M       128K     yes       yes
			openrouter  amazon/nova-lite-v1            300K     5.1K     no        yes
			openrouter  ~openai/gpt-latest             1.1M     128K     yes       yes
			""";

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
	void codexModelsComeWithTheirOwnEffortsAndHiddenOnesAreLeftOut() throws IOException {
		var models = ModelCatalogs.parseCodex(CODEX);

		assertEquals(List.of("gpt-6-astra", "gpt-5.5"), models.stream().map(model -> model.id()).toList());
		assertEquals(List.of("low", "medium", "ultra"), models.getFirst().efforts());
		assertEquals("low", models.getFirst().defaultEffort());
		assertEquals("GPT-5.5", models.get(1).label());
		assertThrows(IOException.class, () -> ModelCatalogs.parseCodex("{\"nope\":1}"));
	}

	@Test
	void piModelsAreProviderQualifiedAndNonThinkingOnesOnlyTurnThinkingOff() throws IOException {
		var models = ModelCatalogs.parsePi(PI);

		assertEquals(List.of("anthropic/claude-opus-5", "openrouter/amazon/nova-lite-v1",
				"openrouter/~openai/gpt-latest"), models.stream().map(model -> model.id()).toList());
		assertTrue(models.getFirst().efforts().isEmpty(), "unknown per model: the provider's levels apply");
		assertEquals(List.of("off"), models.get(1).efforts());
		assertThrows(IOException.class, () -> ModelCatalogs.parsePi("no table here"));
	}

	@Test
	void eachProviderIsAskedWithItsOwnCommandAndAnswersAreCached() throws Exception {
		var calls = new ArrayList<List<String>>();
		var catalogs = ModelCatalogs.answering(command -> {
			calls.add(command);
			return command.contains("debug") ? CODEX : PI;
		});

		var codex = catalogs.catalog(AgentProvider.CODEX, "").get();
		catalogs.catalog(AgentProvider.CODEX, "").get();
		var pi = catalogs.catalog(AgentProvider.PI, "/opt/pi").get();

		assertEquals(List.of(List.of("codex", "debug", "models"), List.of("/opt/pi", "--list-models")), calls);
		assertTrue(codex.live());
		assertEquals(List.of("low", "xhigh"), codex.effortsFor("gpt-5.5"));
		assertEquals(AgentProvider.CODEX.efforts(), codex.effortsFor("typed-by-hand"));
		assertEquals(3, pi.models().size());

		catalogs.refresh(AgentProvider.CODEX, "").get();
		assertEquals(3, calls.size());
	}

	@Test
	void aCliThatCannotBeAskedFallsBackToTheBuiltInListWithAReason() throws Exception {
		var catalogs = ModelCatalogs.answering(command -> {
			throw new UncheckedIOException(new IOException("exit code 1: not logged in"));
		});

		var codex = catalogs.catalog(AgentProvider.CODEX, "").get();
		var claude = catalogs.catalog(AgentProvider.CLAUDE_CODE, "").get();

		assertFalse(codex.live());
		assertTrue(codex.note().contains("not logged in"), codex.note());
		assertTrue(claude.models().stream().anyMatch(model -> model.id().equals("opus")));
	}

	@Test
	void theFormOffersTheProvidersModelsAndClearsModelAndEffortWhenTheProviderChanges() throws Exception {
		var catalogs = ModelCatalogs.answering(command -> CODEX);
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("codex");
		profile.getHarness().setModel("gpt-5.5");
		profile.getHarness().setEffort("xhigh");

		var forms = new ProfileForm[1];
		onEdt(() -> forms[0] = new ProfileForm(profile, catalogs));
		flushEdt();

		var before = new Profile[1];
		onEdt(() -> before[0] = forms[0].toProfile());
		assertEquals("codex", before[0].getHarness().getProvider());
		assertEquals("gpt-5.5", before[0].getHarness().getModel());
		assertEquals("xhigh", before[0].getHarness().getEffort());

		var after = new Profile[1];
		onEdt(() -> {
			var tabs = findProviderBox(forms[0]);
			tabs.setSelectedItem(AgentProvider.CLAUDE_CODE);
		});
		flushEdt();
		onEdt(() -> after[0] = forms[0].toProfile());
		assertEquals("claude-code", after[0].getHarness().getProvider());
		assertNull(after[0].getHarness().getModel());
		assertNull(after[0].getHarness().getEffort());
	}

	@Test
	void anUnsupportedProviderIsReportedNotDropped() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider("anthropic");
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());

		var read = new Profile[1];
		onEdt(() -> read[0] = new ProfileForm(profile, ModelCatalogs.answering(command -> "")).toProfile());
		assertEquals("anthropic", read[0].getHarness().getProvider());
	}

	@SuppressWarnings("unchecked")
	private static javax.swing.JComboBox<Object> findProviderBox(java.awt.Container root) {
		var found = new AtomicInteger();
		var result = new Object[1];
		walk(root, component -> {
			if (component instanceof javax.swing.JComboBox<?> box && box.getItemCount() > 0
					&& box.getItemAt(1) == AgentProvider.CLAUDE_CODE && found.getAndIncrement() == 0) {
				result[0] = box;
			}
		});
		return (javax.swing.JComboBox<Object>) result[0];
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
