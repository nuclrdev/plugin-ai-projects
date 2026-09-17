package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AiProjectScreenPlugin;

/**
 * "I just want a terminal here": a plain shell in the project folder, without
 * filling in the agent form.
 *
 * <p>It is still an ordinary agent, so it persists with the desktop and can be
 * renamed or deleted like any other. What is different is only that nothing is
 * asked for.
 */
class TerminalHereTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private ProjectCatalog catalog;
	private AiProjectScreenPlugin plugin;
	private ProjectEntry entry;

	@BeforeEach
	void setUp() throws IOException {

		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
		plugin = new AiProjectScreenPlugin();
		plugin.preinit(context);
		plugin.init();

		var root = Files.createDirectories(workspace.resolve("project"));
		var project = ProjectCreator.define("project", root, ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		entry = ProjectCreator.entry(project);
		catalog.register(entry);
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	private void openProject() throws Exception {
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
	}

	private ProjectStore reopen() throws IOException {
		return ProjectStore.open(ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(),
				workspace.resolve("home")));
	}

	@Test
	void aShellIsOneOfTheWindowKindsAlready() {
		var shell = AgentCli.byKind("terminal.shell").orElseThrow();
		assertTrue(shell.isShell());
		assertNotNull(shell.defaultHarness().getExecutable());
	}

	@Test
	void theFunctionBarOffersItWithoutOpeningTheAgentForm() {
		var events = plugin.menuItems(null).stream().map(NuclrMenuResource::getEventType).toList();
		assertTrue(events.contains(AiProjectEvents.SCREEN_NEW_TERMINAL));
	}

	@Test
	void itAddsAShellAgentRootedAtTheProjectFolder() throws Exception {

		openProject();
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
				new HashMap<>(), null));
		onEdt(plugin::closeResource);

		try (var store = reopen()) {
			assertEquals(1, store.project().getAgents().size());
			var agent = store.project().getAgents().getFirst();

			assertEquals("Terminal", agent.displayName());
			assertEquals("terminal.shell", agent.getWindowKind());
			// Blank means the project root, which is what "here" means by default.
			assertNull(agent.getWorkingDirectory());
		}
	}

	@Test
	void aTerminalIsAPlainShellWithNoProfile() throws Exception {

		openProject();
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
				new HashMap<>(), null));
		onEdt(plugin::closeResource);

		try (var store = reopen()) {
			var agent = store.project().getAgents().getFirst();
			assertNull(agent.getProfileId(), "a terminal runs the shell, not an agent CLI");
			assertTrue(AgentCli.byKind(agent.getWindowKind()).orElseThrow().isShell());
		}
	}

	@Test
	void aSecondTerminalDoesNotClashWithTheFirst() throws Exception {

		openProject();
		for (var index = 0; index < 2; index++) {
			onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
					new HashMap<>(), null));
		}
		onEdt(plugin::closeResource);

		try (var store = reopen()) {
			var names = store.project().getAgents().stream().map(agent -> agent.displayName()).toList();
			assertEquals(2, names.size());
			assertEquals(2, names.stream().distinct().count(), names.toString());
		}
	}

	@Test
	void itPersistsLikeAnyOtherAgentAndComesBackWithTheDesktop() throws Exception {

		openProject();
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
				new HashMap<>(), null));
		onEdt(plugin::closeResource);

		// Reopening restores it, because a terminal on a persistent desktop that
		// vanished would be the odd one out.
		context.bus().clear();
		openProject();
		var latest = context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload();
		assertEquals(1, latest.get(AiProjectEvents.ACTIVITY_AGENTS));
		onEdt(plugin::closeResource);
	}

	@Test
	void aTerminalInASubfolderIsRecordedRelativelySoTheDefinitionStaysPortable() throws Exception {

		var module = Files.createDirectories(entry.rootPath().resolve("module"));
		openProject();
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
				new HashMap<>(Map.of(AiProjectEvents.TERMINAL_FOLDER_KEY, module)), null));
		onEdt(plugin::closeResource);

		try (var store = reopen()) {
			var agent = store.project().getAgents().getFirst();
			assertEquals("module", agent.getWorkingDirectory());
			assertEquals("Terminal - module", agent.displayName());
		}
	}

	@Test
	void aFolderOutsideEveryAllowedRootIsAddedToThemRatherThanSilentlyIgnored() throws Exception {

		var outside = Files.createDirectories(workspace.resolve("elsewhere"));
		openProject();
		// Headless, the "add it to the allowed roots?" question answers yes.
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_NEW_TERMINAL, List.of(), null,
				new HashMap<>(Map.of(AiProjectEvents.TERMINAL_FOLDER_KEY, outside)), null));
		onEdt(plugin::closeResource);

		try (var store = reopen()) {
			var roots = dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext.allowedRoots(store.project(),
					store.paths().root());
			assertTrue(roots.contains(outside.toAbsolutePath().normalize().toString()), roots.toString());

			// And because it is allowed, the agent really runs there.
			var agent = store.project().getAgents().getFirst();
			assertEquals(outside, dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment
					.workingDirectory(agent, store.paths().root(), roots));
		}
	}
}
