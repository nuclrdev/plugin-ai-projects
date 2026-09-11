package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.harness.Provenance;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentTemplate;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;

/** Inheritance and merging of project, template and agent harnesses. */
class HarnessResolverTest {

	private static AiProject project() {
		var project = new AiProject();
		project.setId("p");
		project.setName("Project");
		project.setRoot("/tmp/project");
		var harness = project.getHarness();
		harness.setExecutable("claude");
		harness.setProvider("anthropic");
		harness.setModel("project-model");
		harness.setStartupArgs(List.of("--project"));
		harness.setPermissions(List.of("read", "write"));
		harness.setAllowedRoots(List.of("/tmp/project"));
		var env = new LinkedHashMap<String, String>();
		env.put("SHARED", "project");
		env.put("ONLY_PROJECT", "yes");
		harness.setEnv(env);
		harness.setMcpServers(List.of(
				McpServerSpec.of("files", "mcp-files", List.of("/tmp/project")),
				McpServerSpec.of("search", "mcp-search", List.of())));
		return project;
	}

	private static AgentDefinition agent(AiProject project, String templateId) {
		var agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setTemplateId(templateId);
		project.getAgents().add(agent);
		return agent;
	}

	@Test
	void inheritsEverythingWhenTheAgentOverridesNothing() {

		var project = project();
		var agent = agent(project, null);

		var resolved = HarnessResolver.resolve(project, agent);

		assertEquals("claude", resolved.executable());
		assertEquals("project-model", resolved.model());
		assertEquals(List.of("--project"), resolved.startupArgs());
		assertEquals(Provenance.PROJECT, resolved.source(EffectiveHarness.EXECUTABLE));
	}

	@Test
	void agentOverridesWinOverTemplateWhichWinsOverProject() {

		var project = project();
		var template = new AgentTemplate();
		template.setId("reviewer");
		template.getHarness().setModel("template-model");
		template.getHarness().setProvider("template-provider");
		project.getTemplates().add(template);

		var agent = agent(project, "reviewer");
		agent.getHarness().setModel("agent-model");

		var resolved = HarnessResolver.resolve(project, agent);

		assertEquals("agent-model", resolved.model());
		assertEquals(Provenance.AGENT, resolved.source(EffectiveHarness.MODEL));
		assertEquals("template-provider", resolved.provider());
		assertEquals(Provenance.TEMPLATE, resolved.source(EffectiveHarness.PROVIDER));
		assertEquals("claude", resolved.executable());
		assertEquals(Provenance.PROJECT, resolved.source(EffectiveHarness.EXECUTABLE));
	}

	@Test
	void environmentIsMergedRatherThanReplaced() {

		var project = project();
		var agent = agent(project, null);
		var env = new LinkedHashMap<String, String>();
		env.put("SHARED", "agent");
		env.put("ONLY_AGENT", "yes");
		agent.getHarness().setEnv(env);

		var resolved = HarnessResolver.resolve(project, agent);

		assertEquals("agent", resolved.env().get("SHARED"));
		assertEquals("yes", resolved.env().get("ONLY_PROJECT"));
		assertEquals("yes", resolved.env().get("ONLY_AGENT"));
	}

	@Test
	void mcpServersMergeByNameSoOneCanBeSwappedWithoutRestatingTheRest() {

		var project = project();
		var agent = agent(project, null);
		agent.getHarness().setMcpServers(List.of(
				McpServerSpec.of("search", "other-search", List.of("--fast")),
				McpServerSpec.of("extra", "mcp-extra", List.of())));

		var resolved = HarnessResolver.resolve(project, agent);
		var names = resolved.mcpServers().stream().map(McpServerSpec::getName).toList();

		assertEquals(List.of("files", "search", "extra"), names);
		assertEquals("other-search --fast",
				resolved.mcpServers().get(1).displayCommandLine());
	}

	@Test
	void anExplicitlyEmptyServerListMeansNoServersRatherThanInherit() {

		var project = project();
		var agent = agent(project, null);
		agent.getHarness().setMcpServers(List.of());

		assertTrue(HarnessResolver.resolve(project, agent).mcpServers().isEmpty());
	}

	@Test
	void aNullListMeansInheritWhileAnEmptyOneMeansNothing() {

		var project = project();
		var inheriting = agent(project, null);
		assertEquals(List.of("read", "write"), HarnessResolver.resolve(project, inheriting).permissions());

		inheriting.getHarness().setPermissions(List.of());
		assertEquals(List.of(), HarnessResolver.resolve(project, inheriting).permissions());
		assertEquals(Provenance.AGENT, HarnessResolver.resolve(project, inheriting)
				.source(EffectiveHarness.PERMISSIONS));
	}

	@Test
	void commandLineIsExecutablePlusStartupArgs() {

		var project = project();
		var resolved = HarnessResolver.resolveProject(project);

		assertEquals(List.of("claude", "--project"), resolved.commandLine());
		assertEquals("claude --project", resolved.displayCommandLine());
	}

	@Test
	void anUnconfiguredExecutableYieldsNoCommandLineRatherThanABlankOne() {

		var project = new AiProject();
		project.setId("empty");
		project.setRoot("/tmp/empty");

		assertTrue(HarnessResolver.resolveProject(project).commandLine().isEmpty());
	}

	@Test
	void flatteningKeepsTheOverrideAndCopiesTheBase() {

		var base = new HarnessSpec();
		base.setExecutable("claude");
		base.setEnv(new LinkedHashMap<>(java.util.Map.of("A", "1")));

		var override = new HarnessSpec();
		override.setModel("m");
		override.setEnv(new LinkedHashMap<>(java.util.Map.of("B", "2")));

		var flattened = HarnessResolver.flatten(base, override);

		assertEquals("claude", flattened.getExecutable());
		assertEquals("m", flattened.getModel());
		assertEquals(java.util.Set.of("A", "B"), flattened.getEnv().keySet());
		assertEquals(java.util.Set.of("A"), base.getEnv().keySet());
	}

	@Test
	void disabledServersAreKeptButNotHandedToTheAgent() {

		var project = project();
		var disabled = McpServerSpec.of("search", "mcp-search", List.of());
		disabled.setEnabled(false);
		project.getHarness().setMcpServers(List.of(
				McpServerSpec.of("files", "mcp-files", List.of()), disabled));

		var resolved = HarnessResolver.resolveProject(project);

		assertEquals(2, resolved.mcpServers().size());
		assertEquals(1, resolved.enabledMcpServers().size());
		assertEquals("files", resolved.enabledMcpServers().getFirst().getName());
	}
}
