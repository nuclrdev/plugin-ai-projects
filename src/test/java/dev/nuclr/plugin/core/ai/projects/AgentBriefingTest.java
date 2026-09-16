package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.harness.AgentBriefing;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/** What an agent is handed at launch is what the resolved context says it gets. */
class AgentBriefingTest {

	@TempDir
	Path root;

	private ProjectStore store;

	@BeforeEach
	void setUp() throws IOException {
		var project = ProjectCreator.define("Demo", root, ProjectStorageMode.PROJECT_LOCAL, "terminal.claude-code", null);
		store = ProjectCreator.create(project, root.resolve("home"));
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	private AgentDefinition agent(String templateId) {
		var agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setTemplateId(templateId);
		store.project().getAgents().add(agent);
		return agent;
	}

	private AgentBriefing briefing(AgentDefinition agent) {
		return AgentBriefing.of("Demo", agent.displayName(),
				ContextResolver.resolve(store.project(), agent, store.paths()));
	}

	@Test
	void aResearcherIsToldItsTemplatesInstructions() throws IOException {

		// The bug this exists for: a rule added to the Researcher's instructions was
		// listed in Resolved Context and never reached the agent.
		var instructions = store.paths().instructionsDirectory().resolve("researcher.md");
		Files.writeString(instructions, Files.readString(instructions) + "- No code changes.\n");

		var briefing = briefing(agent("researcher"));

		assertTrue(briefing.text().contains("No code changes."), briefing.text());
		assertTrue(briefing.text().contains("instructions/researcher.md"), briefing.text());
	}

	@Test
	void skillsInjectedFilesAndVariablesAreIncludedWithTheirContent() throws IOException {

		Files.writeString(store.paths().skillsDirectory().resolve("review.md"), "Review carefully.");
		Files.writeString(root.resolve("NOTES.md"), "Injected notes.");
		var agent = agent(null);
		agent.getContext().getSkills().add("review");
		agent.getContext().getInjectedFiles().add("NOTES.md");
		agent.getContext().getVariables().put("TICKET", "AI-42");

		var briefing = briefing(agent);

		assertTrue(briefing.text().contains("Review carefully."));
		assertTrue(briefing.text().contains("Injected notes."));
		assertTrue(briefing.text().contains("`TICKET` = AI-42"));
		assertEquals(1, briefing.variables());
	}

	@Test
	void aMissingDocumentIsNamedRatherThanSilentlyDropped() {

		var agent = agent(null);
		agent.getContext().getInstructions().add("instructions/absent.md");

		var briefing = briefing(agent);

		assertEquals(1, briefing.missing());
		assertTrue(briefing.text().contains("## Not found"));
		assertTrue(briefing.text().contains("instructions/absent.md"));
	}

	@Test
	void anAgentWithNothingToBeToldGetsNoBriefing() {

		store.project().getContext().getInstructions().clear();
		store.project().getContext().getSkills().clear();
		var agent = agent(null);

		var briefing = briefing(agent);

		assertTrue(briefing.isEmpty(), briefing.text());
		assertEquals("", briefing.text());
	}

	@Test
	void projectKnowledgeAndLoadingRulesAreListedWithoutTheirContent() throws IOException {

		Files.writeString(root.resolve("ARCHITECTURE.md"), "Should not be pasted in.");
		store.project().getContext().getKnowledge().add("ARCHITECTURE.md");
		store.project().getContext().getLoadingRules().add("exclude: target/**");

		var briefing = briefing(agent(null));

		assertTrue(briefing.text().contains("## Project knowledge"), briefing.text());
		assertTrue(briefing.text().contains("- ARCHITECTURE.md"), briefing.text());
		assertTrue(briefing.text().contains("- exclude: target/**"), briefing.text());
		assertTrue(!briefing.text().contains("Should not be pasted in."), briefing.text());
		assertEquals(2, briefing.references());
	}
}
