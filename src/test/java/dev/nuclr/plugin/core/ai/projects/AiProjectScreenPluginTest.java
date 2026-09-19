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

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
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
 * The fullscreen desktop: what it opens, what it restores, and what it reports.
 *
 * <p>Swing work runs through {@link SwingUtilities#invokeAndWait}, because the
 * plugin is written to the host's contract that these calls arrive on the event
 * dispatch thread.
 */
class AiProjectScreenPluginTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private ProjectCatalog catalog;
	private AiProjectScreenPlugin plugin;

	@BeforeEach
	void setUp() {
		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
		plugin = new AiProjectScreenPlugin();
		plugin.preinit(context);
		plugin.init();
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	/**
	 * Let queued {@code invokeLater} work run. Status changes reach the desktop
	 * that way, so a test that asserts on them has to wait for the queue rather
	 * than for the call that caused them.
	 */
	private static void drainEdt() throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(() -> {
		});
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	/**
	 * Wait for the desktop's coalesced live refresh to land.
	 *
	 * <p>Status changes no longer redraw the sidebar and report to the file panel one
	 * at a time; they are gathered for a moment first, so a burst costs one refresh.
	 * A test asserting on what the panel was told therefore has to wait for that
	 * timer rather than only for the event queue.
	 */
	private static void awaitLiveRefresh(java.util.function.BooleanSupplier landed)
			throws InterruptedException, InvocationTargetException {

		var deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			drainEdt();
			if (landed.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		drainEdt();
	}

	private ProjectEntry register(String name, int agents) throws IOException {

		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL);
		for (var index = 0; index < agents; index++) {
			var agent = new AgentDefinition();
			agent.setId("a" + index);
			agent.setName("Agent " + index);
			agent.setWindowKind("terminal.shell");
			project.getAgents().add(agent);
		}
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);
		return entry;
	}

	private NuclrResource resourceFor(ProjectEntry entry) {
		return AiProjectResource.forProject(entry);
	}

	@Test
	void supportsOnlyProjectRows() {
		assertTrue(plugin.supports(AiProjectResource.forProject(
				new ProjectEntry("id", "name", workspace.toString(), ProjectStorageMode.PROJECT_LOCAL))));
		assertFalse(plugin.supports(AiProjectResource.root()));
		assertFalse(plugin.supports(null));
	}

	@Test
	void aProjectThatIsNoLongerInTheListSaysSoRatherThanFailing()
			throws InterruptedException, InvocationTargetException {

		var stranger = AiProjectResource.forProject(
				new ProjectEntry("gone", "Gone", workspace.toString(), ProjectStorageMode.PROJECT_LOCAL));

		onEdt(() -> assertTrue(plugin.openResource(stranger, new java.util.concurrent.atomic.AtomicBoolean())));
		assertNull(plugin.getCurrentResource());
		onEdt(plugin::unload);
	}

	@Test
	void openingAProjectRestoresAWindowPerAgentAndReportsActivity() throws Exception {

		var entry = register("alpha", 2);
		context.bus().clear();

		onEdt(() -> assertTrue(plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean())));

		assertNotNull(plugin.getCurrentResource());
		assertEquals("AI Project - alpha", plugin.getWindowTitle());

		var reports = context.bus().of(AiProjectEvents.ACTIVITY);
		assertFalse(reports.isEmpty());
		var latest = reports.getLast().payload();
		assertEquals(2, latest.get(AiProjectEvents.ACTIVITY_AGENTS));
		assertEquals(0, latest.get(AiProjectEvents.ACTIVITY_RUNNING));
		assertEquals(Boolean.TRUE, latest.get(AiProjectEvents.ACTIVITY_OPEN));

		onEdt(plugin::unload);
	}

	@Test
	void closingTheProjectSendsAFinalReportSoThePanelDoesNotKeepStaleCounts() throws Exception {

		var entry = register("alpha", 1);
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		context.bus().clear();
		onEdt(plugin::closeResource);

		var reports = context.bus().of(AiProjectEvents.ACTIVITY);
		assertFalse(reports.isEmpty());
		assertEquals(Boolean.FALSE, reports.getLast().payload().get(AiProjectEvents.ACTIVITY_OPEN));
	}

	@Test
	void openingWritesTheLastOpenedStampAndTheWindowLayout() throws Exception {

		var entry = register("alpha", 1);
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(plugin::closeResource);

		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));
		try (var store = ProjectStore.open(paths)) {
			assertNotNull(store.desktop().getLastOpenedAt());
			assertTrue(store.desktop().window("a0").isPresent());
			assertTrue(store.desktop().window("a0").orElseThrow().isOpen());
		}
	}

	@Test
	void reopeningKeepsTheStoredGeometry() throws Exception {

		var entry = register("alpha", 1);
		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));

		try (var store = ProjectStore.open(paths)) {
			var window = store.desktop().windowOrCreate("a0");
			window.setX(210);
			window.setY(140);
			window.setWidth(640);
			window.setHeight(380);
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(plugin::closeResource);

		try (var store = ProjectStore.open(paths)) {
			var window = store.desktop().window("a0").orElseThrow();
			assertEquals(210, window.getX());
			assertEquals(640, window.getWidth());
		}
	}

	@Test
	void anAgentWhoseWindowWasClosedStaysClosedOnReopen() throws Exception {

		var entry = register("alpha", 2);
		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));

		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("a1").setOpen(false);
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));

		var latest = context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload();
		assertEquals(2, latest.get(AiProjectEvents.ACTIVITY_AGENTS));

		onEdt(plugin::closeResource);
		try (var store = ProjectStore.open(paths)) {
			assertFalse(store.desktop().window("a1").orElseThrow().isOpen());
			assertTrue(store.desktop().window("a0").orElseThrow().isOpen());
		}
	}

	@Test
	void windowStateForAnAgentThatNoLongerExistsIsDiscarded() throws Exception {

		var entry = register("alpha", 1);
		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));

		try (var store = ProjectStore.open(paths)) {
			store.desktop().windowOrCreate("deleted-long-ago");
			store.markDesktopDirty();
			store.flush();
		}

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(plugin::closeResource);

		try (var store = ProjectStore.open(paths)) {
			assertTrue(store.desktop().window("deleted-long-ago").isEmpty());
		}
	}

	@Test
	void projectWideActionsAreSafeBeforeAProjectIsOpen() {
		for (var action : List.of(AiProjectEvents.SCREEN_START_ALL, AiProjectEvents.SCREEN_STOP_ALL,
				AiProjectEvents.SCREEN_TILE, AiProjectEvents.SCREEN_CASCADE,
				AiProjectEvents.SCREEN_SAVE_LAYOUT, AiProjectEvents.SCREEN_RESET_LAYOUT)) {
			plugin.act(null, action, List.of(), null, new HashMap<>(), null);
		}
	}

	@Test
	void tileAndCascadeAndSaveLayoutRunAgainstAnOpenProject() throws Exception {

		var entry = register("alpha", 3);
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));

		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_TILE, List.of(), null, new HashMap<>(), null));
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_CASCADE, List.of(), null, new HashMap<>(), null));
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_SAVE_LAYOUT, List.of(), null, new HashMap<>(), null));
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_RESET_LAYOUT, List.of(), null, new HashMap<>(), null));

		onEdt(plugin::closeResource);
	}


	/** A profile saved in a project, as "project:<id>" for an agent to start from. */
	static String projectProfile(dev.nuclr.plugin.core.ai.projects.store.ProjectStore store, String name,
			String provider, String executable) throws IOException {
		var profile = new dev.nuclr.plugin.core.ai.projects.profile.Profile();
		profile.setName(name);
		profile.getHarness().setProvider(provider);
		profile.getHarness().setExecutable(executable);
		return "project:" + new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(store.paths().profilesDirectory())
				.create(profile).getId();
	}

	@Test
	void anAgentOnTheDesktopRunsWhatItsProfileConfigures() throws Exception {

		// The profile's command is a small Java program that prints its arguments. Pi's default
		// access adds no flags, so what runs is exactly the profile's executable and startup
		// arguments - and its output in the transcript proves the profile was what started.
		var javaExecutable = ProcessHandle.current().info().command().orElseThrow();
		var classes = Path.of(WindowsCommandLineTest.Echo.class.getProtectionDomain().getCodeSource().getLocation()
				.toURI()).toString();
		var root = Files.createDirectories(workspace.resolve("profiled"));
		var project = ProjectCreator.define("profiled", root, ProjectStorageMode.PROJECT_LOCAL);
		var agent = new AgentDefinition();
		agent.setId("a0");
		agent.setName("Agent");
		// A window kind whose own command would not run: only the profile can start this agent.
		agent.setWindowKind("terminal.codex");
		project.getAgents().add(agent);
		final Path transcript;
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			var profile = new dev.nuclr.plugin.core.ai.projects.profile.Profile();
			profile.setName("Echo");
			profile.getHarness().setProvider("pi");
			profile.getHarness().setExecutable(javaExecutable);
			profile.getHarness().setStartupArgs(List.of("-cp", classes, WindowsCommandLineTest.Echo.class.getName(),
					"from-the-profile"));
			agent.setProfileId("project:" + new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(
					store.paths().profilesDirectory()).create(profile).getId());
			transcript = store.paths().transcriptFile("a0");
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_START_ALL, List.of(), null, new HashMap<>(), null));

		var printed = "ARG[" + String.join(",", "from-the-profile".chars().mapToObj(Integer::toString).toList()) + "]";
		var deadline = System.currentTimeMillis() + 30_000;
		var text = "";
		while (System.currentTimeMillis() < deadline) {
			drainEdt();
			text = Files.exists(transcript) ? Files.readString(transcript) : "";
			if (text.contains(printed)) {
				break;
			}
			Thread.sleep(100);
		}
		onEdt(plugin::closeResource);

		assertTrue(text.contains(printed), "the profile's command did not run: " + text);
		assertTrue(text.contains("Started from a profile for Pi"), text);
	}

	@Test
	void startAllOnAgentsWithNoInstalledCliMarksThemFailedRatherThanPretending() throws Exception {

		var root = Files.createDirectories(workspace.resolve("beta"));
		var project = ProjectCreator.define("beta", root, ProjectStorageMode.PROJECT_LOCAL);
		var agent = new AgentDefinition();
		agent.setId("a0");
		agent.setName("Agent");
		agent.setWindowKind("terminal.codex");
		project.getAgents().add(agent);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			agent.setProfileId(projectProfile(store, "Missing CLI", "codex", "definitely-not-installed-7c1e"));
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(() -> plugin.act(null, AiProjectEvents.SCREEN_START_ALL, List.of(), null, new HashMap<>(), null));
		awaitLiveRefresh(() -> Integer.valueOf(1).equals(
				context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload()
						.get(AiProjectEvents.ACTIVITY_FAILED)));

		var latest = context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload();
		assertEquals(1, latest.get(AiProjectEvents.ACTIVITY_FAILED));
		assertEquals(0, latest.get(AiProjectEvents.ACTIVITY_RUNNING));

		onEdt(plugin::closeResource);
	}

	@Test
	void theFunctionKeyMenuOffersTheProjectWideCommands() {
		var events = plugin.menuItems(null).stream()
				.map(dev.nuclr.platform.plugin.NuclrMenuResource::getEventType).toList();
		assertTrue(events.contains(AiProjectEvents.SCREEN_NEW_AGENT));
		assertTrue(events.contains(AiProjectEvents.SCREEN_START_ALL));
		assertTrue(events.contains(AiProjectEvents.SCREEN_STOP_ALL));
		// Close goes through the plugin's own action, not the host's, so it can confirm
		// before the agents are stopped.
		assertTrue(events.contains(AiProjectEvents.SCREEN_CLOSE));
		assertFalse(events.contains(AiProjectEvents.FULLSCREEN_CLOSE));

		// Bare F10 is Commander's "quit the application", handled before the function
		// bar sees it, so nothing here may claim it.
		var keys = plugin.menuItems(null).stream()
				.map(dev.nuclr.platform.plugin.NuclrMenuResource::getFunctionKey).toList();
		assertFalse(keys.contains("F10"));
	}

	@Test
	void openingASecondProjectClosesTheFirst() throws Exception {

		var first = register("alpha", 1);
		var second = register("beta", 1);

		onEdt(() -> plugin.openResource(resourceFor(first), new java.util.concurrent.atomic.AtomicBoolean()));
		context.bus().clear();
		onEdt(() -> plugin.openResource(resourceFor(second), new java.util.concurrent.atomic.AtomicBoolean()));

		var closing = context.bus().of(AiProjectEvents.ACTIVITY).stream()
				.filter(emission -> Boolean.FALSE.equals(emission.payload().get(AiProjectEvents.ACTIVITY_OPEN)))
				.toList();
		assertFalse(closing.isEmpty());
		assertEquals(first.id(), closing.getFirst().payload().get(AiProjectEvents.ACTIVITY_PROJECT_ID));
		assertEquals("AI Project - beta", plugin.getWindowTitle());

		onEdt(plugin::unload);
	}

	@Test
	void unloadingTwiceIsSafe() throws Exception {
		var entry = register("alpha", 1);
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(plugin::unload);
		onEdt(plugin::unload);
	}

	@Test
	void theCatalogueNameIsRefreshedFromTheDefinitionOnOpen() throws Exception {

		var entry = register("alpha", 0);
		catalog.register(new ProjectEntry(entry.id(), "a stale cached name", entry.root(), entry.storageMode()));

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));

		assertEquals("alpha", catalog.find(entry.id()).orElseThrow().name());
		onEdt(plugin::closeResource);
	}

	@Test
	void aCancelledOpenDoesNothing() throws Exception {
		var entry = register("alpha", 1);
		onEdt(() -> assertFalse(plugin.openResource(resourceFor(entry),
				new java.util.concurrent.atomic.AtomicBoolean(true))));
		assertNull(plugin.getCurrentResource());
	}

	@Test
	void aProjectWithNoAgentsOpensToAnEmptyDesktop() throws Exception {

		var entry = register("empty", 0);
		onEdt(() -> assertTrue(plugin.openResource(resourceFor(entry),
				new java.util.concurrent.atomic.AtomicBoolean())));

		var latest = context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload();
		assertEquals(0, latest.get(AiProjectEvents.ACTIVITY_AGENTS));
		onEdt(plugin::closeResource);
	}

	@Test
	void anAgentNamingAnUninstalledWindowKindStillOpensTheProject() throws Exception {

		var root = Files.createDirectories(workspace.resolve("gamma"));
		var project = ProjectCreator.define("gamma", root, ProjectStorageMode.PROJECT_LOCAL);
		var agent = new AgentDefinition();
		agent.setId("a0");
		agent.setName("From a colleague");
		agent.setWindowKind("provider.we.do.not.have");
		project.getAgents().add(agent);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);

		onEdt(() -> assertTrue(plugin.openResource(resourceFor(entry),
				new java.util.concurrent.atomic.AtomicBoolean())));

		var latest = context.bus().of(AiProjectEvents.ACTIVITY).getLast().payload();
		assertEquals(1, latest.get(AiProjectEvents.ACTIVITY_AGENTS));
		onEdt(plugin::closeResource);

		// The agent it could not render is still in the definition afterwards.
		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));
		try (var store = ProjectStore.open(paths)) {
			assertEquals(1, store.project().getAgents().size());
			assertEquals("provider.we.do.not.have",
					store.project().getAgents().getFirst().getWindowKind());
		}
	}

	@Test
	void panelIsTheSameComponentEveryTime() {
		assertNotNull(plugin.panel());
		assertEquals(plugin.panel(), plugin.panel());
	}

	@Test
	void aProjectDefinitionIsAnEditorConcernNotAViewerOne() throws Exception {
		// The screen writes desktop.json as soon as it opens, which is why it is
		// declared as an Editor in the manifest rather than a Viewer.
		var entry = register("alpha", 0);
		var paths = ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), workspace.resolve("home"));
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));
		onEdt(plugin::closeResource);
		assertTrue(Files.isRegularFile(paths.desktopFile()));
	}

	@Test
	void aSavedProjectComesBackAsTheRowThatOpensIt() throws Exception {

		var entry = register("alpha", 0);
		var data = new HashMap<String, Object>();
		data.put(AiProjectEvents.WORKSPACE_STATE_KEY, AiProjectScreenPlugin.workspaceState(entry.id()));

		// Commander restores off the event thread, before any desktop exists.
		plugin.act(null, AiProjectEvents.WORKSPACE_RESTORE_STATE, List.of(), null, data, null);

		assertEquals(AiProjectEvents.WORKSPACE_RESTORE_RESTORED, data.get(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY));
		var resource = (NuclrResource) data.get(AiProjectEvents.WORKSPACE_RESTORE_RESOURCE_KEY);
		assertEquals(entry.id(), AiProjectResource.projectId(resource));
		assertTrue(plugin.supports(resource));

		// And the host opens it the ordinary way.
		onEdt(() -> assertTrue(plugin.openResource(resource, new java.util.concurrent.atomic.AtomicBoolean())));
		assertEquals("AI Project - alpha", plugin.getWindowTitle());
		onEdt(plugin::closeResource);
	}

	@Test
	void aProjectThatIsGoneOrStateThatMakesNoSenseIsSkippedNotFailed() {

		var forgotten = new HashMap<String, Object>();
		forgotten.put(AiProjectEvents.WORKSPACE_STATE_KEY, AiProjectScreenPlugin.workspaceState("forgotten"));
		var nothing = new HashMap<String, Object>();
		var nonsense = new HashMap<String, Object>();
		nonsense.put(AiProjectEvents.WORKSPACE_STATE_KEY, "not a state");

		for (var data : List.of(forgotten, nothing, nonsense)) {
			plugin.act(null, AiProjectEvents.WORKSPACE_RESTORE_STATE, List.of(), null, data, null);
			assertEquals(AiProjectEvents.WORKSPACE_RESTORE_SKIP, data.get(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY));
			assertNull(data.get(AiProjectEvents.WORKSPACE_RESTORE_RESOURCE_KEY));
		}
	}

	@Test
	void openingAProjectAnnouncesItSoSavingTheWorkspaceNeverHasToAsk() throws Exception {

		var entry = register("alpha", 0);
		context.bus().clear();

		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));

		var announcements = context.bus().of(AiProjectEvents.WORKSPACE_STATE_CHANGED);
		assertEquals(1, announcements.size());
		var payload = announcements.getFirst().payload();
		assertEquals(plugin.uuid(), payload.get(AiProjectEvents.WORKSPACE_PLUGIN_UUID_KEY));
		assertEquals(AiProjectScreenPlugin.workspaceState(entry.id()), payload.get(AiProjectEvents.WORKSPACE_STATE_KEY));
		assertEquals(AiProjectScreenPlugin.WORKSPACE_STATE_VERSION,
				payload.get(AiProjectEvents.WORKSPACE_STATE_VERSION_KEY));

		onEdt(plugin::closeResource);
	}

	@Test
	void theSaveFallbackNamesTheOpenProjectAndNothingBeforeOneIsOpen() throws Exception {

		var before = new HashMap<String, Object>();
		plugin.act(null, AiProjectEvents.WORKSPACE_SAVE_STATE, List.of(), null, before, null);
		assertFalse(before.containsKey(AiProjectEvents.WORKSPACE_STATE_KEY));

		var entry = register("alpha", 0);
		onEdt(() -> plugin.openResource(resourceFor(entry), new java.util.concurrent.atomic.AtomicBoolean()));

		var data = new HashMap<String, Object>();
		onEdt(() -> plugin.act(null, AiProjectEvents.WORKSPACE_SAVE_STATE, List.of(), null, data, null));

		assertEquals(AiProjectScreenPlugin.workspaceState(entry.id()), data.get(AiProjectEvents.WORKSPACE_STATE_KEY));
		assertEquals(AiProjectScreenPlugin.WORKSPACE_STATE_VERSION, data.get(AiProjectEvents.WORKSPACE_STATE_VERSION_KEY));
		onEdt(plugin::closeResource);
	}
}
