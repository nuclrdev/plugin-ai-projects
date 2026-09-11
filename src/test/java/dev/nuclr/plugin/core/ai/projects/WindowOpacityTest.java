package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.WindowState;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AiProjectScreenPlugin;

/**
 * Per-window transparency: the clamp that keeps a window findable, and the
 * persistence that brings it back the way it was left.
 */
class WindowOpacityTest {

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
		var project = ProjectCreator.define("project", root, ProjectStorageMode.PROJECT_LOCAL,
				"terminal.shell", null);
		var agent = new AgentDefinition();
		agent.setId("a0");
		agent.setName("Coder");
		agent.setWindowKind("terminal.shell");
		project.getAgents().add(agent);
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

	private ProjectPaths paths() {
		return ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));
	}

	// ------------------------------------------------------------------- clamp

	@Test
	void aWindowStartsFullySolid() {
		assertEquals(WindowState.MAX_OPACITY, new WindowState().safeOpacity());
	}

	@Test
	void aWindowCannotBeMadeInvisible() {
		// A window nobody can find again is not a feature.
		assertEquals(WindowState.MIN_OPACITY, WindowState.clampOpacity(0));
		assertEquals(WindowState.MIN_OPACITY, WindowState.clampOpacity(-40));
		assertTrue(WindowState.MIN_OPACITY > 0);
	}

	@Test
	void aWindowCannotBeMadeMoreThanSolid() {
		assertEquals(WindowState.MAX_OPACITY, WindowState.clampOpacity(400));
	}

	@Test
	void aHandEditedFileCannotProduceAnInvisibleWindow() {
		var state = new WindowState();
		state.setOpacity(0);
		assertEquals(WindowState.MIN_OPACITY, state.safeOpacity());
	}

	@Test
	void opacityRoundTripsThroughJson() throws IOException {
		var state = new WindowState();
		state.setAgentId("a0");
		state.setOpacity(70);
		assertEquals(70, Json.fromJson(Json.toJson(state), WindowState.class).safeOpacity());
	}

	// ------------------------------------------------------------- persistence

	@Test
	void aWindowRemembersHowSolidItWasLeft() throws Exception {

		var paths = paths();
		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("a0").setOpacity(65);
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
		onEdt(plugin::closeResource);

		try (var store = ProjectStore.open(paths)) {
			assertEquals(65, store.desktop().window("a0").orElseThrow().safeOpacity());
		}
	}

	@Test
	void openingAndClosingDoesNotQuietlyResetIt() throws Exception {

		var paths = paths();
		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("a0").setOpacity(50);
			store.markDesktopDirty();
			store.flush();
		}

		// Twice, because a value that survives one cycle but not two is the usual
		// shape of a restore that writes before it reads.
		for (var round = 0; round < 2; round++) {
			onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
			onEdt(plugin::closeResource);
		}

		try (var store = ProjectStore.open(paths)) {
			assertEquals(50, store.desktop().window("a0").orElseThrow().safeOpacity());
		}
	}

	@Test
	void aSolidWindowStaysOpaqueSoNothingBehindItIsRepaintedNeedlessly() throws Exception {

		var paths = paths();
		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("a0").setOpacity(WindowState.MAX_OPACITY);
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
		assertTrue(frameOpacityOnScreen() == WindowState.MAX_OPACITY);
		assertTrue(frameIsOpaque(), "a fully solid frame should stay opaque");
		onEdt(plugin::closeResource);
	}

	@Test
	void aTranslucentWindowStopsBeingOpaqueSoTheBackgroundShowsThrough() throws Exception {

		var paths = paths();
		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("a0").setOpacity(60);
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));

		// Without this the repaint manager assumes the frame covers its bounds, never
		// repaints the desktop beneath, and the translucency smears.
		assertEquals(60, frameOpacityOnScreen());
		assertFalse(frameIsOpaque(), "a translucent frame must not be opaque");
		onEdt(plugin::closeResource);
	}

	/** The live frame's opacity, found through the component tree. */
	private int frameOpacityOnScreen() {
		var frame = findFrame();
		return frame == null ? -1 : frame.frameOpacity();
	}

	private boolean frameIsOpaque() {
		var frame = findFrame();
		return frame != null && frame.isOpaque();
	}

	private dev.nuclr.plugin.core.ai.projects.ui.screen.AgentFrame findFrame() {
		return find(plugin.panel());
	}

	private static dev.nuclr.plugin.core.ai.projects.ui.screen.AgentFrame find(java.awt.Container container) {
		for (var child : container.getComponents()) {
			if (child instanceof dev.nuclr.plugin.core.ai.projects.ui.screen.AgentFrame frame) {
				return frame;
			}
			if (child instanceof java.awt.Container nested) {
				var found = find(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}
}
