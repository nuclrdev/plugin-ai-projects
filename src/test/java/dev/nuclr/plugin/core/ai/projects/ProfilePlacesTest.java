package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef.Place;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;

/** Profiles kept in a project or in the user's library, and how an agent names one. */
class ProfilePlacesTest {

	@TempDir
	Path root;

	private static Profile named(String name) {
		var profile = new Profile();
		profile.setName(name);
		return profile;
	}

	@Test
	void aReferenceSaysWhereTheProfileIsAndABareIdIsTheLibrarys() {
		assertEquals(Optional.of(new ProfileRef(Place.PROJECT, "abc")), ProfileRef.parse("project:abc"));
		assertEquals(Optional.of(new ProfileRef(Place.LIBRARY, "abc")), ProfileRef.parse("library:abc"));
		assertEquals(Optional.of(new ProfileRef(Place.LIBRARY, "abc")), ProfileRef.parse(" abc "),
				"as agents named profiles before projects could hold them");
		assertEquals(Optional.empty(), ProfileRef.parse(null));
		assertEquals(Optional.empty(), ProfileRef.parse("project:"));
		assertEquals("project:abc", new ProfileRef(Place.PROJECT, "abc").toString());
	}

	@Test
	void theProjectsProfilesAreListedFirstAndEachIsReadFromItsOwnPlace() throws IOException {
		var project = new ProfileStore(root.resolve("project"));
		var library = new ProfileStore(root.resolve("library"));
		var mine = library.create(named("Mine"));
		var shared = project.create(named("Shared"));
		var places = new ProfilePlaces(project, library);

		assertEquals(List.of("Shared", "Mine"), places.list().stream().map(each -> each.profile().getName()).toList());
		assertEquals("Shared", places.require(new ProfileRef(Place.PROJECT, shared.getId())).getName());
		assertEquals("Mine", places.require(new ProfileRef(Place.LIBRARY, mine.getId())).getName());
		assertThrows(NoSuchFileException.class, () -> places.require(new ProfileRef(Place.PROJECT, mine.getId())),
				"the same id in the other place is not the same profile");
	}

	@Test
	void withNoProjectOpenOnlyTheLibraryIsThere() throws IOException {
		var library = new ProfileStore(root.resolve("library"));
		library.create(named("Mine"));
		var places = new ProfilePlaces(null, library);

		assertEquals(1, places.list().size());
		assertTrue(places.list().stream().allMatch(each -> each.ref().place() == Place.LIBRARY));
		assertThrows(NoSuchFileException.class, () -> places.require(new ProfileRef(Place.PROJECT, "x")));
	}
}
