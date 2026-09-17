package dev.nuclr.plugin.core.ai.projects.profile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The folders agents may also work in, besides the project folder.
 *
 * <p>Profiles are shared between machines, so a folder under the user's home is
 * written as {@code ~/.m2} rather than {@code C:\Users\name\.m2}, and expanded
 * only when an agent is started. Either separator may follow the {@code ~}.
 */
public final class ExtraFolders {

	/** An absolute path on Windows or on Unix, whichever machine the profile is checked on. */
	private static final Pattern ABSOLUTE = Pattern.compile("^([A-Za-z]:[\\\\/]|[\\\\/]).*");

	private ExtraFolders() {
	}

	/**
	 * Replace a leading {@code ~} with the home folder.
	 *
	 * @param path the folder as written
	 * @param home the user's home folder
	 * @return the folder as the agent is given it
	 */
	public static String expandHome(String path, String home) {
		var trimmed = path.strip();
		if (trimmed.equals("~")) {
			return home;
		}
		if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
			var rest = trimmed.substring(2);
			// Written on one system, used on another: use this one's separator throughout.
			rest = File.separatorChar == '/' ? rest.replace('\\', '/') : rest.replace('/', '\\');
			return home.endsWith(File.separator) ? home + rest : home + File.separator + rest;
		}
		return trimmed;
	}

	/**
	 * Write a folder under the home folder as {@code ~/...}, so the profile works on
	 * other machines; any other folder is kept as it is.
	 *
	 * @param path the folder chosen
	 * @param home the user's home folder
	 * @return the folder as the profile should hold it
	 */
	public static String collapseHome(String path, String home) {
		var normalizedHome = home.endsWith(File.separator) ? home.substring(0, home.length() - 1) : home;
		var sameCase = File.separatorChar == '\\'
				? path.toLowerCase(java.util.Locale.ROOT).startsWith(normalizedHome.toLowerCase(java.util.Locale.ROOT))
				: path.startsWith(normalizedHome);
		if (!sameCase) {
			return path;
		}
		if (path.length() == normalizedHome.length()) {
			return "~";
		}
		var next = path.charAt(normalizedHome.length());
		if (next != '/' && next != '\\') {
			return path;
		}
		return "~/" + path.substring(normalizedHome.length() + 1).replace('\\', '/');
	}

	/**
	 * Whether a folder can be passed as written: absolute on some system, or under the home folder.
	 *
	 * @param path the folder as written
	 * @return whether it is not relative
	 */
	public static boolean isUsable(String path) {
		var trimmed = path.strip();
		return trimmed.equals("~") || trimmed.startsWith("~/") || trimmed.startsWith("~\\")
				|| ABSOLUTE.matcher(trimmed).matches();
	}

	/**
	 * The folders to pass for a harness: its switched-on folder links, expanded.
	 *
	 * @param harness the harness
	 * @param home    the user's home folder
	 * @return the folders, in order, without repeats
	 */
	public static List<String> resolve(Profile.Harness harness, String home) {
		var folders = new ArrayList<String>();
		if (harness.getExtraFolders() == null) {
			return folders;
		}
		for (var record : harness.getExtraFolders()) {
			if (record != null && record.isEnabled() && record.getKind() == RecordKind.FILE && record.getPath() != null
					&& !record.getPath().isBlank()) {
				var folder = expandHome(record.getPath(), home);
				if (!folders.contains(folder)) {
					folders.add(folder);
				}
			}
		}
		return folders;
	}
}
