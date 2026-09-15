package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AiProjectScreenPlugin;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ProjectLocks;

/**
 * A project is open in one workspace at a time: two screens - one per workspace - never both
 * build a desktop for it.
 */
class ProjectLocksTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private ProjectCatalog catalog;
	private ProjectLocks locks;
	private final List<String> notices = new ArrayList<>();
	private AiProjectScreenPlugin first;
	private AiProjectScreenPlugin second;

	@BeforeEach
	void setUp() {
		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
		locks = new ProjectLocks();
		first = screen();
		second = screen();
	}

	@AfterEach
	void tearDown() throws Exception {
		onEdt(first::unload);
		onEdt(second::unload);
	}

	private AiProjectScreenPlugin screen() {
		var plugin = new AiProjectScreenPlugin(locks, notices::add);
		plugin.preinit(context);
		plugin.init();
		return plugin;
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	/** Let deferred work - the close request and the notice - run. */
	private static void drainEdt() throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(() -> {
		});
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	private ProjectEntry register(String name) throws IOException {
		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL, "terminal.shell", null);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);
		return entry;
	}

	private static boolean open(AiProjectScreenPlugin plugin, ProjectEntry entry) {
		return plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean());
	}

	@Test
	void aSecondScreenHandsTheWindowBackAndSaysWhyInsteadOfOpeningTheProjectTwice() throws Exception {

		var entry = register("alpha");
		onEdt(() -> assertTrue(open(first, entry)));
		context.bus().clear();

		onEdt(() -> assertTrue(open(second, entry)));
		drainEdt();

		assertEquals("AI Project - alpha", first.getWindowTitle());
		assertNull(second.getCurrentResource());
		assertEquals("AI Project", second.getWindowTitle());

		// No message screen: the host is asked to close this one, and the notice goes over the panels.
		assertFalse(context.bus().of(AiProjectEvents.FULLSCREEN_CLOSE).isEmpty());
		assertEquals(1, notices.size());
		assertTrue(notices.getFirst().contains("already open in another workspace"));

		// It keeps nothing, so this workspace does not try again at the next start.
		var announced = context.bus().of(AiProjectEvents.WORKSPACE_STATE_CHANGED).getLast().payload();
		assertEquals(second.uuid(), announced.get(AiProjectEvents.WORKSPACE_PLUGIN_UUID_KEY));
		assertNull(announced.get(AiProjectEvents.WORKSPACE_STATE_KEY));
	}

	@Test
	void theHoldingWorkspaceIsNamedWhenItIsKnown() throws Exception {

		var entry = register("alpha");

		// A restored screen learns its workspace from the host's restore request.
		var restore = new HashMap<String, Object>();
		restore.put(AiProjectEvents.WORKSPACE_NAME_KEY, "Research");
		restore.put(AiProjectEvents.WORKSPACE_STATE_KEY, AiProjectScreenPlugin.workspaceState(entry.id()));
		first.act(null, AiProjectEvents.WORKSPACE_RESTORE_STATE, List.of(), null, restore, null);
		onEdt(() -> open(first, entry));

		onEdt(() -> open(second, entry));
		drainEdt();

		assertTrue(notices.getFirst().contains("the workspace \"Research\""));
	}

	@Test
	void closingTheProjectLetsAnotherWorkspaceOpenIt() throws Exception {

		var entry = register("alpha");
		onEdt(() -> open(first, entry));
		onEdt(first::closeResource);

		assertTrue(locks.holder(entry.id()).isEmpty());
		onEdt(() -> open(second, entry));
		drainEdt();
		assertEquals("AI Project - alpha", second.getWindowTitle());
		assertTrue(notices.isEmpty());
	}

	@Test
	void switchingToAnotherProjectReleasesTheFirst() throws Exception {

		var alpha = register("alpha");
		var beta = register("beta");
		onEdt(() -> open(first, alpha));
		onEdt(() -> open(first, beta));

		assertTrue(locks.holder(alpha.id()).isEmpty());
		assertEquals(first.uuid(), locks.holder(beta.id()).orElseThrow().ownerId());
	}

	@Test
	void aProjectThatFailsToOpenIsNotLeftLocked() throws Exception {

		var entry = register("alpha");
		Files.delete(catalog.paths(entry).projectFile());

		onEdt(() -> open(first, entry));

		assertTrue(locks.holder(entry.id()).isEmpty());
	}

	@Test
	void aRestoreForAProjectOpenElsewhereIsSkippedSoTheWorkspaceComesBackOnItsPanels() throws Exception {

		var entry = register("alpha");
		onEdt(() -> open(first, entry));

		var restore = new HashMap<String, Object>();
		restore.put(AiProjectEvents.WORKSPACE_STATE_KEY, AiProjectScreenPlugin.workspaceState(entry.id()));
		second.act(null, AiProjectEvents.WORKSPACE_RESTORE_STATE, List.of(), null, restore, null);

		assertEquals(AiProjectEvents.WORKSPACE_RESTORE_SKIP, restore.get(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY));
		assertTrue(notices.isEmpty());
	}

	@Test
	void theMessageNamesTheProjectAndWhereItIs() {
		assertTrue(AiProjectScreenPlugin.alreadyOpenMessage("alpha", null)
				.startsWith("\"alpha\" is already open in another workspace."));
		assertTrue(AiProjectScreenPlugin.alreadyOpenMessage("alpha", "Research")
				.contains("already open in the workspace \"Research\"."));
	}

	@Test
	void onlyTheHolderCanReleaseAndHoldingTwiceIsFine() {

		assertTrue(locks.tryAcquire("p", "a", null).isEmpty());
		assertTrue(locks.tryAcquire("p", "a", "Research").isEmpty());
		assertEquals("Research", locks.holder("p").orElseThrow().workspaceName());

		assertEquals("a", locks.tryAcquire("p", "b", null).orElseThrow().ownerId());
		locks.release("p", "b");
		assertFalse(locks.holder("p").isEmpty());

		locks.release("p", "a");
		assertTrue(locks.holder("p").isEmpty());
		locks.release(null, "a");
	}
}
