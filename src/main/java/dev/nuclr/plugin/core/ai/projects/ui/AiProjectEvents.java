package dev.nuclr.plugin.core.ai.projects.ui;

/**
 * The event and action names this plugin owns, in one place.
 *
 * <p>Two conversations run over the host's event bus. Host actions - the ones
 * Commander itself dispatches, such as {@code filepanel.path.opened} - are
 * consumed under their own names. Everything this plugin invents is namespaced
 * under {@code ai.projects} or {@code plugin.ai.project}, as the core plugins
 * namespace theirs, so nothing here can collide with a host action or with
 * another plugin's.
 *
 * <p>The file panel and the fullscreen screen are separate plugin instances with
 * no reference to each other, so the desktop tells the panel what its agents are
 * doing through {@link #ACTIVITY} rather than through a shared object.
 */
public final class AiProjectEvents {

	/** Host action: the user activated the focused row. */
	public static final String PATH_OPENED = "filepanel.path.opened";

	/** Host action: navigate the panel to a resource. */
	public static final String NAVIGATE = "filepanel.navigate.to.path";

	/** Host action: refresh the panel that raised it. */
	public static final String REFRESH_PANEL = "refresh.panel";

	/** Host action: refresh a plugin-backed panel, by {@code plugin.uuid}. */
	public static final String REFRESH_PLUGIN_PANEL = "refresh.plugin.file.panel";

	/** Host action: open a resource in a fullscreen editor. */
	public static final String MAIN_PANEL_EDIT = "mainpanel.edit";

	/** Host action: close the fullscreen screen. */
	public static final String FULLSCREEN_CLOSE = "plugin.fullscreen.close";

	/** Host action: the panel became visible or hidden. */
	public static final String VISIBILITY_CHANGED = "filepanel.visibility.changed";

	/** Host action: hand over state to keep with the workspace. */
	public static final String WORKSPACE_SAVE_STATE = "workspace.save.state";

	/** Host action: take back the state kept with the workspace. */
	public static final String WORKSPACE_RESTORE_STATE = "workspace.restore.state";

	/** Host payload key for workspace state. */
	public static final String WORKSPACE_STATE_KEY = "workspace.state";

	/** Host payload key: the display name of the workspace a workspace-state action is for. */
	public static final String WORKSPACE_NAME_KEY = "workspace.name";

	/** Host payload key: the integer version of the workspace state, so it can be migrated. */
	public static final String WORKSPACE_STATE_VERSION_KEY = "workspace.state.version";

	/** Host payload key: the plugin's answer to {@link #WORKSPACE_RESTORE_STATE}. */
	public static final String WORKSPACE_RESTORE_RESULT_KEY = "workspace.restore.result";

	/** {@link #WORKSPACE_RESTORE_RESULT_KEY}: restored; open the resource that comes with it. */
	public static final String WORKSPACE_RESTORE_RESTORED = "restored";

	/** {@link #WORKSPACE_RESTORE_RESULT_KEY}: nothing left to restore; the host drops the entry. */
	public static final String WORKSPACE_RESTORE_SKIP = "skip";

	/** {@link #WORKSPACE_RESTORE_RESULT_KEY}: restoring failed; the host keeps the entry for next time. */
	public static final String WORKSPACE_RESTORE_FAILED = "failed";

	/** Host payload key: the resource the host should open for a restored entry. */
	public static final String WORKSPACE_RESTORE_RESOURCE_KEY = "workspace.restore.resource";

	/**
	 * Plugin to host: this instance's workspace state changed. Carries the instance
	 * {@code uuid()} under {@link #WORKSPACE_PLUGIN_UUID_KEY} and the state under
	 * {@link #WORKSPACE_STATE_KEY}, so the host can save it without calling back in.
	 */
	public static final String WORKSPACE_STATE_CHANGED = "workspace.state.changed";

	/** {@link #WORKSPACE_STATE_CHANGED} payload: the announcing instance's uuid. */
	public static final String WORKSPACE_PLUGIN_UUID_KEY = "plugin.uuid";

	/** Panel action: open the profile manager. */
	public static final String MANAGE_PROFILES = "ai.projects.profiles";

	/** Panel action: create a project. */
	public static final String NEW_PROJECT = "ai.projects.new";

	/** Panel action: register a folder that already holds a project definition. */
	public static final String ADD_EXISTING = "ai.projects.add.existing";

	/** Panel action: open the focused project's desktop. */
	public static final String OPEN_PROJECT = "ai.projects.open";

	/** Host action: folders copied into this panel from the other one. */
	public static final String ACCEPT_COPY = "accept.copy";

	/** Panel action: change a project's display name. */
	public static final String RENAME_PROJECT = "ai.projects.rename";

	/** Panel action: remove a project from the list, leaving its files alone. */
	public static final String FORGET_PROJECT = "ai.projects.forget";

	/** Panel action: delete a project's metadata directory. Destructive. */
	public static final String DELETE_PROJECT = "ai.projects.delete";

	/** Panel action: navigate the panel to the focused project's root folder. */
	public static final String REVEAL_ROOT = "ai.projects.reveal.root";

	/** Panel action: edit the focused project's definition. */
	public static final String EDIT_DEFINITION = "ai.projects.edit.definition";

	/**
	 * Desktop to panel: how many of a project's agents are running, waiting or
	 * failed. Payload keys are the {@code ACTIVITY_*} constants below.
	 */
	public static final String ACTIVITY = "ai.projects.activity";

	/** {@link #ACTIVITY} payload: the project id. */
	public static final String ACTIVITY_PROJECT_ID = "projectId";

	/** {@link #ACTIVITY} payload: number of agents defined. */
	public static final String ACTIVITY_AGENTS = "agents";

	/** {@link #ACTIVITY} payload: number of agents with a live process. */
	public static final String ACTIVITY_RUNNING = "running";

	/** {@link #ACTIVITY} payload: number of agents waiting for input. */
	public static final String ACTIVITY_WAITING = "waiting";

	/** {@link #ACTIVITY} payload: number of agents that failed. */
	public static final String ACTIVITY_FAILED = "failed";

	/** {@link #ACTIVITY} payload: whether an agent is asking for a decision. */
	public static final String ACTIVITY_ATTENTION = "attention";

	/** {@link #ACTIVITY} payload: whether the project's desktop is still open. */
	public static final String ACTIVITY_OPEN = "open";

	/** Either side: the set of projects changed and the panel should list again. */
	public static final String CATALOG_CHANGED = "ai.projects.catalog.changed";

	/** Screen action: add an agent. */
	public static final String SCREEN_NEW_AGENT = "plugin.ai.project.new.agent";

	/** Screen action: start every agent. */
	public static final String SCREEN_START_ALL = "plugin.ai.project.start.all";

	/** Screen action: stop every agent. */
	public static final String SCREEN_STOP_ALL = "plugin.ai.project.stop.all";

	/** Screen action: write the current window layout. */
	public static final String SCREEN_SAVE_LAYOUT = "plugin.ai.project.save.layout";

	/** Screen action: discard the saved layout and lay the windows out afresh. */
	public static final String SCREEN_RESET_LAYOUT = "plugin.ai.project.reset.layout";

	/** Screen action: tile the windows. */
	public static final String SCREEN_TILE = "plugin.ai.project.tile";

	/** Screen action: cascade the windows. */
	public static final String SCREEN_CASCADE = "plugin.ai.project.cascade";

	/** Screen action: send one instruction to several agents at once. */
	public static final String SCREEN_BROADCAST = "plugin.ai.project.broadcast";

	/** Screen action: open a plain shell in the project root. */
	public static final String SCREEN_NEW_TERMINAL = "plugin.ai.project.new.terminal";

	/**
	 * Optional {@link #SCREEN_NEW_TERMINAL} payload naming the folder the shell
	 * should start in, as a {@link java.nio.file.Path} or a string. Absent means
	 * the project root.
	 */
	public static final String TERMINAL_FOLDER_KEY = "folder";

	/** Screen action: close the project, confirming first when agents are running. */
	public static final String SCREEN_CLOSE = "plugin.ai.project.close";

	/** Screen action: show or hide the project sidebar. */
	public static final String SCREEN_TOGGLE_SIDEBAR = "plugin.ai.project.toggle.sidebar";

	private AiProjectEvents() {
	}
}
