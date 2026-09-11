package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectsFilePanelPlugin;

/** The panel that lists projects: what it shows, what it opens, and what it ignores. */
class AiProjectsFilePanelPluginTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private AiProjectsFilePanelPlugin plugin;
	private ProjectCatalog catalog;

	@BeforeEach
	void setUp() {
		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
		plugin = new AiProjectsFilePanelPlugin();
		plugin.preinit(context);
		plugin.init();
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
	}

	private AiProject registerProject(String name, int agents) throws IOException {

		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL, "terminal.shell", null);
		project.getHarness().setExecutable("claude");
		project.getHarness().setModel("some-model");
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
		catalog.register(ProjectCreator.entry(project));
		return project;
	}

	/**
	 * The status word without its glyph.
	 *
	 * <p>Read from the mirrored sort key rather than by stripping the visible cell:
	 * the two are meant to differ, and asserting on the undecorated one is what
	 * keeps these tests independent of which glyph a font happens to support.
	 */
	private static String statusWord(NuclrResource row) {
		return String.valueOf(row.getMetadata().get("Description"));
	}

	private static NuclrResource rowNamed(List<NuclrResource> rows, String name) {
		return rows.stream().filter(row -> name.equals(row.getName())).findFirst().orElseThrow();
	}

	@Test
	void supportsOnlyItsOwnResources() {
		assertTrue(plugin.supports(AiProjectResource.root()));
		assertFalse(plugin.supports(null));
		assertFalse(plugin.supports(new NuclrResource(null) {
		}));
	}

	@Test
	void theRootListsEveryRegisteredProject() throws IOException {

		registerProject("alpha", 2);
		registerProject("beta", 0);

		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));

		assertNotNull(data);
		assertEquals(2, data.getEntriesCount());
		assertEquals(List.of("Name", "Status", "Agents", "Harness", "Model", "Storage", "Last opened", "Root"),
				data.getColumnNames());
	}

	@Test
	void everyDeclaredColumnHasAValueOnEveryRow() throws IOException {

		registerProject("alpha", 1);
		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));

		for (var row : data.getEntries()) {
			for (var column : data.getColumnNames()) {
				assertNotNull(row.getMetadata().get(column), "row is missing the column " + column);
			}
		}
	}

	@Test
	void rowsReportTheHarnessModelAndAgentCountFromTheDefinition() throws IOException {

		registerProject("alpha", 3);
		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));
		var row = rowNamed(data.getEntries(), "alpha");

		assertEquals("claude", row.getMetadata().get("Harness"));
		assertEquals("some-model", row.getMetadata().get("Model"));
		assertEquals("3", row.getMetadata().get("Agents"));
		assertEquals("Idle", statusWord(row));
		assertTrue(String.valueOf(row.getMetadata().get("Status")).endsWith("Idle"));
		assertEquals("Never", row.getMetadata().get("Last opened"));
	}

	@Test
	void aProjectWhoseFolderIsGoneIsReportedRatherThanHidden() throws IOException {

		var project = registerProject("gone", 0);
		deleteRecursively(Path.of(project.getRoot()));

		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));
		var row = data.getEntries().getFirst();

		assertEquals("Definition missing", statusWord(row));
		assertFalse(row.isReadable());
	}

	@Test
	void aProjectRowIsNotNavigable() throws IOException {

		registerProject("alpha", 0);
		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));
		var row = data.getEntries().getFirst();

		assertFalse(row.isFolder());
		assertNull(row.getPath());
		assertNull(plugin.openResource(row, new AtomicBoolean(false)));
	}

	@Test
	void cancellationStopsTheListingWithoutPublishingAPartialOne() throws IOException {
		registerProject("alpha", 0);
		assertNull(plugin.openResource(AiProjectResource.root(), new AtomicBoolean(true)));
	}

	@Test
	void streamingPublishesColumnsBeforeEntries() throws IOException {

		registerProject("alpha", 0);
		registerProject("beta", 0);
		var order = new java.util.ArrayList<String>();

		plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false),
				new dev.nuclr.platform.plugin.FilePanelNuclrPlugin.EntrySink() {

					@Override
					public void columns(List<String> columnNames) {
						order.add("columns");
					}

					@Override
					public void add(NuclrResource entry) {
						order.add("entry");
					}
				});

		assertEquals(List.of("columns", "entry", "entry"), order);
	}

	@Test
	void openingAProjectAsksTheHostForTheFullscreenScreen() throws IOException {

		registerProject("alpha", 0);
		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));
		var row = data.getEntries().getFirst();
		context.bus().clear();

		plugin.act(null, AiProjectEvents.PATH_OPENED, List.of(row), row, new HashMap<>(), null);

		var emitted = context.bus().of(AiProjectEvents.MAIN_PANEL_EDIT);
		assertEquals(1, emitted.size());
		assertEquals(row, emitted.getFirst().payload().get("resource"));
	}

	@Test
	void openingTheListRootItselfOpensNoScreen() {

		context.bus().clear();
		plugin.act(null, AiProjectEvents.PATH_OPENED, List.of(), AiProjectResource.root(), new HashMap<>(), null);
		assertTrue(context.bus().of(AiProjectEvents.MAIN_PANEL_EDIT).isEmpty());
	}

	@Test
	void anActivityReportChangesWhatTheStatusColumnSays() throws IOException {

		var project = registerProject("alpha", 2);
		var payload = new HashMap<String, Object>();
		payload.put(AiProjectEvents.ACTIVITY_PROJECT_ID, project.getId());
		payload.put(AiProjectEvents.ACTIVITY_RUNNING, 1);
		payload.put(AiProjectEvents.ACTIVITY_WAITING, 1);
		payload.put(AiProjectEvents.ACTIVITY_OPEN, true);

		plugin.handleMessage(null, AiProjectEvents.ACTIVITY, payload, null);

		var row = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false))
				.getEntries().getFirst();
		assertEquals("Waiting", statusWord(row));
		assertEquals("2 (1 running, 1 waiting)", row.getMetadata().get("Agents"));
	}

	@Test
	void aClosingDesktopClearsTheCountsRatherThanLeavingThemStale() throws IOException {

		var project = registerProject("alpha", 2);
		var running = new HashMap<String, Object>();
		running.put(AiProjectEvents.ACTIVITY_PROJECT_ID, project.getId());
		running.put(AiProjectEvents.ACTIVITY_RUNNING, 2);
		running.put(AiProjectEvents.ACTIVITY_OPEN, true);
		plugin.handleMessage(null, AiProjectEvents.ACTIVITY, running, null);

		var closing = new HashMap<>(running);
		closing.put(AiProjectEvents.ACTIVITY_OPEN, false);
		closing.put(AiProjectEvents.ACTIVITY_RUNNING, 0);
		plugin.handleMessage(null, AiProjectEvents.ACTIVITY, closing, null);

		var row = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false))
				.getEntries().getFirst();
		assertEquals("Idle", statusWord(row));
		assertEquals("2", row.getMetadata().get("Agents"));
	}

	@Test
	void onlyItsOwnEventsAreClaimed() {
		assertTrue(plugin.isMessageSupported(AiProjectEvents.ACTIVITY));
		assertTrue(plugin.isMessageSupported(AiProjectEvents.CATALOG_CHANGED));
		assertFalse(plugin.isMessageSupported("filepanel.copy"));
	}

	@Test
	void anActivityReportWithNoProjectIdIsIgnored() {
		plugin.handleMessage(null, AiProjectEvents.ACTIVITY, new HashMap<>(), null);
		plugin.handleMessage(null, AiProjectEvents.ACTIVITY, null, null);
	}

	@Test
	void unloadUnsubscribesFromTheBus() {
		assertEquals(1, context.bus().listenerCount());
		plugin.unload();
		assertEquals(0, context.bus().listenerCount());
	}

	@Test
	void eachPanelInstanceHasItsOwnUuid() {
		var second = new AiProjectsFilePanelPlugin();
		second.preinit(context);
		assertNotEquals(plugin.uuid(), second.uuid());
	}

	@Test
	void theDriveMenuOffersTheProjectList() {
		var holder = plugin.getPluginMenuItems();
		assertEquals(1, holder.getMenuItems().size());
		assertEquals(AiProjectResource.ROOT_UUID, holder.getMenuItems().getFirst().getUuid());
	}

	@Test
	void theSelectionSummaryCountsProjectsAndTheirAgents() throws IOException {
		registerProject("alpha", 3);
		registerProject("beta", 0);
		assertEquals("2 projects", plugin.getSelectionSummaryText(List.of()));

		var rows = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false)).getEntries();
		assertEquals("1 project selected, 3 agents",
				plugin.getSelectionSummaryText(List.of(rowNamed(rows, "alpha"))));
		assertEquals("2 projects selected, 3 agents", plugin.getSelectionSummaryText(rows));
	}

	@Test
	void contextMenuEntriesNeedingAProjectAreDisabledOnEmptySpace() {

		var items = plugin.contextMenuItems(null, List.of());
		var open = items.stream().filter(item -> AiProjectEvents.OPEN_PROJECT.equals(item.getActionType()))
				.findFirst().orElseThrow();
		var create = items.stream().filter(item -> AiProjectEvents.NEW_PROJECT.equals(item.getActionType()))
				.findFirst().orElseThrow();

		assertFalse(open.isEnabled());
		assertTrue(create.isEnabled());
	}

	@Test
	void deletingProjectMetadataIsMarkedDestructive() {

		var destructive = plugin.contextMenuItems(null, List.of()).stream()
				.filter(item -> AiProjectEvents.DELETE_PROJECT.equals(item.getActionType()))
				.findFirst().orElseThrow();

		assertTrue(destructive.isDestructive());
	}

	@Test
	void anUnknownActionIsIgnoredRatherThanThrowing() {
		plugin.act(null, "some.other.plugin.action", List.of(), null, new HashMap<>(), null);
	}

	@Test
	void workspaceStateNamesTheProjectThePanelWasShowing() {

		var data = new HashMap<String, Object>();
		plugin.act(null, AiProjectEvents.WORKSPACE_SAVE_STATE, List.of(), null, data, null);

		assertTrue(data.get(AiProjectEvents.WORKSPACE_STATE_KEY) instanceof Map);
		plugin.act(null, AiProjectEvents.WORKSPACE_RESTORE_STATE, List.of(), null, data, null);
	}

	@Test
	void aCatalogueRowThatCannotBeReadIsSkippedRatherThanEmptyingTheList() throws IOException {

		registerProject("alpha", 0);
		var rows = new java.util.ArrayList<Object>();
		rows.add("not a project row");
		rows.add(Map.of("name", "no id or root"));
		for (var entry : catalog.entries()) {
			rows.add(entry.toMap());
		}
		context.settings().set(ProjectCatalog.NAMESPACE, "projects", rows);

		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));
		assertEquals(1, data.getEntriesCount());
	}

	private static void deleteRecursively(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			for (var file : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		}
	}
}
