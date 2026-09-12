package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;

/** The list of known projects, and how it survives being written badly. */
class ProjectCatalogTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private ProjectCatalog catalog;

	@BeforeEach
	void setUp() {
		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
	}

	private ProjectEntry create(String name) throws IOException {
		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL, "terminal.shell", null);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.flush();
		}
		return ProjectCreator.entry(project);
	}

	@Test
	void anEmptyCatalogueListsNothing() {
		assertTrue(catalog.entries().isEmpty());
	}

	@Test
	void registeringKeepsInsertionOrder() throws IOException {
		catalog.register(create("alpha"));
		catalog.register(create("beta"));
		assertEquals(List.of("alpha", "beta"), catalog.entries().stream().map(ProjectEntry::name).toList());
	}

	@Test
	void registeringTheSameIdTwiceUpdatesRatherThanDuplicates() throws IOException {

		var entry = create("alpha");
		catalog.register(entry);
		catalog.register(new ProjectEntry(entry.id(), "renamed", entry.root(), entry.storageMode()));

		assertEquals(1, catalog.entries().size());
		assertEquals("renamed", catalog.entries().getFirst().name());
	}

	@Test
	void forgettingRemovesTheEntryButNotTheFiles() throws IOException {

		var entry = create("alpha");
		catalog.register(entry);
		var definition = catalog.paths(entry).projectFile();

		catalog.forget(entry.id());

		assertTrue(catalog.entries().isEmpty());
		assertTrue(Files.isRegularFile(definition), "forgetting must not delete the project");
	}

	@Test
	void forgettingSomethingUnknownIsHarmless() throws IOException {
		catalog.register(create("alpha"));
		catalog.forget("no-such-project");
		assertEquals(1, catalog.entries().size());
	}

	@Test
	void rowsThatAreNotUsableAreSkippedRatherThanEmptyingTheList() throws IOException {

		var entry = create("alpha");
		var rows = new ArrayList<Object>();
		rows.add("a bare string");
		rows.add(Map.of("name", "no id, no root"));
		rows.add(Map.of("id", "has-id-but-no-root"));
		rows.add(entry.toMap());
		context.settings().set(ProjectCatalog.NAMESPACE, "projects", rows);

		assertEquals(1, catalog.entries().size());
		assertEquals(entry.id(), catalog.entries().getFirst().id());
	}

	@Test
	void anUnrecognisedStorageModeFallsBackToProjectLocal() {

		context.settings().set(ProjectCatalog.NAMESPACE, "projects", List.of(
				Map.of("id", "x", "root", workspace.toString(), "storageMode", "SOMETHING_ELSE")));

		assertEquals(ProjectStorageMode.PROJECT_LOCAL, catalog.entries().getFirst().storageMode());
	}

	@Test
	void aMissingNameFallsBackToTheId() {
		context.settings().set(ProjectCatalog.NAMESPACE, "projects",
				List.of(Map.of("id", "x", "root", workspace.toString())));
		assertEquals("x", catalog.entries().getFirst().name());
	}

	@Test
	void aFolderCarryingADefinitionCanBeAdopted() throws IOException {

		var entry = create("cloned");

		var adopted = catalog.adoptIfProjectFolder(entry.rootPath());

		assertTrue(adopted.isPresent());
		assertEquals(entry.id(), adopted.orElseThrow().id());
		assertEquals(1, catalog.entries().size());
	}

	@Test
	void anOrdinaryFolderIsNotAdopted() throws IOException {
		var folder = Files.createDirectories(workspace.resolve("just-a-folder"));
		assertTrue(catalog.adoptIfProjectFolder(folder).isEmpty());
		assertTrue(catalog.entries().isEmpty());
	}

	@Test
	void adoptingSomethingThatIsNotAFolderIsHarmless() {
		assertTrue(catalog.adoptIfProjectFolder(null).isEmpty());
		assertTrue(catalog.adoptIfProjectFolder(workspace.resolve("nowhere")).isEmpty());
	}

	@Test
	void adoptingADamagedDefinitionFailsQuietly() throws IOException {

		var entry = create("broken");
		Files.writeString(catalog.paths(entry).projectFile(), "{ not json");

		assertTrue(catalog.adoptIfProjectFolder(entry.rootPath()).isEmpty());
	}

	@Test
	void peekReadsTheDefinitionFreshRatherThanTheCachedName() throws IOException {

		var entry = create("alpha");
		catalog.register(new ProjectEntry(entry.id(), "stale cached name", entry.root(), entry.storageMode()));

		assertEquals("alpha", catalog.peek(entry).orElseThrow().displayName());
	}

	@Test
	void peekOnAProjectWhoseFilesAreGoneReturnsNothing() throws IOException {

		var entry = create("alpha");
		Files.delete(catalog.paths(entry).projectFile());

		assertTrue(catalog.peek(entry).isEmpty());
	}

	@Test
	void aProjectNeverOpenedHasNoLastOpenedStamp() throws IOException {
		assertFalse(catalog.lastOpenedAt(create("alpha")) != null);
	}

	@Test
	void aRowWhoseRootIsNotAPathOnThisPlatformIsDroppedRatherThanBreakingTheList() throws IOException {

		var good = create("alpha");
		catalog.register(good);

		// A root that cannot become a Path - here a NUL byte, but in practice a Windows
		// path read on Linux - used to throw out of the middle of the panel's listing.
		var rows = new ArrayList<Map<String, Object>>();
		rows.add(Map.of("id", "broken", "name", "broken", "root", "a\0b",
				"storageMode", ProjectStorageMode.PROJECT_LOCAL.name()));
		rows.add(good.toMap());
		context.settings().set(ProjectCatalog.NAMESPACE, "projects", rows);

		assertEquals(List.of(good.id()), catalog.entries().stream().map(ProjectEntry::id).toList());
		assertTrue(catalog.find("broken").isEmpty());
	}

	@Test
	void commanderPrivatePathsLiveUnderTheCommanderHome() {

		var entry = new ProjectEntry("id", "name", workspace.toString(), ProjectStorageMode.COMMANDER_PRIVATE);

		assertTrue(catalog.paths(entry).metadataDirectory().startsWith(workspace.resolve("home")));
	}
}
