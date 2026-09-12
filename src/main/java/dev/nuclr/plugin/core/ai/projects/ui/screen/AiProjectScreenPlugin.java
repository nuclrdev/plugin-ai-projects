package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FullscreenNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import lombok.extern.slf4j.Slf4j;

/**
 * The fullscreen AI project screen: opens a project and hands the window to its
 * {@link ProjectDesktop}.
 *
 * <p>Declared as an {@code Editor} rather than a {@code Viewer} because it
 * genuinely edits: agents are added, harnesses are changed, skills are written,
 * and the layout is saved as the user works. Calling it a viewer would be a
 * claim about immutability that this screen does not honour.
 *
 * <p>The plugin itself is thin. It owns the project store's lifetime and the
 * host contract; everything the user does belongs to the desktop.
 */
@Slf4j
public final class AiProjectScreenPlugin implements FullscreenNuclrPlugin {

	/** Manifest id. */
	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.ai.projects.screen";

	private final String uuid = UUID.randomUUID().toString();
	private final AgentWindowRegistry registry = new AgentWindowRegistry();
	private final JPanel root = new JPanel(new BorderLayout());

	private NuclrPluginContext context;
	private ProjectCatalog catalog;
	private ProjectDesktop desktop;
	private NuclrResource currentResource;
	private volatile boolean focused;

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.catalog = new ProjectCatalog(context.getSettings(), ProjectPaths.defaultCommanderHome());
	}

	@Override
	public void init() {
		// Nothing to set up until a project is opened.
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public JComponent panel() {
		return root;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return AiProjectResource.isProject(resource);
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String getWindowTitle() {
		return desktop == null ? "AI Project" : desktop.title();
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {

		if (!supports(resource) || cancelled != null && cancelled.get()) {
			return false;
		}

		closeDesktop();

		var projectId = AiProjectResource.projectId(resource);
		var entry = catalog.find(projectId).orElse(null);
		if (entry == null) {
			showMessage("This project is no longer in the list. Add it again from the AI Projects panel.");
			return true;
		}

		ProjectStore store;
		try {
			store = ProjectStore.open(catalog.paths(entry));
		} catch (IOException e) {
			log.warn("Could not open the AI project {}: {}", projectId, e.getMessage(), e);
			showMessage("Could not open this project: " + e.getMessage());
			return true;
		}

		this.currentResource = resource;

		// Building a frame per agent is not instant with a dozen of them, and it all
		// happens on the event thread, so say so rather than appearing to hang.
		root.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
		try {
			this.desktop = new ProjectDesktop(store, registry, context.getEventBus(), this::closeFromDesktop);
			root.removeAll();
			root.add(desktop, BorderLayout.CENTER);
		} catch (RuntimeException | LinkageError e) {
			// The store owns a writer thread and holds the project open. Leaving it
			// behind would keep this project looking open for the rest of the session,
			// and a second attempt would then be writing the same files twice.
			log.warn("Could not build the desktop for AI project {}: {}", projectId, e.getMessage(), e);
			store.close();
			this.currentResource = null;
			showMessage("Could not open this project's desktop: "
					+ (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
			return true;
		} finally {
			root.setCursor(java.awt.Cursor.getDefaultCursor());
		}
		root.revalidate();
		root.repaint();

		// The catalogue entry's name is only a cache of the definition's, so refresh it
		// whenever the definition is actually read.
		catalog.register(new dev.nuclr.plugin.core.ai.projects.store.ProjectEntry(
				entry.id(), store.project().displayName(), entry.root(), entry.storageMode()));

		SwingUtilities.invokeLater(desktop::requestFocusInWindow);
		return true;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		var items = new ArrayList<NuclrMenuResource>();
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.NEW, "New agent"), "F2",
				AiProjectEvents.SCREEN_NEW_AGENT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.TERMINAL, "Terminal"), "Shift+F2",
				AiProjectEvents.SCREEN_NEW_TERMINAL));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.SIDEBAR, "Sidebar"), "F3",
				AiProjectEvents.SCREEN_TOGGLE_SIDEBAR));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.SAVE, "Save layout"), "F4",
				AiProjectEvents.SCREEN_SAVE_LAYOUT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.START, "Start all"), "F5",
				AiProjectEvents.SCREEN_START_ALL));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.STOP, "Stop all"), "F6",
				AiProjectEvents.SCREEN_STOP_ALL));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.TILE, "Tile"), "F7",
				AiProjectEvents.SCREEN_TILE));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.CASCADE, "Cascade"), "F8",
				AiProjectEvents.SCREEN_CASCADE));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.BROADCAST, "Broadcast"), "F9",
				AiProjectEvents.SCREEN_BROADCAST));
		// Not F10: bare F10 is Commander's own "quit the application", handled by its
		// global key dispatcher before the function bar ever sees it. Close sits on
		// Ctrl+F4, and goes through this plugin's own action so it can confirm first -
		// the host's teardown cannot be vetoed once it starts.
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.CLOSE, "Close project"), "Ctrl+F4",
				AiProjectEvents.SCREEN_CLOSE));
		return items;
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (desktop == null) {
			return;
		}
		// Menu items, key bindings and host dispatch all land here, so a command
		// behaves the same however it was reached.
		switch (actionType) {
			case AiProjectEvents.SCREEN_NEW_AGENT -> desktop.newAgent(null);
			case AiProjectEvents.SCREEN_NEW_TERMINAL -> desktop.newTerminal(folderFrom(data));
			case AiProjectEvents.SCREEN_START_ALL -> desktop.startAll();
			case AiProjectEvents.SCREEN_STOP_ALL -> desktop.stopAll();
			case AiProjectEvents.SCREEN_TILE -> desktop.tile();
			case AiProjectEvents.SCREEN_CASCADE -> desktop.cascade();
			case AiProjectEvents.SCREEN_SAVE_LAYOUT -> desktop.saveLayout();
			case AiProjectEvents.SCREEN_RESET_LAYOUT -> desktop.resetLayout();
			case AiProjectEvents.SCREEN_BROADCAST -> desktop.broadcast();
			case AiProjectEvents.SCREEN_TOGGLE_SIDEBAR -> desktop.toggleSidebar();
			case AiProjectEvents.SCREEN_CLOSE -> desktop.requestClose();
			default -> log.debug("AI project screen ignoring action [{}]", actionType);
		}
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		if (desktop != null) {
			desktop.updateTheme();
		}
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return root.requestFocusInWindow();
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		closeDesktop();
		currentResource = null;
	}

	@Override
	public void unload() {
		closeDesktop();
		currentResource = null;
		context = null;
	}

	/**
	 * Shut the open project down.
	 *
	 * <p>Closing the screen terminates the agents. They are children of this
	 * process and there is nowhere for them to keep running once their windows
	 * are gone; leaving them alive would mean processes the user can no longer
	 * see or stop. What survives is their transcripts and session records.
	 */
	private void closeDesktop() {
		var open = desktop;
		desktop = null;
		if (open == null) {
			return;
		}
		try {
			open.close();
		} catch (RuntimeException e) {
			log.warn("Closing the AI project desktop failed: {}", e.getMessage(), e);
		}
		root.removeAll();
		root.revalidate();
		root.repaint();
	}

	/**
	 * The folder a terminal request named, or {@code null} for the project root.
	 *
	 * <p>Payload values are untyped and may have crossed a JSON round trip, so a
	 * string is accepted alongside a path and anything unusable is treated as
	 * absent rather than failing the action.
	 *
	 * @param data the action payload, possibly {@code null}
	 * @return the folder, or {@code null}
	 */
	private static java.nio.file.Path folderFrom(Map<String, Object> data) {
		var value = data == null ? null : data.get(AiProjectEvents.TERMINAL_FOLDER_KEY);
		if (value instanceof java.nio.file.Path path) {
			return path;
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return java.nio.file.Path.of(text.trim());
			} catch (RuntimeException e) {
				log.debug("Ignoring an unusable terminal folder: {}", text);
			}
		}
		return null;
	}

	/** The desktop asked to be closed, and has already confirmed it with the user. */
	private void closeFromDesktop() {
		if (context != null) {
			context.getEventBus().emit(AiProjectEvents.FULLSCREEN_CLOSE, java.util.Map.of(), null);
		}
	}

	private void showMessage(String message) {
		var label = new JLabel("<html>" + message.replace("<", "&lt;") + "</html>");
		label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
		label.setVerticalAlignment(JLabel.TOP);
		root.removeAll();
		root.add(label, BorderLayout.CENTER);
		root.revalidate();
		root.repaint();
	}
}
