package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.quickview.AiProjectQuickViewPlugin;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ListEditor;

/**
 * The editable half of the configuration, and the preview that saves opening a
 * project to find out what is in it.
 */
class EditorAndQuickViewTest {

	@TempDir
	Path workspace;

	private FakePluginContext context;
	private ProjectCatalog catalog;

	@BeforeEach
	void setUp() {
		context = new FakePluginContext();
		catalog = new ProjectCatalog(context.getSettings(), workspace.resolve("home"));
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	// ------------------------------------------------------------- list editor

	@Test
	void aListEditorReadsBackWhatWasPutIn() throws Exception {
		var editors = new ListEditor[1];
		onEdt(() -> editors[0] = new ListEditor("hint", 4, List.of("one", "two", "three")));
		assertEquals(List.of("one", "two", "three"), editors[0].values());
	}

	@Test
	void blankLinesAndPaddingAreNotValues() throws Exception {
		var editors = new ListEditor[1];
		onEdt(() -> {
			editors[0] = new ListEditor(null, 4, null);
			editors[0].setValues(List.of("  spaced  ", "", "   ", "kept"));
		});
		assertEquals(List.of("spaced", "kept"), editors[0].values());
	}

	@Test
	void environmentLinesRoundTripAsAMap() throws Exception {

		var entries = new LinkedHashMap<String, String>();
		entries.put("API_KEY", "abc123");
		entries.put("MODE", "review");

		var editors = new ListEditor[1];
		onEdt(() -> editors[0] = new ListEditor(null, 4, ListEditor.fromMap(entries)));

		assertEquals(entries, editors[0].asMap());
	}

	@Test
	void aLineWithNoEqualsIsKeptRatherThanDropped() throws Exception {
		var editors = new ListEditor[1];
		onEdt(() -> {
			editors[0] = new ListEditor(null, 4, null);
			editors[0].setValues(List.of("JUST_A_NAME"));
		});
		assertEquals(Map.of("JUST_A_NAME", ""), editors[0].asMap());
	}

	@Test
	void aLineThatNamesNoVariableIsDroppedRatherThanBecomingABlankName() throws Exception {
		var editors = new ListEditor[1];
		onEdt(() -> {
			editors[0] = new ListEditor(null, 4, null);
			editors[0].setValues(List.of("=orphaned", "  =also orphaned", "REAL=value"));
		});
		// A blank name is not a variable, and handing one to the process builder makes
		// an agent fail to start for a reason nothing on screen explains.
		assertEquals(Map.of("REAL", "value"), editors[0].asMap());
	}

	@Test
	void aValueContainingAnEqualsKeepsIt() throws Exception {
		var editors = new ListEditor[1];
		onEdt(() -> {
			editors[0] = new ListEditor(null, 4, null);
			editors[0].setValues(List.of("QUERY=a=b"));
		});
		assertEquals(Map.of("QUERY", "a=b"), editors[0].asMap());
	}

	// ----------------------------------------------------------------- dialogs

	@Test
	void headlessDialogsAnswerForAnAbsentUserRatherThanThrowing() {
		assertTrue(Dialogs.isHeadless());
		assertTrue(Dialogs.confirm(null, "title", "message"));
		assertTrue(Dialogs.ask(null, "title", "message"));
		assertTrue(Dialogs.choose(null, "title", "message", "first", "second"));
		assertNull(Dialogs.input(null, "message", "initial"));
		Dialogs.message(null, "title", "message");
		Dialogs.error(null, "title", "message");
	}

	@Test
	void theMenuShortcutMaskIsUsableWithoutADisplay() {
		assertEquals(java.awt.event.InputEvent.CTRL_DOWN_MASK, Dialogs.menuShortcutMask());
	}

	// --------------------------------------------------------------- quick view

	private ProjectEntry project(String name, int agents) throws IOException {

		var root = Files.createDirectories(workspace.resolve(name));
		var project = ProjectCreator.define(name, root, ProjectStorageMode.PROJECT_LOCAL);
		for (var index = 0; index < agents; index++) {
			var agent = new AgentDefinition();
			agent.setId("a" + index);
			agent.setName("Agent " + index);
			project.getAgents().add(agent);
		}
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			if (agents > 0) {
				project.getAgents().getFirst().setProfileId(
						AiProjectScreenPluginTest.projectProfile(store, "Team Claude", "claude-code", null));
			}
			store.markProjectDirty();
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);
		return entry;
	}

	private String previewOf(AiProjectQuickViewPlugin plugin) {
		var scroll = (JScrollPane) plugin.panel().getComponent(0);
		return ((JEditorPane) scroll.getViewport().getView()).getText();
	}

	@Test
	void quickViewAcceptsOnlyProjectRows() {
		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		assertFalse(plugin.supports(AiProjectResource.root()));
		assertFalse(plugin.supports(null));
		assertTrue(plugin.supports(AiProjectResource.forProject(
				new ProjectEntry("id", "name", workspace.toString(), ProjectStorageMode.PROJECT_LOCAL))));
		plugin.unload();
	}

	@Test
	void quickViewReportsWhatDecidesWhichProjectToOpen() throws Exception {

		var entry = project("alpha", 2);
		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		plugin.init();

		onEdt(() -> assertTrue(plugin.openResource(
				AiProjectResource.forProject(entry), new AtomicBoolean(false))));

		var preview = previewOf(plugin);
		assertTrue(preview.contains("alpha"), preview);
		assertTrue(preview.contains("Team Claude"), preview);
		assertTrue(preview.contains("1 of 2"), preview);
		assertTrue(preview.contains("Agent 0"));
		assertTrue(preview.contains("never run"));
		plugin.unload();
	}

	@Test
	void quickViewNeverClaimsAnAgentIsRunning() throws Exception {

		var entry = project("alpha", 1);
		var paths = catalog.paths(entry);
		try (var store = dev.nuclr.plugin.core.ai.projects.store.ProjectStore.open(paths)) {
			var session = store.session("a0");
			session.setStatus(AgentStatus.RUNNING);
			session.setStartedAt(Instant.now());
			session.setRuntimeStamp("a-previous-run");
			store.markSessionDirty("a0");
			store.flush();
		}

		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));

		var preview = previewOf(plugin);
		// It cannot know whether that process still exists, so it describes history.
		assertTrue(preview.contains("last started"), preview);
		assertFalse(preview.toLowerCase().contains(">running<"));
		plugin.unload();
	}

	@Test
	void quickViewSaysSoWhenTheDefinitionCannotBeRead() throws Exception {

		var entry = project("alpha", 0);
		Files.writeString(catalog.paths(entry).projectFile(), "{ not json");

		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));

		assertTrue(previewOf(plugin).contains("could not be read"));
		plugin.unload();
	}

	@Test
	void quickViewSaysSoWhenTheProjectIsNoLongerListed() throws Exception {

		var stranger = AiProjectResource.forProject(
				new ProjectEntry("gone", "Gone", workspace.toString(), ProjectStorageMode.PROJECT_LOCAL));
		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> plugin.openResource(stranger, new AtomicBoolean(false)));

		assertTrue(previewOf(plugin).contains("no longer in the list"));
		plugin.unload();
	}

	@Test
	void aCancelledPreviewDoesNothing() throws Exception {
		var entry = project("alpha", 0);
		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> assertFalse(plugin.openResource(
				AiProjectResource.forProject(entry), new AtomicBoolean(true))));
		plugin.unload();
	}

	@Test
	void closingThePreviewClearsIt() throws Exception {

		var entry = project("alpha", 0);
		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
		onEdt(plugin::closeResource);

		assertNull(plugin.getCurrentResource());
		assertFalse(previewOf(plugin).contains("alpha"));
		plugin.unload();
	}

	@Test
	void aProjectNameWithMarkupIsEscapedRatherThanRendered() throws Exception {

		var root = Files.createDirectories(workspace.resolve("markup"));
		var project = ProjectCreator.define("<b>bold</b>", root, ProjectStorageMode.PROJECT_LOCAL);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.flush();
		}
		var entry = ProjectCreator.entry(project);
		catalog.register(entry);

		var plugin = new AiProjectQuickViewPlugin();
		plugin.preinit(context);
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));

		assertTrue(previewOf(plugin).contains("&lt;b&gt;bold&lt;/b&gt;"));
		plugin.unload();
	}
}
