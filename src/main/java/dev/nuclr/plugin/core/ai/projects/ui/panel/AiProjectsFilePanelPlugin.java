package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import lombok.extern.slf4j.Slf4j;

/**
 * The panel that lists AI projects.
 *
 * <p>Each row is a project: its name, what its agents are doing, which harness
 * and model it runs, where its metadata lives and when it was last opened.
 * Activating a row opens the project's desktop as a fullscreen screen - a
 * project is not a folder, so navigating "into" one would be the wrong gesture.
 *
 * <p>The panel knows nothing about live agents. It shows what an open desktop
 * reports over the event bus, and treats a project with no desktop open as idle
 * rather than guessing.
 */
@Slf4j
public final class AiProjectsFilePanelPlugin implements FilePanelNuclrPlugin, NuclrEventListener {

	/** Manifest id; also this plugin's settings namespace. */
	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.ai.projects.panel";

	private static final List<String> COLUMNS =
			List.of("Name", "Status", "Agents", "Harness", "Model", "Storage", "Last opened", "Root");

	/**
	 * Metadata keys Commander's own comparators read.
	 *
	 * <p>The host owns the comparators and keys them to fixed metadata names, so a
	 * plugin makes one of its columns sortable by publishing that column's value
	 * under the name the matching comparator reads. Status is therefore mirrored
	 * into {@code Description} and Harness into {@code Owner}; the columns the user
	 * sees are still Status and Harness, and the sort descriptors below point the
	 * header arrow at them.
	 */
	private static final String SORT_KEY_STATUS = "Description";

	/** @see #SORT_KEY_STATUS */
	private static final String SORT_KEY_HARNESS = "Owner";

	private final String uuid = UUID.randomUUID().toString();
	private final Map<String, ProjectActivity> activity = new ConcurrentHashMap<>();
	private final AgentWindowRegistry windowRegistry = new AgentWindowRegistry();

	private NuclrPluginContext context;
	private ProjectCatalog catalog;
	private volatile NuclrResource currentResource = AiProjectResource.root();
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
		context.getEventBus().subscribe(this);
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		activity.clear();
		context = null;
	}

	@Override
	public void closeResource() {
		// The panel holds no project open; the fullscreen screen owns that.
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return AiProjectResource.isOurs(resource);
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
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
	public String getWindowTitle() {
		return "AI Projects";
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var item = new MenuItem();
		item.setText("AI Projects");
		item.setUuid(AiProjectResource.ROOT_UUID);
		item.setPath(AiProjectResource.root());
		var holder = new MenuItemsHolder();
		holder.setTitle("AI Projects");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		return "AI Projects";
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		var projects = selectedResources == null ? List.<NuclrResource>of()
				: selectedResources.stream().filter(AiProjectResource::isProject).toList();
		if (projects.isEmpty()) {
			var total = catalog == null ? 0 : catalog.entries().size();
			return total + (total == 1 ? " project" : " projects");
		}
		var agents = projects.stream().mapToLong(NuclrResource::getLength).sum();
		return projects.size() + (projects.size() == 1 ? " project selected, " : " projects selected, ")
				+ agents + (agents == 1 ? " agent" : " agents");
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {

		if (!supports(resourceToOpen) || cancelled(cancelled)) {
			return null;
		}

		// Only the list root is navigable. Activating a project row opens its desktop,
		// which happens in act(), so a project never becomes "the current directory".
		if (!AiProjectResource.isRoot(resourceToOpen)) {
			return null;
		}

		var data = new NuclrResourceData();
		data.setColumnNames(COLUMNS);
		if (sink != null) {
			sink.columns(COLUMNS);
		}

		var entries = new ArrayList<NuclrResource>();
		for (var entry : catalog.entries()) {
			if (cancelled(cancelled)) {
				return null;
			}
			var row = describe(entry);
			entries.add(row);
			if (sink != null) {
				sink.add(row);
			}
		}
		if (entries.isEmpty()) {
			var hint = describeHint();
			entries.add(hint);
			if (sink != null) {
				sink.add(hint);
			}
		}
		data.setEntries(entries);
		currentResource = AiProjectResource.root();
		return data;
	}

	/** Build one row, reading the project definition afresh so hand edits show up. */
	private AiProjectResource describe(ProjectEntry entry) {

		var row = AiProjectResource.forProject(entry);
		var definition = catalog.peek(entry).orElse(null);
		var report = activity.getOrDefault(entry.id(), ProjectActivity.CLOSED);
		var rootExists = Files.isDirectory(entry.rootPath());
		var status = status(definition, rootExists, report);
		var harness = harness(definition);
		var lastOpened = catalog.lastOpenedAt(entry);
		var agentCount = definition == null ? 0 : definition.getAgents().size();

		row.getMetadata().put("Name", definition == null ? entry.name() : definition.displayName());
		row.getMetadata().put("Status", Glyphs.label(statusGlyph(definition, rootExists, report), status));
		row.getMetadata().put("Agents", agents(definition, report));
		row.getMetadata().put("Harness", harness);
		row.getMetadata().put("Model", model(definition));
		row.getMetadata().put("Storage", entry.storageMode().label());
		row.getMetadata().put("Last opened", Timestamps.relative(lastOpened));
		row.getMetadata().put("Root", entry.root());

		// Fields the host's own comparators read. See SORT_KEY_STATUS. These stay
		// undecorated: sorting by status should order by the word, not by whichever
		// codepoint happens to sit in front of it.
		row.getMetadata().put(SORT_KEY_STATUS, status);
		row.getMetadata().put(SORT_KEY_HARNESS, harness);
		row.setLength(agentCount);
		row.setLastModifiedDateTime(toLocal(lastOpened));

		if (definition != null) {
			row.setName(definition.displayName());
		}
		row.setReadable(rootExists);
		return row;
	}

	/** The row shown when the catalogue is empty, filled in so no column renders as "-". */
	private AiProjectResource describeHint() {
		var hint = AiProjectResource.hint();
		hint.getMetadata().put("Name", Glyphs.label(Glyphs.NEW, hint.getName()));
		hint.getMetadata().put("Status", "");
		hint.getMetadata().put("Agents", "");
		hint.getMetadata().put("Harness", "");
		hint.getMetadata().put("Model", "");
		hint.getMetadata().put("Storage", "");
		hint.getMetadata().put("Last opened", "");
		hint.getMetadata().put("Root", "F7 for a new project, or copy a folder here with F5");
		hint.getMetadata().put(SORT_KEY_STATUS, "");
		hint.getMetadata().put(SORT_KEY_HARNESS, "");
		return hint;
	}

	private static LocalDateTime toLocal(String isoInstant) {
		if (isoInstant == null || isoInstant.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.ofInstant(Instant.parse(isoInstant.trim()), ZoneId.systemDefault());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The glyph that goes in front of a row's status. */
	private static String statusGlyph(AiProject definition, boolean rootExists, ProjectActivity report) {
		if (definition == null || !rootExists) {
			return Glyphs.MISSING;
		}
		if (report.failed() > 0) {
			return Glyphs.FAILED;
		}
		if (report.attention() || report.waiting() > 0) {
			return Glyphs.WAITING;
		}
		if (report.running() > 0) {
			return Glyphs.RUNNING;
		}
		return report.open() ? Glyphs.STARTING : Glyphs.STOPPED;
	}

	private static String status(AiProject definition, boolean rootExists, ProjectActivity report) {
		if (definition == null) {
			return "Definition missing";
		}
		if (!rootExists) {
			return "Root missing";
		}
		return report.statusLabel();
	}

	private static String agents(AiProject definition, ProjectActivity report) {
		var defined = definition == null ? 0 : definition.getAgents().size();
		if (!report.open()) {
			return String.valueOf(defined);
		}
		var detail = new StringBuilder(String.valueOf(defined));
		detail.append(" (").append(report.running()).append(" running");
		if (report.waiting() > 0) {
			detail.append(", ").append(report.waiting()).append(" waiting");
		}
		if (report.failed() > 0) {
			detail.append(", ").append(report.failed()).append(" failed");
		}
		return detail.append(')').toString();
	}

	private static String harness(AiProject definition) {
		if (definition == null) {
			return "-";
		}
		var resolved = HarnessResolver.resolveProject(definition);
		if (resolved.executable() != null && !resolved.executable().isBlank()) {
			return resolved.executable();
		}
		return resolved.provider() == null ? "-" : resolved.provider();
	}

	private static String model(AiProject definition) {
		if (definition == null) {
			return "-";
		}
		var model = HarnessResolver.resolveProject(definition).model();
		return model == null || model.isBlank() ? "-" : model;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {

		var items = new ArrayList<NuclrMenuResource>();
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.REFRESH, "Refresh"), "F2",
				AiProjectEvents.REFRESH_PANEL));
		// F3 and F4 both open the project, as Enter does: its desktop is the one place a
		// project is both viewed and edited. The raw definition file sits beside them.
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.AGENT, "Open"), "F3",
				AiProjectEvents.OPEN_PROJECT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.AGENT, "Open"), "F4",
				AiProjectEvents.OPEN_PROJECT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.EDIT, "Definition"), "Shift+F4",
				AiProjectEvents.EDIT_DEFINITION));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.RENAME, "Rename"), "F6",
				AiProjectEvents.RENAME_PROJECT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.NEW, "New project"), "F7",
				AiProjectEvents.NEW_PROJECT));
		items.add(new NuclrMenuResource(Glyphs.label(Glyphs.CLOSE, "Forget"), "F8",
				AiProjectEvents.FORGET_PROJECT));

		// Commander owns the comparators; a plugin declares which of its columns each
		// one applies to and omits the ones it cannot back. These also drive the
		// column-header click sort, so a header with no descriptor stays inert on
		// purpose rather than by oversight.
		items.add(sortByColumn("Name", "Ctrl+F3", "name"));
		items.add(sortByColumn("Status", "Ctrl+F4", "description"));
		items.add(sortByColumn("Last opened", "Ctrl+F5", "modified"));
		items.add(sortByColumn("Agents", "Ctrl+F6", "size"));
		items.add(new NuclrMenuResource("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(sortByColumn("Harness", "Ctrl+F8", "owner"));
		items.add(new NuclrMenuResource("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
		return items;
	}

	private static NuclrMenuResource sortByColumn(String columnName, String functionKey, String criterion) {
		return new NuclrMenuResource(columnName, functionKey, "filepanel.sort:" + criterion + ":" + columnName);
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource,
			List<NuclrResource> selectedResources) {

		var items = new ArrayList<NuclrContextMenuItem>();
		var onProject = AiProjectResource.isProject(focusedResource);

		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.AGENT, "Open project")).iconKey("open")
				.actionType(AiProjectEvents.OPEN_PROJECT).enabled(onProject).build());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.FOLDER, "Open project root in this panel")).iconKey("folder")
				.actionType(AiProjectEvents.REVEAL_ROOT).enabled(onProject).build());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.RENAME, "Rename project...")).iconKey("rename")
				.actionType(AiProjectEvents.RENAME_PROJECT).enabled(onProject).build());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.EDIT, "Edit project definition")).iconKey("edit")
				.actionType(AiProjectEvents.EDIT_DEFINITION).enabled(onProject).build());
		items.add(NuclrContextMenuItem.separator());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.NEW, "New project...")).iconKey("new")
				.actionType(AiProjectEvents.NEW_PROJECT).build());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.PROJECT, "Add existing project...")).iconKey("add")
				.actionType(AiProjectEvents.ADD_EXISTING).build());
		items.add(NuclrContextMenuItem.separator());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.CLOSE, "Forget project")).iconKey("remove")
				.actionType(AiProjectEvents.FORGET_PROJECT).enabled(onProject).build());
		items.add(NuclrContextMenuItem.builder()
				.label(Glyphs.label(Glyphs.DELETE, "Delete project metadata...")).iconKey("delete")
				.actionType(AiProjectEvents.DELETE_PROJECT).enabled(onProject).destructive(true).build());
		return items;
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		switch (actionType) {
			case AiProjectEvents.PATH_OPENED -> open(focusedResource, data);
			case AiProjectEvents.OPEN_PROJECT -> openProject(focusedResource);
			case AiProjectEvents.NEW_PROJECT -> newProject(data);
			case AiProjectEvents.ADD_EXISTING -> addExisting(data);
			case AiProjectEvents.ACCEPT_COPY -> adoptFolders(selectedResources, focusedResource, data);
			case AiProjectEvents.RENAME_PROJECT -> rename(focusedResource, data);
			case AiProjectEvents.FORGET_PROJECT -> forget(focusedResource, data);
			case AiProjectEvents.DELETE_PROJECT -> delete(focusedResource, data);
			case AiProjectEvents.REVEAL_ROOT -> revealRoot(focusedResource);
			case AiProjectEvents.EDIT_DEFINITION -> editDefinition(focusedResource);
			case AiProjectEvents.REFRESH_PANEL -> requestRefresh(data);
			case AiProjectEvents.WORKSPACE_SAVE_STATE -> saveWorkspaceState(data);
			case AiProjectEvents.WORKSPACE_RESTORE_STATE -> restoreWorkspaceState(data);
			default -> log.debug("AI Projects panel ignoring action [{}]", actionType);
		}
	}

	/** Enter: open a project, or offer to create one when the list is empty. */
	private void open(NuclrResource resource, Map<String, Object> data) {
		if (AiProjectResource.isHint(resource)) {
			newProject(data);
			return;
		}
		openProject(resource);
	}

	/** Hand the project to the host, which opens the fullscreen desktop for it. */
	private void openProject(NuclrResource resource) {
		if (!AiProjectResource.isProject(resource) || context == null) {
			return;
		}
		context.getEventBus().emit(AiProjectEvents.MAIN_PANEL_EDIT, Map.of("resource", resource), null);
	}

	private void newProject(Map<String, Object> data) {
		var created = ProjectDialogs.createProject(catalog, windowRegistry, null);
		if (created == null) {
			return;
		}
		register(created, data, true);
	}

	/**
	 * Create a project and, unless told otherwise, offer to open it.
	 *
	 * <p>Someone who has just described a project almost always wants to be in it,
	 * and finding the new row by hand afterwards is busywork.
	 */
	private void register(AiProject created, Map<String, Object> data, boolean offerToOpen) {

		try (var store = ProjectCreator.create(created, catalog.commanderHome())) {
			store.flush();
		} catch (IOException e) {
			log.warn("Could not create the project at {}: {}", created.getRoot(), e.getMessage(), e);
			ProjectDialogs.error("New project", "Could not create the project: " + e.getMessage());
			return;
		}
		var entry = ProjectCreator.entry(created);
		catalog.register(entry);
		requestRefresh(data);

		if (offerToOpen && ProjectDialogs.confirm("New project",
				"Created '" + created.displayName() + "'.\n\nOpen it now?")) {
			openProject(AiProjectResource.forProject(entry));
		}
	}

	private void addExisting(Map<String, Object> data) {
		var folder = ProjectDialogs.chooseFolder("Add existing AI project", null);
		if (folder == null) {
			return;
		}
		var adopted = catalog.adoptIfProjectFolder(folder);
		if (adopted.isEmpty()) {
			if (ProjectDialogs.confirm("Add existing project",
					"No AI project definition was found in\n" + folder + "\n\nCreate one there?")) {
				var created = ProjectDialogs.createProject(catalog, windowRegistry, folder);
				if (created != null) {
					register(created, data, true);
				}
			}
			return;
		}
		requestRefresh(data);
	}

	/**
	 * Accept folders copied in from the other panel (F5), turning each into a
	 * project.
	 *
	 * <p>Commander is a file manager, so the natural way to say "make this folder
	 * an AI project" is to stand on it and copy it here - rather than being sent
	 * to a file chooser to find a folder the other panel is already showing.
	 * Folders that already carry a definition are adopted instead of re-created.
	 */
	private void adoptFolders(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {

		var sources = new ArrayList<NuclrResource>();
		if (selectedResources != null) {
			sources.addAll(selectedResources);
		}
		if (sources.isEmpty() && focusedResource != null) {
			sources.add(focusedResource);
		}

		var folders = sources.stream()
				.map(NuclrResource::getPath)
				.filter(path -> path != null && Files.isDirectory(path))
				.map(path -> path.toAbsolutePath().normalize())
				.distinct()
				.toList();

		if (folders.isEmpty()) {
			ProjectDialogs.error("New AI project",
					"Only local folders can be turned into AI projects. Select a folder and copy it here.");
			return;
		}

		for (var folder : folders) {
			if (catalog.adoptIfProjectFolder(folder).isPresent()) {
				continue;
			}
			var created = ProjectDialogs.createProject(catalog, windowRegistry, folder);
			if (created == null) {
				break;
			}
			register(created, data, folders.size() == 1);
		}
		requestRefresh(data);
	}

	private void rename(NuclrResource resource, Map<String, Object> data) {

		var projectId = AiProjectResource.projectId(resource);
		var entry = projectId == null ? null : catalog.find(projectId).orElse(null);
		if (entry == null) {
			return;
		}
		var definition = catalog.peek(entry).orElse(null);
		if (definition == null) {
			ProjectDialogs.error("Rename project", "This project's definition could not be read.");
			return;
		}
		var name = ProjectDialogs.prompt("Rename project", "New name:", definition.displayName());
		if (name == null || name.equals(definition.getName())) {
			return;
		}

		try (var store = ProjectStore.open(catalog.paths(entry))) {
			store.project().setName(name);
			store.markProjectDirty();
			store.flush();
		} catch (IOException e) {
			log.warn("Could not rename the project {}: {}", projectId, e.getMessage(), e);
			ProjectDialogs.error("Rename project", "Could not save the new name: " + e.getMessage());
			return;
		}
		catalog.register(new ProjectEntry(entry.id(), name, entry.root(), entry.storageMode()));
		requestRefresh(data);
	}

	private void forget(NuclrResource resource, Map<String, Object> data) {
		var projectId = AiProjectResource.projectId(resource);
		if (projectId == null) {
			return;
		}
		if (!ProjectDialogs.confirm("Forget project",
				"Remove '" + resource.getName() + "' from the list?\n\nIts files are left untouched.")) {
			return;
		}
		catalog.forget(projectId);
		activity.remove(projectId);
		requestRefresh(data);
	}

	private void delete(NuclrResource resource, Map<String, Object> data) {

		var projectId = AiProjectResource.projectId(resource);
		if (projectId == null || catalog.find(projectId).isEmpty()) {
			return;
		}
		var entry = catalog.find(projectId).orElseThrow();
		var metadata = catalog.paths(entry).metadataDirectory();

		if (!ProjectDialogs.confirmDestructive("Delete project metadata",
				"Delete the AI project metadata in\n\n" + metadata
						+ "\n\nThis removes the project definition, its instructions, skills, layout and"
						+ " transcripts. Nothing else in the project folder is touched. This cannot be undone.")) {
			return;
		}

		try {
			deleteRecursively(metadata);
		} catch (IOException e) {
			log.warn("Could not delete {}: {}", metadata, e.getMessage(), e);
			ProjectDialogs.error("Delete project metadata", "Could not delete " + metadata + ": " + e.getMessage());
			return;
		}
		catalog.forget(projectId);
		activity.remove(projectId);
		requestRefresh(data);
	}

	/** Depth-first delete, bounded to the metadata directory the caller confirmed. */
	private static void deleteRecursively(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			var files = walk.sorted(java.util.Comparator.reverseOrder()).toList();
			for (var file : files) {
				Files.deleteIfExists(file);
			}
		}
	}

	private void revealRoot(NuclrResource resource) {
		if (!(resource instanceof AiProjectResource project) || context == null) {
			return;
		}
		var folder = LocalFolderResource.of(project.projectRoot());
		if (folder == null) {
			ProjectDialogs.error("Open project root", "The project root is not there: " + project.getFullPath());
			return;
		}
		context.getEventBus().emit(this, AiProjectEvents.PATH_OPENED, Map.of("resource", folder));
	}

	private void editDefinition(NuclrResource resource) {
		var projectId = AiProjectResource.projectId(resource);
		if (projectId == null || context == null) {
			return;
		}
		catalog.find(projectId).ifPresent(entry -> {
			var file = catalog.paths(entry).projectFile();
			var definition = LocalFileResource.of(file);
			if (definition == null) {
				ProjectDialogs.error("Project definition", "There is no definition at " + file + ".");
				return;
			}
			context.getEventBus().emit(AiProjectEvents.MAIN_PANEL_EDIT, Map.of("resource", definition), null);
		});
	}

	private void requestRefresh(Map<String, Object> data) {
		if (data != null) {
			data.put("result.refresh", true);
		}
		if (context != null) {
			context.getEventBus().emit(AiProjectEvents.REFRESH_PLUGIN_PANEL, Map.of("plugin.uuid", uuid), null);
		}
	}

	/**
	 * Keep the panel's own state with the workspace: which project row was
	 * focused. An AI project is a domain object with a life of its own, so the
	 * workspace remembers only which one was being looked at, never the desktop
	 * inside it - that belongs to the project and travels with it.
	 */
	private void saveWorkspaceState(Map<String, Object> data) {
		if (data == null) {
			return;
		}
		var focusedProject = AiProjectResource.projectId(currentResource);
		data.put(AiProjectEvents.WORKSPACE_STATE_KEY, ProjectCatalog.workspaceState(focusedProject));
	}

	private void restoreWorkspaceState(Map<String, Object> data) {
		if (data == null || !(data.get(AiProjectEvents.WORKSPACE_STATE_KEY) instanceof Map<?, ?> state)) {
			return;
		}
		var projectId = state.get("openProjectId");
		if (projectId != null) {
			log.debug("Workspace remembered AI project {}", projectId);
		}
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData,
			NuclrPluginCallback callback) {

		if (AiProjectEvents.ACTIVITY.equals(type)) {
			var projectId = eventData == null ? null : eventData.get(AiProjectEvents.ACTIVITY_PROJECT_ID);
			if (projectId == null) {
				return;
			}
			// A busy desktop reports every status change, and most of them leave this
			// panel's row reading exactly as before. Only a report that would change
			// what is on screen is worth a relisting.
			var report = ProjectActivity.fromPayload(eventData);
			var previous = activity.put(String.valueOf(projectId), report);
			if (!report.equals(previous)) {
				SwingUtilities.invokeLater(() -> requestRefresh(new HashMap<>()));
			}
			return;
		}
		if (AiProjectEvents.CATALOG_CHANGED.equals(type)) {
			SwingUtilities.invokeLater(() -> requestRefresh(new HashMap<>()));
		}
	}

	@Override
	public boolean isMessageSupported(String type) {
		return AiProjectEvents.ACTIVITY.equals(type) || AiProjectEvents.CATALOG_CHANGED.equals(type);
	}

	private static boolean cancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}
}
