package dev.nuclr.plugin.core.ai.projects.profile;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef.Place;

/**
 * The two places profiles are kept, seen together: the open project's, and the
 * user's library.
 *
 * @param project the project's profiles, or {@code null} when no project is open
 * @param library the user's library
 */
public record ProfilePlaces(ProfileStore project, ProfileStore library) {

	/**
	 * A profile with where it is kept.
	 *
	 * @param ref     its reference
	 * @param profile the profile
	 */
	public record Located(ProfileRef ref, Profile profile) {
	}

	/**
	 * The store for a place.
	 *
	 * @param place the place
	 * @return the store, or {@code null} for the project when none is open
	 */
	public ProfileStore store(Place place) {
		return place == Place.PROJECT ? project : library;
	}

	/**
	 * Read the profile a reference names.
	 *
	 * @param ref the reference
	 * @return the profile
	 * @throws NoSuchFileException when there is no such profile
	 * @throws IOException         when it cannot be read, or was saved by a newer plugin
	 */
	public Profile require(ProfileRef ref) throws IOException {
		var store = store(ref.place());
		if (store == null) {
			throw new NoSuchFileException(ref.toString());
		}
		return store.require(ref.id());
	}

	/**
	 * Every readable profile, the project's first.
	 *
	 * @return the profiles
	 */
	public List<Located> list() {
		var all = new ArrayList<Located>();
		for (var place : Place.values()) {
			var store = store(place);
			if (store != null) {
				for (var profile : store.list().profiles()) {
					all.add(new Located(new ProfileRef(place, profile.getId()), profile));
				}
			}
		}
		return all;
	}
}
