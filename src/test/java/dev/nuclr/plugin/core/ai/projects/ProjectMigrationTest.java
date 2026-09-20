package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectMigration;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/** A project from before profiles opens with its harness, context and templates carried over to profiles. */
class ProjectMigrationTest {

	@TempDir
	Path root;

	private ProjectPaths paths;

	/** Write a version 2 project, as the plugin before profiles did, with its documents. */
	private void writeVersionTwoProject() throws IOException {
		var project = ProjectCreator.define("Demo", root, ProjectStorageMode.PROJECT_LOCAL);
		paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), root.resolve("home"));
		ProjectCreator.create(project, root.resolve("home")).close();
		Files.createDirectories(paths.instructionsDirectory());
		Files.createDirectories(paths.skillsDirectory());
		Files.writeString(paths.instructionsDirectory().resolve("coder.md"), "Write tests first.");
		Files.writeString(paths.skillsDirectory().resolve("review.md"), "Review carefully.");
		var json = """
				{"schemaVersion": 2, "id": "%s", "name": "Demo", "root": %s, "storageMode": "PROJECT_LOCAL",
				 "harness": {"executable": "claude", "provider": "anthropic", "model": "opus",
				             "env": {"MAVEN_OPTS": "-Xmx2g"},
				             "allowedRoots": [%s, "/srv/shared"],
				             "mcpServers": [{"name": "files", "command": "mcp-files", "args": []}],
				             "permissions": ["read"]},
				 "context": {"instructions": ["instructions/coder.md"], "variables": {"TICKET": "AI-1"}},
				 "templates": [{"id": "t1", "name": "Reviewer", "windowKind": "terminal.claude-code",
				                "context": {"skills": ["review"]}}],
				 "agents": [
				   {"id": "a1", "name": "Review", "windowKind": "terminal.claude-code", "templateId": "t1"},
				   {"id": "a2", "name": "Code", "windowKind": "terminal.claude-code"},
				   {"id": "a3", "name": "Also code", "windowKind": "terminal.claude-code"},
				   {"id": "a4", "name": "Shell", "windowKind": "terminal.shell",
				    "harness": {"executable": "bash", "provider": null}},
				   {"id": "a5", "name": "Chosen", "windowKind": "terminal.codex", "profileId": "abc"}]}"""
				.formatted(project.getId(), quoted(root.toString()), quoted(root.toString()));
		Files.writeString(paths.projectFile(), json);
	}

	private static String quoted(String text) {
		return "\"" + text.replace("\\", "\\\\") + "\"";
	}

	private Profile profileOf(ProjectStore store, String agentId) throws IOException {
		var ref = ProfileRef.parse(store.project().agent(agentId).orElseThrow().getProfileId()).orElseThrow();
		assertEquals(ProfileRef.Place.PROJECT, ref.place());
		return new ProfileStore(paths.profilesDirectory()).require(ref.id());
	}

	@Test
	void eachAgentStartsFromAProjectProfileThatReproducesItsOldLaunch() throws IOException {
		writeVersionTwoProject();

		try (var store = ProjectStore.open(paths)) {
			var reviewer = profileOf(store, "a1");
			assertEquals("Reviewer", reviewer.getName(), "named after its template");
			assertEquals("claude-code", reviewer.getHarness().getProvider(), "the old provider name is understood");
			assertNull(reviewer.getHarness().getExecutable(), "claude is Claude Code's own command");
			assertEquals("opus", reviewer.getHarness().getModel());
			assertEquals("MAVEN_OPTS", reviewer.getHarness().getEnvironment().getFirst().getName());
			assertEquals("files", reviewer.getHarness().getMcpServers().getFirst().getName());
			assertEquals(List.of("/srv/shared"),
					reviewer.getHarness().getExtraFolders().stream().map(ProfileRecord::getPath).toList());

			var instructions = reviewer.getContext().getInstructions();
			assertEquals(RecordKind.FILE, instructions.get(0).getKind());
			assertEquals(".nuclr/ai-project/instructions/coder.md", instructions.get(0).getPath(),
					"relative to the project root, so it works wherever the project is cloned");
			assertTrue(instructions.get(1).getPath().endsWith("review.md"), "the template's skill, in full as before");
			assertEquals("Context variables", instructions.get(2).getName());
			assertTrue(instructions.get(2).getText().contains("TICKET = AI-1"));
			assertTrue(ProfileValidator.validate(reviewer, List.of()).isEmpty(),
					ProfileValidator.validate(reviewer, List.of()).toString());

			var coder = profileOf(store, "a2");
			assertEquals("Demo", coder.getName(), "named after the project, whose settings it carries");
			assertNotEquals(reviewer.getId(), coder.getId());
			assertEquals(store.project().agent("a2").orElseThrow().getProfileId(),
					store.project().agent("a3").orElseThrow().getProfileId(), "agents that came out the same share one");

			assertNull(store.project().agent("a4").orElseThrow().getProfileId(), "a shell has no provider");
			assertEquals("library:abc", store.project().agent("a5").orElseThrow().getProfileId(),
					"a profile already chosen is kept, now saying where it is");
			assertEquals(List.of("/srv/shared"), store.project().getAllowedRoots());
		}

		var written = Files.readString(paths.projectFile());
		assertTrue(written.contains("\"schemaVersion\" : " + AiProject.SCHEMA_VERSION), written);
		for (var gone : List.of("\"harness\"", "\"context\"", "\"templates\"", "\"templateId\"")) {
			assertFalse(written.contains(gone), gone + " in " + written);
		}
	}

	@Test
	void openingAgainMakesNoMoreProfiles() throws IOException {
		writeVersionTwoProject();
		ProjectStore.open(paths).close();
		var count = new ProfileStore(paths.profilesDirectory()).list().profiles().size();

		ProjectStore.open(paths).close();

		assertEquals(2, count);
		assertEquals(count, new ProfileStore(paths.profilesDirectory()).list().profiles().size());
	}

	@Test
	void anInterruptedCarryOverFindsTheProfilesItAlreadyMade() throws IOException {
		writeVersionTwoProject();
		var original = Files.readString(paths.projectFile());
		ProjectStore.open(paths).close();
		// As if Commander stopped after writing the profiles but before the project.
		Files.writeString(paths.projectFile(), original);

		ProjectStore.open(paths).close();

		assertEquals(2, new ProfileStore(paths.profilesDirectory()).list().profiles().size());
	}

	/** A project holding one agent of the given window kind. */
	private static AiProject projectWith(String... kinds) {
		var project = new AiProject();
		project.setName("Demo");
		var index = 0;
		for (var kind : kinds) {
			var agent = new AgentDefinition();
			agent.setId("a" + index++);
			agent.setName("Agent");
			agent.setWindowKind(kind);
			project.getAgents().add(agent);
		}
		return project;
	}

	@Test
	void anAgentRecordedAsATerminalIsOpenedAsAConversation() throws IOException {
		writeVersionTwoProject();

		try (var store = ProjectStore.open(paths)) {
			assertEquals("chat.claude-code", store.project().agent("a1").orElseThrow().getWindowKind());
			assertEquals("chat.codex", store.project().agent("a5").orElseThrow().getWindowKind());
		}
		// Written, not merely mended in memory: the next open finds it already moved.
		assertTrue(Files.readString(paths.projectFile()).contains("chat.claude-code"));
	}

	@Test
	void theShellStaysATerminalBecauseThatIsWhatItIsFor() {

		var project = projectWith("terminal.shell");

		assertFalse(ProjectMigration.toConversations(project));
		assertEquals("terminal.shell", project.getAgents().get(0).getWindowKind());
	}

	@Test
	void aProjectAlreadyMovedIsNotWrittenAgain() {

		var project = projectWith("terminal.codex", "chat.pi", "board.tasks", null);

		assertTrue(ProjectMigration.toConversations(project));
		assertFalse(ProjectMigration.toConversations(project), "nothing left to move");
		assertEquals(List.of("chat.codex", "chat.pi", "board.tasks"), project.getAgents().stream()
				.map(AgentDefinition::getWindowKind).filter(kind -> kind != null).toList());
		assertNull(project.getAgents().get(3).getWindowKind(), "an agent with no kind gets none invented for it");
	}

	@Test
	void aKindThatWasNeverOursIsLeftAlone() {

		var project = projectWith("terminal.nonesuch");

		assertFalse(ProjectMigration.toConversations(project));
		assertEquals("terminal.nonesuch", project.getAgents().get(0).getWindowKind());
	}
}
