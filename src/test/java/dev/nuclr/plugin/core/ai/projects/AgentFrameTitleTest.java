package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AgentFrame;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AiProjectScreenPlugin;

/**
 * What an agent's own frame is called: its name, what runs it, and how it is doing.
 */
class AgentFrameTitleTest {

	@TempDir
	Path workspace;

	private AiProjectScreenPlugin plugin;
	private ProjectEntry entry;

	@BeforeEach
	void setUp() throws IOException {

		var context = new FakePluginContext();
		var catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
		plugin = new AiProjectScreenPlugin();
		plugin.preinit(context);
		plugin.init();

		var root = Files.createDirectories(workspace.resolve("project"));
		var project = ProjectCreator.define("project", root, ProjectStorageMode.PROJECT_LOCAL);
		project.getAgents().add(agent("a0", "Coder", "chat.claude-code"));
		project.getAgents().add(agent("a1", "Digger", "terminal.shell"));
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		entry = ProjectCreator.entry(project);
		catalog.register(entry);
	}

	private static AgentDefinition agent(String id, String name, String kind) {
		var agent = new AgentDefinition();
		agent.setId(id);
		agent.setName(name);
		agent.setWindowKind(kind);
		return agent;
	}

	@Test
	void eachFrameSaysWhichAgentImplementationItIsRunning() throws Exception {

		var titles = titles();

		assertFalse(titles.isEmpty(), "no agent frames opened");
		assertTrue(titles.stream().anyMatch(title -> title.startsWith("Coder")
				&& title.contains("Claude Code")), "no Claude Code conversation in " + titles);
		assertTrue(titles.stream().anyMatch(title -> title.startsWith("Digger")
				&& title.contains("Shell")), "no shell terminal in " + titles);
	}

	/** The title of every agent frame on the open desktop. */
	private List<String> titles() throws Exception {
		var result = new AtomicReference<List<String>>();
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
		try {
			onEdt(() -> {
				var frames = new ArrayList<AgentFrame>();
				collect(plugin.panel(), frames);
				result.set(frames.stream().map(AgentFrame::getTitle).toList());
			});
		} finally {
			onEdt(plugin::closeResource);
		}
		assertNotNull(result.get());
		return result.get();
	}

	private static void collect(Component component, List<AgentFrame> into) {
		if (component instanceof AgentFrame frame) {
			into.add(frame);
		}
		if (component instanceof Container container) {
			for (var child : container.getComponents()) {
				collect(child, into);
			}
		}
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}
}
