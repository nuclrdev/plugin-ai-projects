package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.Locale;
import java.util.Optional;

/**
 * Which profile an agent starts from, and where it is kept: in the project, so it
 * travels with the project to anyone who opens it, or in the user's own library on
 * this machine.
 *
 * <p>Written into {@code project.json} as {@code project:<id>} or {@code library:<id>}.
 * A bare id, as agents referred to profiles before projects could hold them, is a
 * library profile.
 *
 * @param place where the profile is kept
 * @param id    its id there
 */
public record ProfileRef(Place place, String id) {

	/** Where a profile is kept. */
	public enum Place {
		/** In the project's metadata folder: shared with everyone who opens the project. */
		PROJECT("project", "This project"),
		/** In the user's library: on this machine only, usable in every project. */
		LIBRARY("library", "My library");

		private final String prefix;
		private final String label;

		Place(String prefix, String label) {
			this.prefix = prefix;
			this.label = label;
		}

		/** Name shown to people. */
		public String label() {
			return label;
		}
	}

	/**
	 * A reference as {@code project.json} holds it.
	 *
	 * @param written the stored value, possibly {@code null}
	 * @return the reference, or empty for none
	 */
	public static Optional<ProfileRef> parse(String written) {
		if (written == null || written.isBlank()) {
			return Optional.empty();
		}
		var value = written.strip();
		var colon = value.indexOf(':');
		if (colon > 0) {
			var prefix = value.substring(0, colon).toLowerCase(Locale.ROOT);
			for (var place : Place.values()) {
				if (place.prefix.equals(prefix)) {
					var id = value.substring(colon + 1).strip();
					return id.isEmpty() ? Optional.empty() : Optional.of(new ProfileRef(place, id));
				}
			}
		}
		return Optional.of(new ProfileRef(Place.LIBRARY, value));
	}

	/** The value {@code project.json} holds. */
	@Override
	public String toString() {
		return place.prefix + ":" + id;
	}
}
