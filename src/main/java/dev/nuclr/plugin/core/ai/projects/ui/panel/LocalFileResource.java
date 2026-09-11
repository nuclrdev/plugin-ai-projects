package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * A local file this plugin hands to Commander to view or edit - a project
 * definition, an instruction document, a skill.
 *
 * <p>The plugin does not open these itself. It describes the file and lets the
 * host pick the editor, so a skill opens in whatever the user's text editor
 * plugin is rather than in a second, worse one written here.
 */
public final class LocalFileResource extends NuclrResource {

	private LocalFileResource(Path path) {
		super(path);
	}

	/**
	 * Wrap a local file.
	 *
	 * @param file the file
	 * @return the resource, or {@code null} when the file is not there
	 */
	public static LocalFileResource of(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		var absolute = file.toAbsolutePath().normalize();
		var resource = new LocalFileResource(absolute);
		resource.setUuid("ai-projects:file:" + absolute);
		var name = absolute.getFileName();
		resource.setName(name == null ? absolute.toString() : name.toString());
		resource.setFullPath(absolute.toString());
		resource.setFolder(false);
		try {
			resource.setLength(Files.size(absolute));
			resource.setLastModifiedDateTime(LocalDateTime.ofInstant(
					Files.getLastModifiedTime(absolute).toInstant(), ZoneId.systemDefault()));
		} catch (IOException e) {
			// Size and timestamp are decoration; the file itself is what matters.
			resource.setLength(0);
		}
		return resource;
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws IOException {
		return Files.newInputStream(getPath(), options);
	}
}
