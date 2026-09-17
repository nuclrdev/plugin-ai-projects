package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/** Persistence: what is written, where, and what survives a reopen. */
class ProjectStoreTest {

	@TempDir
	Path root;

	private AiProject definition(ProjectStorageMode mode) {
		return ProjectCreator.define("Demo", root, mode, "terminal.shell", null);
	}

	@Test
	void createWritesTheDefinitionTemplatesAndDocuments() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
			assertTrue(Files.isRegularFile(store.paths().projectFile()));
			assertTrue(Files.isRegularFile(store.paths().instructionsDirectory().resolve("coder.md")));
			assertTrue(Files.isRegularFile(store.paths().skillsDirectory().resolve("example.md")));
			assertEquals(4, store.project().getTemplates().size());
		}
	}

	@Test
	void aProjectLocalLayoutIgnoresRuntimeStateButNotTheCommittableHalf() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var gitignore = Files.readString(store.paths().metadataDirectory().resolve(".gitignore"));

			assertTrue(gitignore.contains("desktop.json"));
			assertTrue(gitignore.contains("sessions/"));
			assertTrue(gitignore.contains("transcripts/"));
			assertFalse(gitignore.lines().anyMatch(line -> line.strip().equals("project.json")));
			assertFalse(gitignore.lines().anyMatch(line -> line.strip().equals("skills/")));
		}
	}

	@Test
	void commanderPrivateStorageWritesNothingInsideTheProjectFolder() throws IOException {

		var home = root.resolve("home");
		var project = definition(ProjectStorageMode.COMMANDER_PRIVATE);

		try (var store = ProjectCreator.create(project, home)) {
			store.flush();
			assertFalse(Files.exists(root.resolve(".nuclr")));
			assertTrue(store.paths().projectFile().startsWith(home));
		}
	}

	@Test
	void desktopStateAndDefinitionRoundTripThroughAReopen() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));

		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var agent = new dev.nuclr.plugin.core.ai.projects.model.AgentDefinition();
			agent.setId("a1");
			agent.setName("Coder");
			agent.setWindowKind("terminal.shell");
			store.project().getAgents().add(agent);
			store.markProjectDirty();

			var window = store.desktop().windowOrCreate("a1");
			window.setX(120);
			window.setY(80);
			window.setWidth(500);
			window.setHeight(400);
			window.setMaximized(true);
			store.markDesktopDirty();
			store.flush();
		}

		try (var reopened = ProjectStore.open(paths)) {
			assertEquals(1, reopened.project().getAgents().size());
			assertEquals("Coder", reopened.project().getAgents().getFirst().displayName());
			var window = reopened.desktop().window("a1").orElseThrow();
			assertEquals(120, window.getX());
			assertEquals(500, window.getWidth());
			assertTrue(window.isMaximized());
		}
	}

	@Test
	void aSessionLeftRunningByAnEarlierRunIsRecognisedAsStale() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));

		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var session = store.session("a1");
			session.setStatus(AgentStatus.RUNNING);
			session.setPid(4242);
			session.setStartedAt(Instant.now());
			session.setCommandLine(List.of("claude", "--model", "x"));
			session.setRuntimeStamp("a-previous-commander-run");
			store.markSessionDirty("a1");
			store.flush();
		}

		try (var reopened = ProjectStore.open(paths)) {
			var session = reopened.session("a1");
			assertEquals(AgentStatus.RUNNING, session.getStatus());
			assertTrue(session.isStale(RuntimeStamp.CURRENT));
			assertEquals("claude --model x", session.displayCommandLine());
		}
	}

	@Test
	void aSessionFromThisRunIsNotStale() throws IOException {
		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var session = store.session("a1");
			session.setStatus(AgentStatus.RUNNING);
			session.setRuntimeStamp(RuntimeStamp.CURRENT);
			assertFalse(session.isStale(RuntimeStamp.CURRENT));
		}
	}

	@Test
	void aDamagedDesktopFileDoesNotStopTheProjectOpening() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		Files.writeString(paths.desktopFile(), "{ this is not json");

		try (var reopened = ProjectStore.open(paths)) {
			assertNotNull(reopened.desktop());
			assertTrue(reopened.desktop().getWindows().isEmpty());
		}
	}

	@Test
	void nullCollectionsInAHandEditedDesktopAreFilledIn() throws IOException {
		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		Files.writeString(paths.desktopFile(),
				"{\"schemaVersion\":1,\"windows\":null,\"expandedSections\":null}");

		try (var reopened = ProjectStore.open(paths)) {
			assertNotNull(reopened.desktop().getWindows());
			assertNotNull(reopened.desktop().getExpandedSections());
		}
	}

	@Test
	void aDamagedDefinitionFailsLoudlyRatherThanOpeningAnEmptyProject() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		Files.writeString(paths.projectFile(), "{ not json either");

		assertThrows(IOException.class, () -> ProjectStore.open(paths));
	}

	@Test
	void aNewerProjectSchemaIsNotSilentlyDowngraded() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		Files.writeString(paths.projectFile(), "{\"schemaVersion\":" + (AiProject.SCHEMA_VERSION + 1)
				+ ",\"id\":\"future\"}");

		assertThrows(IOException.class, () -> ProjectStore.open(paths));
	}

	private static int writtenSchema(Path projectFile) throws IOException {
		var node = dev.nuclr.plugin.core.ai.projects.store.Json.fromJson(Files.readString(projectFile),
				tools.jackson.databind.JsonNode.class);
		return node.path("schemaVersion").asInt(-1);
	}

	@Test
	void aProjectWithoutProfilesStaysAtSchemaOneSoOlderPluginsStillOpenIt() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var agent = new dev.nuclr.plugin.core.ai.projects.model.AgentDefinition();
			agent.setId("a1");
			store.project().getAgents().add(agent);
			store.markProjectDirty();
			store.flush();
		}
		assertEquals(1, writtenSchema(paths.projectFile()));
		assertFalse(Files.readString(paths.projectFile()).contains("profileId\" : \""));
	}

	@Test
	void anAgentStartingFromAProfileMakesTheProjectSchemaTwoAndSurvivesAReopen() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			var agent = new dev.nuclr.plugin.core.ai.projects.model.AgentDefinition();
			agent.setId("a1");
			agent.setProfileId("p-123");
			store.project().getAgents().add(agent);
			store.markProjectDirty();
			store.flush();
		}
		// A version 1 plugin refuses anything above 1, so it cannot open this and drop profileId.
		assertEquals(2, writtenSchema(paths.projectFile()));

		try (var reopened = ProjectStore.open(paths)) {
			assertEquals(2, reopened.project().readSchemaVersion());
			assertEquals("p-123", reopened.project().getAgents().getFirst().getProfileId());

			// No agent uses a profile any more: back to what every plugin reads.
			reopened.project().getAgents().getFirst().setProfileId(null);
			reopened.markProjectDirty();
			reopened.flush();
		}
		assertEquals(1, writtenSchema(paths.projectFile()));
	}

	@Test
	void aSchemaOneProjectFromAnOlderPluginStillOpens() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		var text = Files.readString(paths.projectFile());
		assertEquals(1, writtenSchema(paths.projectFile()));
		Files.writeString(paths.projectFile(), text);

		try (var reopened = ProjectStore.open(paths)) {
			assertEquals(1, reopened.project().readSchemaVersion());
		}
	}

	@Test
	void nullCollectionsInAHandEditedDefinitionAreFilledIn() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.flush();
		}
		Files.writeString(paths.projectFile(),
				"{\"schemaVersion\":1,\"id\":\"" + project.getId() + "\",\"name\":\"Hand edited\"}");

		try (var reopened = ProjectStore.open(paths)) {
			assertNotNull(reopened.project().getAgents());
			assertNotNull(reopened.project().getTemplates());
			assertNotNull(reopened.project().getHarness());
			assertEquals(root.toString(), reopened.project().getRoot());
		}
	}

	@Test
	void deletingAnAgentRemovesItsSessionAndTranscript() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, root.resolve("home"))) {
			store.session("a1").setStatus(AgentStatus.FINISHED);
			store.markSessionDirty("a1");
			store.transcripts().append("a1", "hello");
			store.flush();

			assertTrue(Files.isRegularFile(store.paths().sessionFile("a1")));
			assertTrue(Files.isRegularFile(store.paths().transcriptFile("a1")));

			store.deleteAgentRuntime("a1");

			assertFalse(Files.exists(store.paths().sessionFile("a1")));
			assertFalse(Files.exists(store.paths().transcriptFile("a1")));
		}
	}

	@Test
	void closingFlushesWhatWasStillPending() throws IOException {

		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		var paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		var store = ProjectCreator.create(project, root.resolve("home"));
		store.project().setDescription("written on close");
		store.markProjectDirty();
		store.close();

		try (var reopened = ProjectStore.open(paths)) {
			assertEquals("written on close", reopened.project().getDescription());
		}
	}

	@Test
	void aNewProjectAllowsItsOwnRootAndNothingElse() {
		var project = definition(ProjectStorageMode.PROJECT_LOCAL);
		assertEquals(List.of(root.toAbsolutePath().normalize().toString()),
				project.getHarness().getAllowedRoots());
	}

	@Test
	void privateStorageSanitisesAnUntrustedProjectId() {

		var home = root.resolve("home");
		var paths = ProjectPaths.of(root, ProjectStorageMode.COMMANDER_PRIVATE,
				"..\\..\\outside", home);

		assertTrue(paths.metadataDirectory().toAbsolutePath().normalize()
				.startsWith(home.toAbsolutePath().normalize().resolve("ai-projects")));
		assertEquals(".._.._outside", paths.metadataDirectory().getFileName().toString());
	}
}
