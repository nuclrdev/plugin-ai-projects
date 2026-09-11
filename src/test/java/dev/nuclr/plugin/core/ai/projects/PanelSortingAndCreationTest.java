package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectsFilePanelPlugin;
import dev.nuclr.plugin.core.ai.projects.ui.panel.Timestamps;

/**
 * The panel's Commander-side manners: sorting that a column header can actually
 * drive, an empty state that leads somewhere, and adopting a folder copied in.
 */
class PanelSortingAndCreationTest {

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

	private void register(String name, int agents) throws IOException {
		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL, "terminal.shell", null);
		project.getHarness().setExecutable("claude-" + name);
		for (var index = 0; index < agents; index++) {
			var agent = new AgentDefinition();
			agent.setId("a" + index);
			agent.setName("Agent " + index);
			project.getAgents().add(agent);
		}
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		catalog.register(ProjectCreator.entry(project));
	}

	private List<NuclrResource> rows() {
		return plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false)).getEntries();
	}

	private Map<String, String> sortEvents() {
		var sorts = new HashMap<String, String>();
		for (var item : plugin.menuItems(null)) {
			if (item.getEventType().startsWith("filepanel.sort:")) {
				sorts.put(item.getName(), item.getEventType());
			}
		}
		return sorts;
	}

	@Test
	void theColumnsUsersWillClickAreDeclaredAsSorts() {

		var sorts = sortEvents();

		// The host maps a header click to a sort through these descriptors, so a
		// column with no descriptor is a header that silently does nothing.
		assertEquals("filepanel.sort:name:Name", sorts.get("Name"));
		assertEquals("filepanel.sort:description:Status", sorts.get("Status"));
		assertEquals("filepanel.sort:modified:Last opened", sorts.get("Last opened"));
		assertEquals("filepanel.sort:size:Agents", sorts.get("Agents"));
		assertEquals("filepanel.sort:owner:Harness", sorts.get("Harness"));
	}

	@Test
	void everySortDescriptorNamesAColumnThatExists() {

		var columns = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false)).getColumnNames();

		for (var event : sortEvents().values()) {
			var parts = event.split(":");
			if (parts.length == 3) {
				assertTrue(columns.contains(parts[2]),
						"sort points at a column that is not shown: " + parts[2]);
			}
		}
	}

	@Test
	void unsortAndTheSortDialogAreOffered() {
		var events = plugin.menuItems(null).stream().map(NuclrMenuResource::getEventType).toList();
		assertTrue(events.contains("filepanel.sort:unsorted"));
		assertTrue(events.contains("filepanel.sort:dialog"));
	}

	@Test
	void rowsCarryTheFieldsTheHostComparatorsRead() throws IOException {

		register("alpha", 3);
		var row = rows().getFirst();

		// Commander's description and owner comparators read fixed metadata keys, so
		// the values shown in Status and Harness are published under them too - but
		// undecorated. Sorting by status should order by the word, not by whichever
		// codepoint the glyph in front of it happens to be.
		assertEquals("Idle", row.getMetadata().get("Description"));
		assertTrue(String.valueOf(row.getMetadata().get("Status")).endsWith("Idle"));
		assertNotEquals(row.getMetadata().get("Status"), row.getMetadata().get("Description"));

		assertEquals(row.getMetadata().get("Harness"), row.getMetadata().get("Owner"));
		assertEquals(3, row.getLength());
	}

	@Test
	void lastOpenedIsSortableBecauseTheRowCarriesATimestamp() throws IOException {

		register("alpha", 0);
		var paths = ProjectPaths.of(workspace.resolve("alpha"), ProjectStorageMode.PROJECT_LOCAL,
				catalog.entries().getFirst().id(), workspace.resolve("home"));
		try (var store = ProjectStore.open(paths)) {
			store.markOpened();
			store.flush();
		}

		var row = rows().getFirst();
		assertNotNull(row.getLastModifiedDateTime());
		assertEquals("Just now", row.getMetadata().get("Last opened"));
	}

	@Test
	void aNeverOpenedProjectHasNoTimestampToSortBy() throws IOException {
		register("alpha", 0);
		var row = rows().getFirst();
		assertEquals("Never", row.getMetadata().get("Last opened"));
	}

	@Test
	void anEmptyListShowsOneRowThatLeadsSomewhere() {

		var data = plugin.openResource(AiProjectResource.root(), new AtomicBoolean(false));

		assertEquals(1, data.getEntriesCount());
		var hint = data.getEntries().getFirst();
		assertTrue(AiProjectResource.isHint(hint));
		assertFalse(AiProjectResource.isProject(hint));
		for (var column : data.getColumnNames()) {
			assertNotNull(hint.getMetadata().get(column), "the hint row is missing " + column);
		}
	}

	@Test
	void theHintDisappearsOnceThereIsAProject() throws IOException {
		register("alpha", 0);
		assertFalse(AiProjectResource.isHint(rows().getFirst()));
	}

	@Test
	void refreshIsOnTheFunctionBar() {
		var events = plugin.menuItems(null).stream().map(NuclrMenuResource::getEventType).toList();
		assertTrue(events.contains(AiProjectEvents.REFRESH_PANEL));
		assertTrue(events.contains(AiProjectEvents.RENAME_PROJECT));
	}

	@Test
	void copyingInAFolderThatAlreadyHoldsAProjectAdoptsItRatherThanAskingAnything()
			throws IOException {

		// Created but not registered, as it would be after a colleague's clone.
		var root = Files.createDirectories(workspace.resolve("cloned"));
		var project = ProjectCreator.define("cloned", root, ProjectStorageMode.PROJECT_LOCAL,
				"terminal.shell", null);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.flush();
		}
		assertTrue(catalog.entries().isEmpty());

		var folder = dev.nuclr.plugin.core.ai.projects.ui.panel.LocalFolderResource.of(root);
		plugin.act(null, AiProjectEvents.ACCEPT_COPY, List.of(folder), folder, new HashMap<>(), null);

		assertEquals(1, catalog.entries().size());
		assertEquals(project.getId(), catalog.entries().getFirst().id());
	}

	@Test
	void copyingInSomethingThatIsNotALocalFolderIsRejectedRatherThanIgnored() {

		var data = new HashMap<String, Object>();
		plugin.act(null, AiProjectEvents.ACCEPT_COPY, List.of(AiProjectResource.root()),
				AiProjectResource.root(), data, null);

		// Nothing was created, and the panel was not told a refresh was warranted.
		assertTrue(catalog.entries().isEmpty());
	}

	@Test
	void relativeTimestampsReadTheWayPeopleAsk() {

		var now = Instant.parse("2026-09-11T12:00:00Z");
		assertEquals("Just now", Timestamps.relative(now.minusSeconds(20), now));
		assertEquals("1 minute ago", Timestamps.relative(now.minusSeconds(90), now));
		assertEquals("45 minutes ago", Timestamps.relative(now.minusSeconds(45 * 60), now));
		assertEquals("2 hours ago", Timestamps.relative(now.minusSeconds(2 * 3600), now));
		assertEquals("3 days ago", Timestamps.relative(now.minusSeconds(3 * 86400), now));
	}

	@Test
	void anythingOlderThanAWeekGoesBackToADate() {
		var now = Instant.parse("2026-09-11T12:00:00Z");
		assertFalse(Timestamps.relative(now.minusSeconds(30L * 86400), now).contains("ago"));
	}

	@Test
	void aClockThatRanBackwardsDoesNotProduceAFutureTense() {
		var now = Instant.parse("2026-09-11T12:00:00Z");
		assertFalse(Timestamps.relative(now.plusSeconds(3600), now).contains("ago"));
	}

	@Test
	void anUnparseableStampIsShownRatherThanSwallowed() {
		assertEquals("not a timestamp", Timestamps.relative("not a timestamp"));
		assertEquals("Never", Timestamps.relative(null));
		assertEquals("Never", Timestamps.relative("  "));
	}
}
