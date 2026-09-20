package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Show a file to the user in whatever their system uses to look at folders.
 *
 * <p>Java's own {@code Desktop.browseFileDirectory} is the obvious answer and is refused
 * on Windows, which is the platform this is wanted on most, so each system is asked in
 * the way it understands: Explorer selects the file, the Finder reveals it, and a Linux
 * desktop is given the folder, since there is no portable way to ask it to select
 * something inside one.
 *
 * <p>The command is worked out by {@link #command(String, Path)}, which is pure and
 * therefore the part that is tested; running it is one line around that.
 */
@Slf4j
public final class Reveal {

	private Reveal() {
	}

	/**
	 * The command that shows a file on one platform.
	 *
	 * @param osName the value of {@code os.name}
	 * @param file   the file to show
	 * @return the command and its arguments
	 */
	public static List<String> command(String osName, Path file) {
		var os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		var absolute = file.toAbsolutePath().normalize();
		if (os.startsWith("windows")) {
			// One argument, and no space after the comma: Explorer parses it itself and
			// takes a separated path as a folder to open instead of a file to select.
			return List.of("explorer.exe", "/select," + absolute);
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return List.of("open", "-R", absolute.toString());
		}
		return List.of("xdg-open", parentOf(absolute).toString());
	}

	/**
	 * Show a file, falling back to opening its folder when the system has no file
	 * manager to ask.
	 *
	 * @param file the file to show
	 */
	public static void show(Path file) {
		var absolute = file.toAbsolutePath().normalize();
		try {
			new ProcessBuilder(command(System.getProperty("os.name", ""), absolute))
					.directory(parentOf(absolute).toFile())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
		} catch (IOException | RuntimeException e) {
			log.debug("Could not reveal {}: {}", absolute, e.getMessage());
			openFolder(absolute);
		}
	}

	/** The last resort: hand the folder to whatever Java can reach. */
	private static void openFolder(Path file) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				Desktop.getDesktop().open(parentOf(file).toFile());
			}
		} catch (IOException | RuntimeException e) {
			log.debug("Could not open the folder of {}: {}", file, e.getMessage());
		}
	}

	/** A file's folder, or the file itself when it has no parent - a root. */
	private static Path parentOf(Path file) {
		var parent = file.getParent();
		return parent == null ? file : parent;
	}
}
