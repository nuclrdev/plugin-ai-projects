package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Whether a path a project definition names is somewhere that project is allowed
 * to reach.
 *
 * <p>The question is asked in two places — where an agent runs, and what it is
 * pointed at — and they have to give the same answer. A working directory that
 * launches somewhere the resolved-context view refuses to show, or the reverse,
 * is a bug the user cannot see and has already happened here once, so both go
 * through this.
 *
 * <p>Both sides of every comparison are resolved through {@link Path#toRealPath}
 * where they exist. That stops a symlink being used to step outside, and stops a
 * project that itself lives under a symlinked directory from being wrongly
 * refused — which is the normal case on macOS, where the temporary directory is
 * reached through one.
 */
public final class PathContainment {

	private PathContainment() {
	}

	/**
	 * The real path where one exists, and the normalised absolute path where it
	 * does not.
	 *
	 * <p>A path to nothing still has to be comparable: a reference to a file that
	 * has not been written yet is a missing reference, not an escape.
	 *
	 * @param path the path to canonicalise
	 * @return the canonical form, never {@code null}
	 */
	public static Path real(Path path) {
		var normalized = path.toAbsolutePath().normalize();
		try {
			return canonicalForContainment(normalized);
		} catch (IOException e) {
			// Unreadable is not the same as outside, but it is not somewhere to hand an
			// agent either. Callers treat the normalised form as the answer.
			return normalized;
		}
	}

	/**
	 * Whether {@code candidate} lies inside any of the permitted directories.
	 *
	 * @param candidate    the path being checked
	 * @param owned        directories the project always owns, such as its root
	 *                     and metadata directory
	 * @param allowedRoots directories the harness additionally permits, as written
	 *                     in the definition; {@code null}, blank and malformed
	 *                     entries widen nothing
	 * @return whether the candidate is contained
	 */
	public static boolean contains(Path candidate, List<Path> owned, List<String> allowedRoots) {

		if (candidate == null) {
			return false;
		}
		final Path resolved;
		try {
			resolved = canonicalForContainment(candidate);
		} catch (IOException | RuntimeException e) {
			return false;
		}

		if (owned != null) {
			for (var directory : owned) {
				if (directory != null) {
					try {
						if (resolved.startsWith(canonicalForContainment(directory))) {
							return true;
						}
					} catch (IOException | RuntimeException e) {
						// An unreadable root grants no access; try the remaining roots.
					}
				}
			}
		}
		if (allowedRoots == null) {
			return false;
		}
		for (var configured : allowedRoots) {
			if (configured == null || configured.isBlank()) {
				continue;
			}
			try {
				if (resolved.startsWith(canonicalForContainment(Path.of(configured.trim())))) {
					return true;
				}
			} catch (IOException | RuntimeException e) {
				// A malformed allowed root widens nothing; try the next one.
			}
		}
		return false;
	}

	/**
	 * Resolve every existing part of a path, not just a target that already exists.
	 * This matters for a missing child below a symlink: resolving only the complete
	 * target would compare its lexical spelling and could mistake an external path
	 * for one owned by the project.
	 */
	private static Path canonicalForContainment(Path path) throws IOException {
		var normalized = path.toAbsolutePath().normalize();
		var existing = normalized;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return normalized;
		}
		var canonicalParent = existing.toRealPath();
		return canonicalParent.resolve(existing.relativize(normalized)).normalize();
	}
}
