package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * A plain local folder, handed to Commander when this panel asks to navigate
 * somewhere it does not itself browse - a project's root, say.
 *
 * <p>The panel does not resolve the folder itself: it emits the resource and
 * lets the host find the file-panel plugin that owns local paths. That keeps
 * this plugin out of the business of listing directories, which something else
 * already does properly.
 */
public final class LocalFolderResource extends NuclrResource {

	private LocalFolderResource(Path path) {
		super(path);
	}

	/**
	 * Wrap a local folder.
	 *
	 * @param folder the folder
	 * @return the resource, or {@code null} when {@code folder} is not a directory
	 */
	public static LocalFolderResource of(Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			return null;
		}
		var absolute = folder.toAbsolutePath().normalize();
		var resource = new LocalFolderResource(absolute);
		resource.setUuid("ai-projects:folder:" + absolute);
		var name = absolute.getFileName();
		resource.setName(name == null ? absolute.toString() : name.toString());
		resource.setFullPath(absolute.toString());
		resource.setFolder(true);
		return resource;
	}
}
