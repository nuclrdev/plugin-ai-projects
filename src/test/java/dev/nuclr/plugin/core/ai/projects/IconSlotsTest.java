package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphIcon;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AgentFrame;
import dev.nuclr.plugin.core.ai.projects.ui.screen.AiProjectScreenPlugin;

/**
 * The open desktop, checked as a whole: every picture sits in an icon slot,
 * and none is left typed into the words beside it.
 *
 * <p>Walks the real component tree rather than trusting each widget's own
 * test, because the regression this guards against is one new button built the
 * old way.
 */
class IconSlotsTest {

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
		var project = ProjectCreator.define("project", root, ProjectStorageMode.PROJECT_LOCAL,
				"terminal.shell", null);
		var agent = new AgentDefinition();
		agent.setId("a0");
		agent.setName("Coder");
		agent.setWindowKind("terminal.shell");
		project.getAgents().add(agent);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.markProjectDirty();
			store.flush();
		}
		entry = ProjectCreator.entry(project);
		catalog.register(entry);
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	private static <T> void collect(Container container, Class<T> type, List<T> into) {
		for (var child : container.getComponents()) {
			if (type.isInstance(child)) {
				into.add(type.cast(child));
			}
			if (child instanceof Container nested) {
				collect(nested, type, into);
			}
		}
	}

	/** Run a look at the open desktop on the EDT, where Swing state may be read. */
	private <T> T inspect(java.util.function.Function<Component, T> look) throws Exception {
		var result = new AtomicReference<T>();
		onEdt(() -> plugin.openResource(AiProjectResource.forProject(entry), new AtomicBoolean(false)));
		try {
			onEdt(() -> result.set(look.apply(plugin.panel())));
		} finally {
			onEdt(plugin::closeResource);
		}
		return result.get();
	}

	private record Seen(String text, Icon icon) {
	}

	@Test
	void everyWordedButtonHasItsPictureInTheIconSlotAndNotInItsText() throws Exception {

		var buttons = inspect(root -> {
			var found = new ArrayList<AbstractButton>();
			collect((Container) root, AbstractButton.class, found);
			return found.stream()
					.filter(button -> button.getText() != null && !button.getText().isBlank())
					.map(button -> new Seen(button.getText(), button.getIcon()))
					.toList();
		});

		assertFalse(buttons.isEmpty(), "found no buttons to check");
		var glyphs = GlyphIconTest.declaredGlyphs();
		for (var button : buttons) {
			assertFalse(button.text().startsWith("<html>"), "still HTML: " + button.text());
			for (var glyph : glyphs) {
				assertFalse(button.text().contains(glyph), "a glyph typed into the words: " + button.text());
			}
			assertNotNull(button.icon(), "no picture on '" + button.text() + "'");
		}
	}

	@Test
	void anAgentFrameWearsItsStatusAsTheFrameIconAndKeepsItsTitleToWords() throws Exception {

		var seen = inspect(root -> {
			var frames = new ArrayList<AgentFrame>();
			collect((Container) root, AgentFrame.class, frames);
			return frames.isEmpty() ? null : new Seen(frames.get(0).getTitle(), frames.get(0).getFrameIcon());
		});

		assertNotNull(seen, "the agent's frame did not open");
		assertTrue(seen.text().startsWith("Coder"), "the title should start with the name: " + seen.text());
		var icon = assertInstanceOf(GlyphIcon.class, seen.icon());

		var statusGlyphs = new HashSet<String>();
		for (var status : AgentStatus.values()) {
			statusGlyphs.add(Glyphs.icon(Glyphs.statusGlyph(status, false)).glyph());
		}
		statusGlyphs.add(Glyphs.icon(Glyphs.ATTENTION).glyph());
		assertTrue(statusGlyphs.contains(icon.glyph()), "the frame icon is not a status: " + icon.glyph());
	}

	@Test
	void theStatusBarAndSectionHeadersCarryIconsAndNoStraySeparators() throws Exception {

		var labels = inspect(root -> {
			var found = new ArrayList<JLabel>();
			collect((Container) root, JLabel.class, found);
			return found.stream().map(label -> new Seen(label.getText(), label.getIcon())).toList();
		});

		// The status bar's figures, e.g. "0 running".
		assertTrue(labels.stream().anyMatch(label -> label.text() != null
				&& label.text().matches("\\d+ running") && label.icon() instanceof GlyphIcon),
				"no running count with an icon in " + labels);

		// The sidebar's section captions, e.g. "Agents   1".
		assertTrue(labels.stream().anyMatch(label -> label.text() != null
				&& label.text().startsWith("Agents") && label.icon() instanceof GlyphIcon),
				"no Agents caption with an icon in " + labels);

		// The unit separator joins a sidebar glyph to its words; it must never reach the screen.
		for (var label : labels) {
			assertFalse(label.text() != null && label.text().contains(Glyphs.SIDEBAR_SEPARATOR),
					"a raw separator on screen: " + label.text());
		}
	}
}
