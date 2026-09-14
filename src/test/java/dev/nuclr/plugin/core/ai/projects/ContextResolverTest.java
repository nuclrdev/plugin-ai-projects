package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment;
import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.Provenance;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentTemplate;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;

/** Shared and agent-specific context, and what the resolved view reports. */
class ContextResolverTest {

	@TempDir
	Path root;

	private ProjectPaths paths;
	private AiProject project;

	@BeforeEach
	void setUp() throws IOException {
		paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, "p", root.resolve("home"));
		paths.createLayout(true);
		project = new AiProject();
		project.setId("p");
		project.setName("Project");
		project.setRoot(root.toString());
	}

	private Path writeSkill(String name, String body) throws IOException {
		var file = paths.skillsDirectory().resolve(name + ".md");
		Files.writeString(file, body);
		return file;
	}

	private AgentDefinition agent(String id, String name) {
		var agent = new AgentDefinition();
		agent.setId(id);
		agent.setName(name);
		project.getAgents().add(agent);
		return agent;
	}

	@Test
	void projectContextReachesEveryAgentAndTheAgentAddsItsOwn() throws IOException {

		writeSkill("shared", "shared");
		writeSkill("reviewing", "reviewing");
		project.getContext().getSkills().add("shared");

		var agent = agent("a1", "Reviewer");
		agent.getContext().getSkills().add("reviewing");

		var resolved = ContextResolver.resolve(project, agent, paths);
		var skills = resolved.of(ContextItem.Kind.SKILL);

		assertEquals(List.of("shared", "reviewing"), skills.stream().map(ContextItem::label).toList());
		assertEquals(Provenance.PROJECT, skills.get(0).source());
		assertEquals(Provenance.AGENT, skills.get(1).source());
	}

	@Test
	void aTemplateContributesBetweenTheProjectAndTheAgent() throws IOException {

		writeSkill("shared", "shared");
		writeSkill("templated", "templated");
		project.getContext().getSkills().add("shared");

		var template = new AgentTemplate();
		template.setId("coder");
		template.getContext().getSkills().add("templated");
		project.getTemplates().add(template);

		var agent = agent("a1", "Coder");
		agent.setTemplateId("coder");

		var skills = ContextResolver.resolve(project, agent, paths).of(ContextItem.Kind.SKILL);

		assertEquals(List.of("shared", "templated"), skills.stream().map(ContextItem::label).toList());
		assertEquals(Provenance.TEMPLATE, skills.get(1).source());
	}

	@Test
	void aRepeatedItemIsListedOnceAndKeepsTheLevelThatFirstContributedIt() throws IOException {

		writeSkill("shared", "shared");
		project.getContext().getSkills().add("shared");
		var agent = agent("a1", "Agent");
		agent.getContext().getSkills().add("shared");

		var skills = ContextResolver.resolve(project, agent, paths).of(ContextItem.Kind.SKILL);

		assertEquals(1, skills.size());
		assertEquals(Provenance.PROJECT, skills.getFirst().source());
	}

	@Test
	void aMissingDocumentIsListedAndMarkedRatherThanDroppedSilently() {

		project.getContext().getInstructions().add("instructions/absent.md");

		var resolved = ContextResolver.resolve(project, null, paths);
		var instruction = resolved.of(ContextItem.Kind.INSTRUCTION).getFirst();

		assertFalse(instruction.available());
		assertEquals(1, resolved.missing().size());
	}

	@Test
	void documentsLinkedFromAnotherProjectAreListedAsLinked() throws IOException {

		var other = Files.createDirectories(root.resolveSibling(root.getFileName() + "-other"));
		var instruction = Files.writeString(other.resolve("HOUSE-STYLE.md"), "house style");
		var skill = Files.writeString(other.resolve("review.md"), "review");
		writeSkill("local", "local");

		project.getContext().getInstructions().add(instruction.toString());
		var agent = agent("a1", "Agent");
		agent.getContext().getSkills().add("local");
		agent.getContext().getSkills().add(skill.toString());

		var resolved = ContextResolver.resolve(project, agent, paths);
		var linkedInstruction = resolved.of(ContextItem.Kind.INSTRUCTION).getFirst();
		var skills = resolved.of(ContextItem.Kind.SKILL);

		assertTrue(linkedInstruction.available());
		assertTrue(linkedInstruction.linked());
		assertFalse(skills.get(0).linked(), "a skill in the project's own folder is not linked");
		assertTrue(skills.get(1).linked());
		assertEquals(skill, skills.get(1).path());
		assertTrue(resolved.missing().isEmpty());
	}

	@Test
	void harnessSharedInstructionsAreListedBeforeContextOnes() throws IOException {

		Files.writeString(paths.instructionsDirectory().resolve("house.md"), "house");
		Files.writeString(paths.instructionsDirectory().resolve("extra.md"), "extra");
		project.getHarness().setSharedInstructions(List.of("instructions/house.md"));
		project.getContext().getInstructions().add("instructions/extra.md");

		var labels = ContextResolver.resolve(project, null, paths)
				.of(ContextItem.Kind.INSTRUCTION).stream().map(ContextItem::label).toList();

		assertEquals(List.of("instructions/house.md", "instructions/extra.md"), labels);
	}

	@Test
	void theVariablesTheLauncherSetsAppearInTheView() {

		var agent = agent("a1", "Agent");

		var environment = ContextResolver.resolve(project, agent, paths).of(ContextItem.Kind.ENVIRONMENT);
		var names = environment.stream().map(ContextItem::label).toList();

		assertTrue(names.contains(AgentEnvironment.PROJECT_NAME));
		assertTrue(names.contains(AgentEnvironment.AGENT_NAME));
		assertTrue(names.contains(AgentEnvironment.PROJECT_ROOT));
	}

	@Test
	void aBareSkillNameResolvesInTheSkillsDirectoryWhileAPathIsTakenAsWritten() throws IOException {

		var skill = writeSkill("review", "review");
		Files.createDirectories(root.resolve("docs"));
		var doc = Files.writeString(root.resolve("docs").resolve("CONVENTIONS.md"), "conventions");

		assertEquals(skill, ContextResolver.skillPath(paths, "review"));
		assertEquals(doc, ContextResolver.skillPath(paths, "docs/CONVENTIONS.md"));
	}

	@Test
	void groupingOmitsEmptyCategoriesAndKeepsDeclarationOrder() throws IOException {

		writeSkill("shared", "shared");
		project.getContext().getSkills().add("shared");
		project.getHarness().setPermissions(List.of("read"));

		var grouped = ContextResolver.resolve(project, null, paths).grouped();

		assertTrue(grouped.containsKey(ContextItem.Kind.SKILL));
		assertTrue(grouped.containsKey(ContextItem.Kind.PERMISSION));
		assertFalse(grouped.containsKey(ContextItem.Kind.INJECTED_FILE));
	}

	@Test
	void aWorkingDirectoryThatDoesNotExistFallsBackToTheProjectRoot() {

		var agent = agent("a1", "Agent");
		agent.setWorkingDirectory("nowhere-at-all");

		assertEquals(root, AgentEnvironment.workingDirectory(agent, root));
	}

	@Test
	void aRelativeWorkingDirectoryResolvesAgainstTheProjectRoot() throws IOException {

		var module = Files.createDirectories(root.resolve("module"));
		var agent = agent("a1", "Agent");
		agent.setWorkingDirectory("module");

		assertEquals(module, AgentEnvironment.workingDirectory(agent, root));
	}

	@Test
	void aWorkingDirectoryOutsideApprovedRootsFallsBackToTheProjectRoot() throws IOException {

		var outside = Files.createDirectories(root.resolveSibling("outside-agent-directory"));
		var agent = agent("a1", "Agent");
		agent.setWorkingDirectory(outside.toString());
		project.getHarness().setAllowedRoots(List.of(root.toString()));

		assertEquals(root, AgentEnvironment.workingDirectory(agent, root,
				project.getHarness().getAllowedRoots()));
	}

	@Test
	void aDocumentOutsideTheProjectIsRejected() {

		assertEquals(null, paths.resolveDocument("../../../outside.md"));
	}
}
