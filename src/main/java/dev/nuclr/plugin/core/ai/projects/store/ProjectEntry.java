package dev.nuclr.plugin.core.ai.projects.store;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;

/**
 * One row of the catalogue: enough to find a project's definition without
 * reading it.
 *
 * <p>Deliberately small. The catalogue is a list of places to look, not a
 * cached copy of every project - a project whose {@code project.json} someone
 * edited by hand must show the edit the next time the panel lists it.
 *
 * @param id          stable project id
 * @param name        display name, as of the last time the catalogue was touched
 * @param root        absolute project root
 * @param storageMode where the metadata lives
 */
public record ProjectEntry(String id, String name, String root, ProjectStorageMode storageMode) {

	/** The root as a path. */
	public Path rootPath() {
		return Path.of(root);
	}

	/**
	 * The entry as a settings-storable map.
	 *
	 * @return a JSON-round-trippable map
	 */
	public Map<String, Object> toMap() {
		var map = new LinkedHashMap<String, Object>();
		map.put("id", id);
		map.put("name", name);
		map.put("root", root);
		map.put("storageMode", storageMode.name());
		return map;
	}

	/**
	 * Rebuild an entry from what the settings store handed back.
	 *
	 * <p>Values arrive Jackson-normalised and may have been written by an older
	 * version of the plugin, so anything unrecognised yields {@code null} rather
	 * than an exception: one bad row must not empty the panel.
	 *
	 * @param value a value read from settings
	 * @return the entry, or {@code null} when the value is not a usable row
	 */
	public static ProjectEntry fromValue(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		var id = text(map.get("id"));
		var root = text(map.get("root"));
		if (id == null || root == null || !isUsablePath(root)) {
			return null;
		}
		var name = text(map.get("name"));
		return new ProjectEntry(id, name == null ? id : name, root,
				ProjectStorageMode.parse(text(map.get("storageMode"))));
	}

	/**
	 * Whether a stored root can be turned into a {@link Path} on this platform.
	 *
	 * <p>Checked here, where untrusted text becomes an entry, because every caller
	 * reaches the root through {@link #rootPath()} - and a row carrying, say, a
	 * Windows path on Linux would otherwise throw out of the middle of the panel's
	 * listing and empty it, which is exactly what dropping bad rows is meant to
	 * prevent.
	 */
	private static boolean isUsablePath(String root) {
		try {
			Path.of(root);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		var string = String.valueOf(value).trim();
		return string.isEmpty() ? null : string;
	}
}
