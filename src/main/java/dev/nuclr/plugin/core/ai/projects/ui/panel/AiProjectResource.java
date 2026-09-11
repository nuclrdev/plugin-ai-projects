package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectEntry;

/**
 * A row in the AI Projects panel: the list's root, one project, or the hint
 * shown when there are no projects yet.
 *
 * <p>Project rows are deliberately <em>not</em> folders and carry no local
 * {@link NuclrResource#getPath()}. A project is not a directory listing - it is
 * a desktop - so activating one has to reach the fullscreen screen rather than
 * navigate into the folder, and leaving the path unset is what keeps Commander
 * from treating it as an ordinary file on the way there. The root folder is
 * still recorded, in the metadata and in {@link #projectRoot()}, for the actions
 * that do want it.
 */
public final class AiProjectResource extends NuclrResource {

	/** Metadata key marking which kind of row this is. */
	public static final String KIND = "ai.projects.kind";

	/** The list root. */
	public static final String KIND_ROOT = "root";

	/** One project. */
	public static final String KIND_PROJECT = "project";

	/** The "no projects yet" row. */
	public static final String KIND_HINT = "hint";

	/** Uuid of the singleton root resource. */
	public static final String ROOT_UUID = "ai-projects:root";

	/** Uuid of the singleton hint row. */
	public static final String HINT_UUID = "ai-projects:hint";

	/** Metadata key holding a project's id. */
	public static final String PROJECT_ID = "ai.projects.id";

	/** Metadata key holding a project's root folder. */
	public static final String PROJECT_ROOT = "ai.projects.root";

	/** Metadata key holding a project's storage mode. */
	public static final String PROJECT_STORAGE = "ai.projects.storage";

	private AiProjectResource() {
		super(null);
	}

	/**
	 * The list root, which the panel opens to show every registered project.
	 *
	 * @return a new root resource
	 */
	public static AiProjectResource root() {
		var resource = new AiProjectResource();
		resource.setUuid(ROOT_UUID);
		resource.setName("AI Projects");
		resource.setFullPath("ai-projects://");
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_ROOT);
		return resource;
	}

	/**
	 * A row for one registered project.
	 *
	 * @param entry the catalogue entry
	 * @return a new project resource
	 */
	public static AiProjectResource forProject(ProjectEntry entry) {
		var resource = new AiProjectResource();
		resource.setUuid("ai-projects:project:" + entry.id());
		resource.setName(entry.name());
		resource.setFullPath(entry.root());
		resource.setFolder(false);
		resource.getMetadata().put(KIND, KIND_PROJECT);
		resource.getMetadata().put(PROJECT_ID, entry.id());
		resource.getMetadata().put(PROJECT_ROOT, entry.root());
		resource.getMetadata().put(PROJECT_STORAGE, entry.storageMode().name());
		return resource;
	}

	/**
	 * The row shown instead of an empty table, so a first-time user is told what
	 * this panel is for and how to fill it rather than left staring at nothing.
	 *
	 * <p>Activating it creates a project, which is what someone looking at an
	 * empty list is almost certainly trying to do.
	 *
	 * @return a new hint resource
	 */
	public static AiProjectResource hint() {
		var resource = new AiProjectResource();
		resource.setUuid(HINT_UUID);
		resource.setName("No AI projects yet - press Enter to create one");
		resource.setFullPath("");
		resource.setFolder(false);
		resource.getMetadata().put(KIND, KIND_HINT);
		return resource;
	}

	/**
	 * Whether a resource belongs to this plugin.
	 *
	 * @param resource any resource, possibly {@code null}
	 * @return whether it is one of ours
	 */
	public static boolean isOurs(NuclrResource resource) {
		return resource instanceof AiProjectResource;
	}

	/**
	 * Whether a resource is a project row.
	 *
	 * @param resource any resource, possibly {@code null}
	 * @return whether it names a project
	 */
	public static boolean isProject(NuclrResource resource) {
		return resource instanceof AiProjectResource project
				&& KIND_PROJECT.equals(project.getMetadata().get(KIND));
	}

	/**
	 * Whether a resource is the list root.
	 *
	 * @param resource any resource, possibly {@code null}
	 * @return whether it is the root
	 */
	public static boolean isRoot(NuclrResource resource) {
		return resource instanceof AiProjectResource root
				&& KIND_ROOT.equals(root.getMetadata().get(KIND));
	}

	/**
	 * Whether a resource is the "no projects yet" row.
	 *
	 * @param resource any resource, possibly {@code null}
	 * @return whether it is the hint
	 */
	public static boolean isHint(NuclrResource resource) {
		return resource instanceof AiProjectResource hint
				&& KIND_HINT.equals(hint.getMetadata().get(KIND));
	}

	/**
	 * The project id of a project row.
	 *
	 * @param resource any resource, possibly {@code null}
	 * @return the id, or {@code null} when the resource is not a project row
	 */
	public static String projectId(NuclrResource resource) {
		if (!isProject(resource)) {
			return null;
		}
		var id = resource.getMetadata().get(PROJECT_ID);
		return id == null ? null : String.valueOf(id);
	}

	/** This row's project root, or {@code null} when it is the list root. */
	public Path projectRoot() {
		var root = getMetadata().get(PROJECT_ROOT);
		if (root == null) {
			return null;
		}
		try {
			return Path.of(String.valueOf(root));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** This row's storage mode, defaulting to project-local. */
	public ProjectStorageMode storageMode() {
		var mode = getMetadata().get(PROJECT_STORAGE);
		return ProjectStorageMode.parse(mode == null ? null : String.valueOf(mode));
	}
}
