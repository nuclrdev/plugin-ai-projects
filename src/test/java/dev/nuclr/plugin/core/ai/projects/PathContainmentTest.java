package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;

/**
 * A project definition is a file, and a file can be edited or arrive from
 * someone else. These cover the two places where a path out of it is refused:
 * document references, and an agent's working directory.
 *
 * <p>The interesting cases are the ones where the target <em>exists</em>. A
 * containment check that only rejects paths to nothing is no check at all, and
 * that is precisely how the first version of this passed its own tests.
 */
class PathContainmentTest {

	@TempDir
	Path workspace;

	private Path root;
	private ProjectPaths paths;

	@BeforeEach
	void setUp() throws IOException {
		root = Files.createDirectories(workspace.resolve("project"));
		paths = ProjectPaths.of(root, ProjectStorageMode.PROJECT_LOCAL, "p", workspace.resolve("home"));
		paths.createLayout(true);
	}

	// ------------------------------------------------------- document references

	@Test
	void aSkillInsideTheProjectResolves() throws IOException {
		var skill = Files.writeString(paths.skillsDirectory().resolve("review.md"), "review");
		assertEquals(skill, paths.resolveDocument("skills/review.md"));
	}

	@Test
	void aDocumentUnderTheProjectRootResolves() throws IOException {
		Files.createDirectories(root.resolve("docs"));
		var doc = Files.writeString(root.resolve("docs").resolve("CONVENTIONS.md"), "x");
		assertEquals(doc, paths.resolveDocument("docs/CONVENTIONS.md"));
	}

	@Test
	void aMissingButLegalReferenceStillResolvesSoTheViewCanMarkItMissing() {
		var resolved = paths.resolveDocument("instructions/absent.md");
		assertNotNull(resolved);
		assertTrue(resolved.startsWith(paths.metadataDirectory()));
	}

	@Test
	void anExistingFileOutsideTheProjectIsRefused() throws IOException {

		// The file is real, which is the whole point: rejecting only paths to nothing
		// would let every reference that actually matters through.
		Files.writeString(workspace.resolve("secret.txt"), "secret");

		assertNull(paths.resolveDocument("../../../secret.txt"));
	}

	@Test
	void anExistingFileOutsideTheProjectIsRefusedByAbsolutePathToo() throws IOException {
		var outside = Files.writeString(workspace.resolve("secret.txt"), "secret");
		assertNull(paths.resolveDocument(outside.toString()));
	}

	@Test
	void aNonExistentPathOutOfTheProjectIsRefused() {
		assertNull(paths.resolveDocument("../../../outside.md"));
	}

	@Test
	void anAbsolutePathInsideTheProjectIsAccepted() throws IOException {
		var doc = Files.writeString(root.resolve("NOTES.md"), "notes");
		assertEquals(doc, paths.resolveDocument(doc.toString()));
	}

	@Test
	void aBlankReferenceResolvesToNothing() {
		assertNull(paths.resolveDocument(null));
		assertNull(paths.resolveDocument("   "));
	}

	// ------------------------------------- document references and allowed roots

	@Test
	void aSharedDocumentInAnAllowedRootResolves() throws IOException {

		// The case this exists for: one instruction document shared across several
		// checkouts, with its directory declared in the harness.
		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		var doc = Files.writeString(shared.resolve("HOUSE-STYLE.md"), "house style");

		assertEquals(doc, paths.resolveDocument(doc.toString(), List.of(shared.toString())));
	}

	@Test
	void thatSameDocumentIsRefusedWhenItsRootIsNotDeclared() throws IOException {
		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		var doc = Files.writeString(shared.resolve("HOUSE-STYLE.md"), "house style");
		assertNull(paths.resolveDocument(doc.toString(), List.of()));
	}

	@Test
	void aRelativeReferenceClimbingIntoAnAllowedRootResolves() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		var doc = Files.writeString(shared.resolve("HOUSE-STYLE.md"), "house style");

		// ../shared-instructions from the project root.
		assertEquals(doc, paths.resolveDocument(
				"../shared-instructions/HOUSE-STYLE.md", List.of(shared.toString())));
	}

	@Test
	void declaringOneRootDoesNotOpenItsSiblings() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		Files.createDirectories(shared);
		var elsewhere = Files.writeString(workspace.resolve("secret.txt"), "secret");

		assertNull(paths.resolveDocument(elsewhere.toString(), List.of(shared.toString())));
	}

	@Test
	void aMissingDocumentInsideAnAllowedRootStillResolvesSoItReadsAsMissing() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		var absent = shared.resolve("NOT-WRITTEN-YET.md");

		assertEquals(absent, paths.resolveDocument(absent.toString(), List.of(shared.toString())));
	}

	@Test
	void theProjectsOwnFilesNeedNoAllowedRoot() throws IOException {
		var doc = Files.writeString(paths.skillsDirectory().resolve("review.md"), "review");
		assertEquals(doc, paths.resolveDocument("skills/review.md", null));
	}

	@Test
	void aSkillNamedAsAPathCanLiveInAnAllowedRoot() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-skills"));
		var skill = Files.writeString(shared.resolve("review.md"), "review");

		assertEquals(skill, ContextResolver.skillPath(paths, skill.toString(), List.of(shared.toString())));
		assertNull(ContextResolver.skillPath(paths, skill.toString(), List.of()));
	}

	@Test
	void aBareSkillNameStillMeansTheProjectsSkillsFolder() {
		assertEquals(paths.skillsDirectory().resolve("review.md"),
				ContextResolver.skillPath(paths, "review", List.of(workspace.toString())));
	}

	@Test
	void theResolvedContextHonoursTheHarnessAllowedRootsForDocuments() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-instructions"));
		var doc = Files.writeString(shared.resolve("HOUSE-STYLE.md"), "house style");

		var project = new AiProject();
		project.setId("p");
		project.setName("Project");
		project.setRoot(root.toString());
		project.getHarness().setAllowedRoots(List.of(root.toString(), shared.toString()));
		project.getContext().getInstructions().add(doc.toString());

		var resolved = ContextResolver.resolve(project, null, paths);
		var instruction = resolved.of(ContextItem.Kind.INSTRUCTION).getFirst();

		assertTrue(instruction.available(), "a document in a declared allowed root should resolve");
		assertEquals(doc, instruction.path());
	}

	@Test
	void theResolvedContextMarksADocumentOutsideEveryRootAsUnresolved() throws IOException {

		var outside = Files.writeString(workspace.resolve("secret.txt"), "secret");

		var project = new AiProject();
		project.setId("p");
		project.setName("Project");
		project.setRoot(root.toString());
		project.getHarness().setAllowedRoots(List.of(root.toString()));
		project.getContext().getInstructions().add(outside.toString());

		var instruction = ContextResolver.resolve(project, null, paths)
				.of(ContextItem.Kind.INSTRUCTION).getFirst();

		// Listed, so the user can see the reference; not available, and with no path
		// handed on, so nothing downstream can read it.
		assertFalse(instruction.available());
		assertNull(instruction.path());
	}

	@Test
	void theLauncherAndTheContextViewAgreeAboutAnAllowedRoot() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-checkout"));
		var roots = List.of(root.toString(), shared.toString());

		// One containment rule, so where an agent runs and what it may be pointed at
		// cannot drift apart.
		assertEquals(shared, AgentEnvironment.workingDirectory(agentIn(shared.toString()), root, roots));
		assertNotNull(paths.resolveDocument(shared.resolve("NOTES.md").toString(), roots));
	}

	// ------------------------------------------------------ working directories

	private static AgentDefinition agentIn(String workingDirectory) {
		var agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWorkingDirectory(workingDirectory);
		return agent;
	}

	@Test
	void aWorkingDirectoryInsideTheProjectIsUsed() throws IOException {
		var module = Files.createDirectories(root.resolve("module"));
		assertEquals(module, AgentEnvironment.workingDirectory(agentIn("module"), root, List.of()));
	}

	@Test
	void anExistingDirectoryOutsideEveryAllowedRootFallsBackToTheProjectRoot() throws IOException {

		var outside = Files.createDirectories(workspace.resolve("somewhere-else"));

		assertEquals(root, AgentEnvironment.workingDirectory(
				agentIn(outside.toString()), root, List.of(root.toString())));
	}

	@Test
	void climbingOutWithDotDotFallsBackToTheProjectRoot() throws IOException {
		Files.createDirectories(workspace.resolve("somewhere-else"));
		assertEquals(root, AgentEnvironment.workingDirectory(
				agentIn("../somewhere-else"), root, List.of(root.toString())));
	}

	@Test
	void aDirectoryInsideADeclaredAllowedRootIsHonoured() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-checkout"));

		assertEquals(shared, AgentEnvironment.workingDirectory(
				agentIn(shared.toString()), root, List.of(root.toString(), shared.toString())));
	}

	@Test
	void withNoAllowedRootsOnlyTheProjectRootIsPermitted() throws IOException {

		var shared = Files.createDirectories(workspace.resolve("shared-checkout"));

		// The narrow overload: callers that launch or describe an agent must pass the
		// harness roots, or they disagree with each other about where it runs.
		assertEquals(root, AgentEnvironment.workingDirectory(agentIn(shared.toString()), root));
		assertEquals(shared, AgentEnvironment.workingDirectory(
				agentIn(shared.toString()), root, List.of(shared.toString())));
	}

	@Test
	void aMalformedAllowedRootIsIgnoredRatherThanWideningAnything() throws IOException {

		var outside = Files.createDirectories(workspace.resolve("somewhere-else"));
		var roots = new java.util.ArrayList<String>();
		roots.add(null);
		roots.add("   ");
		roots.add(root.toString());

		assertEquals(root, AgentEnvironment.workingDirectory(agentIn(outside.toString()), root, roots));
	}

	@Test
	void aBlankWorkingDirectoryMeansTheProjectRoot() {
		assertEquals(root, AgentEnvironment.workingDirectory(agentIn(null), root, List.of()));
		assertEquals(root, AgentEnvironment.workingDirectory(agentIn("  "), root, List.of()));
	}
}
