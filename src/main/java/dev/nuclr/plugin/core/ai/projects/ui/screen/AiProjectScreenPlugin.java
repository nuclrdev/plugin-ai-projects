package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
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

	/** Version of the state this screen keeps with a Commander workspace. */
	public static final int WORKSPACE_STATE_VERSION = 1;

	/** Workspace state key: the id of the project that was open. */
	public static final String STATE_PROJECT_ID = "projectId";

	private final String uuid = UUID.randomUUID().toString();
	private final AgentWindowRegistry registry = new AgentWindowRegistry();
	private final JPanel root = new JPanel(new BorderLayout());

	private final ProjectLocks locks;

	private NuclrPluginContext context;
	private ProjectCatalog catalog;
	private ProjectDesktop desktop;
	private NuclrResource currentResource;
	private volatile boolean focused;

	/** The project this screen holds in {@link ProjectLocks}, or {@code null}. */
	private String lockedProjectId;

	/** This screen's workspace name, learned from the host's workspace-state actions; may stay unknown. */
	private volatile String workspaceName;

	/** Tells the user a project is already open elsewhere; a popup, except in tests. */
	private final Consumer<String> alreadyOpenNotice;

	/** The instance Commander creates: one lock registry shared by every workspace. */
	public AiProjectScreenPlugin() {
		this(ProjectLocks.shared());
	}

	/**
	 * A screen over a given lock registry, so tests can keep their locks to themselves.
	 *
	 * @param locks the registry deciding which screen may open which project
	 */
	public AiProjectScreenPlugin(ProjectLocks locks) {
		this(locks, AiProjectScreenPlugin::showAlreadyOpenPopup);
	}

	/**
	 * A screen over a given lock registry that reports a project open elsewhere through
	 * {@code alreadyOpenNotice} instead of a popup.
	 *
	 * @param locks             the registry deciding which screen may open which project
	 * @param alreadyOpenNotice receives the message a popup would have shown
	 */
	public AiProjectScreenPlugin(ProjectLocks locks, Consumer<String> alreadyOpenNotice) {
		this.locks = locks;
		this.alreadyOpenNotice = alreadyOpenNotice;
	}

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

		var holder = locks.tryAcquire(entry.id(), uuid, workspaceName);
		if (holder.isPresent()) {
			log.info("AI project {} is already open in another workspace; not opening it twice", projectId);
			// Nothing is open here, and nothing is kept: this workspace must not try again next time.
			this.currentResource = null;
			announceWorkspaceState();
			var message = alreadyOpenMessage(entry.name(), holder.get().workspaceName());
			// Not a screen of its own: give the window straight back to the panels and say why
			// over them. Deferred, because the host is still in the middle of showing this
			// screen and would ignore a close that arrived before it had.
			SwingUtilities.invokeLater(() -> {
				closeFromDesktop();
				alreadyOpenNotice.accept(message);
			});
			return true;
		}
		lockedProjectId = entry.id();

		ProjectStore store;
		try {
			store = ProjectStore.open(catalog.paths(entry));
		} catch (IOException e) {
			log.warn("Could not open the AI project {}: {}", projectId, e.getMessage(), e);
			releaseLock();
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
			releaseLock();
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

		announceWorkspaceState();

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

		// Workspace state is asked for whether or not a project is open, and a restore
		// arrives before one is - possibly off the event thread.
		if (AiProjectEvents.WORKSPACE_SAVE_STATE.equals(actionType)) {
			saveWorkspaceState(data);
			return;
		}
		if (AiProjectEvents.WORKSPACE_RESTORE_STATE.equals(actionType)) {
			restoreWorkspaceState(data);
			return;
		}

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
		releaseLock();
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

	/**
	 * The state Commander keeps for this screen in a workspace: which project was open.
	 *
	 * @param projectId the open project's id
	 * @return a settings-storable map
	 */
	public static Map<String, Object> workspaceState(String projectId) {
		var state = new LinkedHashMap<String, Object>();
		state.put(STATE_PROJECT_ID, projectId);
		return state;
	}

	private void releaseLock() {
		var projectId = lockedProjectId;
		lockedProjectId = null;
		locks.release(projectId, uuid);
	}

	private static void showAlreadyOpenPopup(String message) {
		Dialogs.notice(null, "Project already open", message);
	}

	/**
	 * What a second workspace is told instead of opening a project that is already open.
	 *
	 * @param projectName     the project's display name
	 * @param holderWorkspace the workspace holding it, or {@code null} when not known
	 * @return the message
	 */
	public static String alreadyOpenMessage(String projectName, String holderWorkspace) {
		var where = holderWorkspace == null ? "another workspace" : "the workspace \"" + holderWorkspace + "\"";
		return "\"" + projectName + "\" is already open in " + where + ". "
				+ "A project can be open in one workspace at a time, so its agents are never started twice. "
				+ "Switch to that workspace to work on it, or close it there and open it here again.";
	}

	/** The host names the workspace on its workspace-state actions; remember it for the lock. */
	private void rememberWorkspace(Map<String, Object> data) {
		if (data != null && data.get(AiProjectEvents.WORKSPACE_NAME_KEY) instanceof String name && !name.isBlank()) {
			workspaceName = name;
		}
	}

	/** The pull fallback; Commander normally has the state already from {@link #announceWorkspaceState()}. */
	private void saveWorkspaceState(Map<String, Object> data) {
		rememberWorkspace(data);
		var projectId = AiProjectResource.projectId(currentResource);
		if (data == null || projectId == null) {
			return;
		}
		data.put(AiProjectEvents.WORKSPACE_STATE_KEY, workspaceState(projectId));
		data.put(AiProjectEvents.WORKSPACE_STATE_VERSION_KEY, WORKSPACE_STATE_VERSION);
	}

	/**
	 * Turn a saved project id back into the row that opens it.
	 *
	 * <p>Commander may ask off the event thread, so this only reads the catalogue; the
	 * desktop itself is built when the host opens the resource. A project the user has
	 * since forgotten is skipped rather than failed: it is not coming back.
	 */
	private void restoreWorkspaceState(Map<String, Object> data) {

		if (data == null) {
			return;
		}
		rememberWorkspace(data);

		var projectId = data.get(AiProjectEvents.WORKSPACE_STATE_KEY) instanceof Map<?, ?> state
				&& state.get(STATE_PROJECT_ID) instanceof String id && !id.isBlank() ? id : null;
		var entry = projectId == null || catalog == null ? null : catalog.find(projectId).orElse(null);

		if (entry == null) {
			log.info("Not reopening AI project {}: it is no longer in the list", projectId);
			data.put(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY, AiProjectEvents.WORKSPACE_RESTORE_SKIP);
			return;
		}

		var holder = locks.holder(entry.id()).filter(current -> !uuid.equals(current.ownerId()));
		if (holder.isPresent()) {
			// Already open in another workspace, which got there first: this one comes back
			// on its panels rather than on a screen explaining why the project is not here.
			log.info("Not reopening AI project {} here: it is already open in another workspace", projectId);
			data.put(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY, AiProjectEvents.WORKSPACE_RESTORE_SKIP);
			return;
		}

		data.put(AiProjectEvents.WORKSPACE_RESTORE_RESULT_KEY, AiProjectEvents.WORKSPACE_RESTORE_RESTORED);
		data.put(AiProjectEvents.WORKSPACE_RESTORE_RESOURCE_KEY, AiProjectResource.forProject(entry));
	}

	/** Tell Commander which project this instance shows, so saving a workspace never has to ask. */
	private void announceWorkspaceState() {
		if (context == null) {
			return;
		}
		var projectId = AiProjectResource.projectId(currentResource);
		var payload = new HashMap<String, Object>();
		payload.put(AiProjectEvents.WORKSPACE_PLUGIN_UUID_KEY, uuid);
		payload.put(AiProjectEvents.WORKSPACE_STATE_KEY, projectId == null ? null : workspaceState(projectId));
		payload.put(AiProjectEvents.WORKSPACE_STATE_VERSION_KEY, WORKSPACE_STATE_VERSION);
		context.getEventBus().emit(this, AiProjectEvents.WORKSPACE_STATE_CHANGED, payload);
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
