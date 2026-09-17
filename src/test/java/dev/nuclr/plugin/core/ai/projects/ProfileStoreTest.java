package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;

/** Profiles on disk: creating, listing, saving safely, copying, sharing. */
class ProfileStoreTest {

	@TempDir
	Path home;

	private ProfileStore store;

	@BeforeEach
	void setUp() {
		store = ProfileStore.inCommanderHome(home);
	}

	private static Profile profile(String name) {
		var profile = new Profile();
		profile.setName(name);
		profile.getHarness().setModel("model-a");
		profile.getHarness().getAllowedCommands().add("git status");
		profile.getHarness().getAllowedTools().add("Bash");
		profile.getContext().getInstructions().add(ProfileRecord.git("Conventions",
				"https://github.com/org/conventions.git", "main", "docs/JAVA.md"));
		return profile;
	}

	@Test
	void anEmptyFolderListsNothingAndIsNotCreatedJustByLooking() {
		var listing = store.list();
		assertTrue(listing.profiles().isEmpty());
		assertTrue(listing.unreadable().isEmpty());
		assertFalse(Files.exists(store.directory()));
	}

	@Test
	void aCreatedProfileIsListedWithEverythingItHolds() throws IOException {

		var created = store.create(profile("Company default"));

		assertTrue(Files.isRegularFile(store.directory().resolve(created.getId() + ".json")));
		var listed = store.list().profiles();
		assertEquals(1, listed.size());
		var read = listed.getFirst();
		assertEquals("Company default", read.getName());
		assertEquals("model-a", read.getHarness().getModel());
		assertEquals(List.of("git status"), read.getHarness().getAllowedCommands());
		assertEquals(List.of("Bash"), read.getHarness().getAllowedTools());
		var instruction = read.getContext().getInstructions().getFirst();
		assertEquals("https://github.com/org/conventions.git", instruction.getRepository());
		assertEquals("docs/JAVA.md", instruction.getPath());
		assertEquals(created.getCreatedAt(), read.getCreatedAt());
	}

	@Test
	void profilesAreListedByNameIgnoringCase() throws IOException {
		store.create(profile("beta"));
		store.create(profile("Alpha"));
		store.create(profile("gamma"));
		assertEquals(List.of("Alpha", "beta", "gamma"),
				store.list().profiles().stream().map(Profile::getName).toList());
	}

	@Test
	void savingWhatWasReadSucceedsAndMovesTheTimestampOn() throws Exception {
		var created = store.create(profile("P"));
		var edited = store.find(created.getId()).orElseThrow();
		edited.setDescription("changed");

		var saved = store.save(edited, false);

		assertTrue(saved.getUpdatedAt().isAfter(created.getUpdatedAt()));
		assertEquals("changed", store.find(created.getId()).orElseThrow().getDescription());
	}

	@Test
	void aSaveFromElsewhereIsNotSilentlyOverwritten() throws Exception {
		var created = store.create(profile("P"));
		var mine = store.find(created.getId()).orElseThrow();
		var theirs = store.find(created.getId()).orElseThrow();
		theirs.setDescription("theirs");
		store.save(theirs, false);

		mine.setDescription("mine");
		var conflict = assertThrows(ProfileStore.ConflictException.class, () -> store.save(mine, false));
		assertFalse(conflict.deleted());
		assertEquals("theirs", store.find(created.getId()).orElseThrow().getDescription());

		store.save(mine, true);
		assertEquals("mine", store.find(created.getId()).orElseThrow().getDescription());
	}

	@Test
	void savingAProfileDeletedElsewhereSaysSo() throws Exception {
		var created = store.create(profile("P"));
		store.delete(created.getId());

		var conflict = assertThrows(ProfileStore.ConflictException.class, () -> store.save(created, false));
		assertTrue(conflict.deleted());
		store.save(created, true);
		assertTrue(store.find(created.getId()).isPresent());
	}

	@Test
	void deletingTwiceIsNotAnError() throws IOException {
		var created = store.create(profile("P"));
		store.delete(created.getId());
		store.delete(created.getId());
		assertTrue(store.list().profiles().isEmpty());
	}

	@Test
	void duplicatesGetANewIdAndAFreeName() throws IOException {
		var original = store.create(profile("Base"));

		var first = store.duplicate(original);
		var second = store.duplicate(original);

		assertNotEquals(original.getId(), first.getId());
		assertEquals("Base (copy)", first.getName());
		assertEquals("Base (copy) (2)", second.getName());
		assertEquals("model-a", second.getHarness().getModel());
	}

	@Test
	void exportThenImportMakesANewProfileAndNeverReplacesOne() throws IOException {
		var original = store.create(profile("Shared"));
		var exported = home.resolve("out").resolve(ProfileStore.exportFileName(original));
		store.exportTo(original, exported);

		var imported = store.importFrom(exported);

		assertNotEquals(original.getId(), imported.getId());
		assertEquals("Shared (2)", imported.getName());
		assertEquals(2, store.list().profiles().size());
		assertEquals("docs/JAVA.md", imported.getContext().getInstructions().getFirst().getPath());
	}

	@Test
	void aDamagedFileIsReportedWithoutHidingTheRest() throws IOException {
		store.create(profile("Good"));
		Files.writeString(store.directory().resolve("broken.json"), "{ not json");

		var listing = store.list();

		assertEquals(1, listing.profiles().size());
		assertEquals(1, listing.unreadable().size());
		assertEquals("broken.json", listing.unreadable().getFirst().file().getFileName().toString());
	}

	@Test
	void theFileNameIsTheIdentityNotAnIdEditedInside() throws IOException {
		var created = store.create(profile("P"));
		var file = store.directory().resolve(created.getId() + ".json");
		Files.writeString(file, Files.readString(file).replace(created.getId(), "someone-else"));

		assertEquals(created.getId(), store.list().profiles().getFirst().getId());
	}

	@Test
	void anIdThatWouldEscapeTheFolderIsRefused() {
		assertTrue(store.find("../../etc/passwd").isEmpty());
		var profile = profile("P");
		profile.setId("../outside");
		assertThrows(IOException.class, () -> store.save(profile, true));
	}

	/** Write a profile file as a plugin of another schema would have. */
	private Path writeRaw(String id, String json) throws IOException {
		Files.createDirectories(store.directory());
		var file = store.directory().resolve(id + ".json");
		Files.writeString(file, json);
		return file;
	}

	@Test
	void aVersionOneProfileIsReadAndSavedAsTheCurrentVersion() throws Exception {
		var id = "0b8c1f3e-0000-4000-8000-000000000001";
		writeRaw(id, """
				{"schemaVersion": 1, "id": "%s", "name": "Old", "updatedAt": "2026-01-01T00:00:00Z",
				 "harness": {"allowedRoots": [{"kind": "FILE", "path": "/srv/shared"}],
				             "software": [{"kind": "TEXT", "text": "git"}]}}""".formatted(id));

		var read = store.require(id);
		assertEquals(1, read.getSchemaVersion());
		assertEquals("/srv/shared", read.getHarness().getExtraFolders().getFirst().getPath());

		var saved = store.save(read, false);
		assertEquals(Profile.SCHEMA_VERSION, saved.getSchemaVersion());
		var onDisk = Files.readString(store.directory().resolve(id + ".json"));
		assertTrue(onDisk.contains("\"schemaVersion\" : " + Profile.SCHEMA_VERSION)
				|| onDisk.contains("\"schemaVersion\":" + Profile.SCHEMA_VERSION), onDisk);
		assertTrue(onDisk.contains("extraFolders") && !onDisk.contains("allowedRoots"), onDisk);
	}

	@Test
	void aProfileFromANewerPluginIsListedAsUnreadableAndNeverOverwritten() throws Exception {
		var id = "0b8c1f3e-0000-4000-8000-000000000002";
		var future = """
				{"schemaVersion": %d, "id": "%s", "name": "Future", "harness": {"somethingNew": true}}"""
				.formatted(Profile.SCHEMA_VERSION + 1, id);
		var file = writeRaw(id, future);
		store.create(profile("Current"));

		var listing = store.list();
		assertEquals(List.of("Current"), listing.profiles().stream().map(Profile::getName).toList());
		assertEquals(1, listing.unreadable().size());
		assertTrue(listing.unreadable().getFirst().error().contains("newer version"),
				listing.unreadable().getFirst().error());

		assertTrue(store.find(id).isEmpty());
		var refused = assertThrows(IOException.class, () -> store.require(id));
		assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());

		// Even a forced save, from an editor opened before the newer plugin wrote the file.
		var stale = profile("Future");
		stale.setId(id);
		assertThrows(IOException.class, () -> store.save(stale, true));
		assertEquals(future, Files.readString(file), "left as the newer plugin wrote it");
	}

	@Test
	void importingAProfileFromANewerPluginIsRefused() throws IOException {
		var file = home.resolve("future" + ProfileStore.EXPORT_SUFFIX);
		Files.writeString(file, """
				{"schemaVersion": %d, "name": "Future"}""".formatted(Profile.SCHEMA_VERSION + 1));

		var refused = assertThrows(IOException.class, () -> store.importFrom(file));
		assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());
		assertTrue(store.list().profiles().isEmpty());
	}

	@Test
	void exportsAndCopiesAreWrittenInTheCurrentVersion() throws IOException {
		var old = profile("Old");
		old.setSchemaVersion(1);
		assertEquals(Profile.SCHEMA_VERSION, store.create(old).getSchemaVersion());

		var file = home.resolve("old" + ProfileStore.EXPORT_SUFFIX);
		store.exportTo(old, file);
		assertEquals(Profile.SCHEMA_VERSION, store.importFrom(file).getSchemaVersion());
		assertEquals(1, old.getSchemaVersion(), "the profile passed in is not changed");
	}

	@Test
	void uniqueNamesIgnoreCase() {
		assertEquals("Base (2)", ProfileStore.uniqueName("Base", List.of("base")));
		assertEquals("Base (3)", ProfileStore.uniqueName("Base", List.of("BASE", "base (2)")));
		assertEquals("Other", ProfileStore.uniqueName("Other", List.of("base")));
	}

	@Test
	void anExportFileNameIsSafeOnEveryFilesystem() {
		var profile = profile("a/b:c*?");
		assertEquals("a_b_c__.profile.json", ProfileStore.exportFileName(profile));
		assertEquals("profile.profile.json", ProfileStore.exportFileName(profile("..")));
	}
}
