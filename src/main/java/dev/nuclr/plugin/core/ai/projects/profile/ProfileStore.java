package dev.nuclr.plugin.core.ai.projects.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.nuclr.plugin.core.ai.projects.store.Json;
import lombok.extern.slf4j.Slf4j;

/**
 * The profiles on this machine: one JSON file each, in one folder.
 *
 * <p>Profiles belong to the user, not to a project, so they live beside
 * Commander's own configuration. Every write is atomic, and a save checks that
 * nobody else - another workspace, another editor - saved the same profile since
 * it was read, so two edits never silently overwrite one another.
 */
@Slf4j
public final class ProfileStore {

	/** Folder under the Commander home that holds the profiles. */
	public static final String DIRECTORY_NAME = "ai-profiles";

	/** Extension of an exported profile. */
	public static final String EXPORT_SUFFIX = ".profile.json";

	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$");

	private final Path directory;

	/**
	 * A store over a folder, created on first write.
	 *
	 * @param directory the folder
	 */
	public ProfileStore(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory");
	}

	/**
	 * The store in a Commander home.
	 *
	 * @param commanderHome the Commander configuration directory
	 * @return the store
	 */
	public static ProfileStore inCommanderHome(Path commanderHome) {
		return new ProfileStore(commanderHome.resolve(DIRECTORY_NAME));
	}

	/** The folder the profiles are kept in. */
	public Path directory() {
		return directory;
	}

	/**
	 * A profile file that could not be read.
	 *
	 * @param file  the file
	 * @param error why
	 */
	public record Unreadable(Path file, String error) {
	}

	/**
	 * Everything in the folder.
	 *
	 * @param profiles   the profiles, by name
	 * @param unreadable files that are there but could not be used
	 */
	public record Listing(List<Profile> profiles, List<Unreadable> unreadable) {
	}

	/** Thrown when a profile changed on disk after it was read. */
	public static final class ConflictException extends Exception {

		private static final long serialVersionUID = 1L;

		private final boolean deleted;

		ConflictException(String message, boolean deleted) {
			super(message);
			this.deleted = deleted;
		}

		/** Whether the profile was deleted, rather than saved, elsewhere. */
		public boolean deleted() {
			return deleted;
		}
	}

	/**
	 * Read every profile. A damaged file is reported, not fatal: one bad file
	 * must not hide every other profile.
	 *
	 * @return the listing
	 */
	public Listing list() {
		var profiles = new ArrayList<Profile>();
		var unreadable = new ArrayList<Unreadable>();
		if (!Files.isDirectory(directory)) {
			return new Listing(profiles, unreadable);
		}
		try (var files = Files.list(directory)) {
			for (var file : files.filter(ProfileStore::isProfileFile).sorted().toList()) {
				try {
					var profile = Json.read(file, Profile.class);
					var expectedId = idOf(file);
					if (profile == null) {
						throw new IOException("the file is empty");
					}
					// The file name is the identity; an id edited by hand inside it is not trusted.
					profile.setId(expectedId);
					profiles.add(profile);
				} catch (IOException | RuntimeException e) {
					log.warn("Skipping unreadable profile {}: {}", file, e.getMessage());
					unreadable.add(new Unreadable(file, e.getMessage()));
				}
			}
		} catch (IOException e) {
			log.warn("Could not list profiles in {}: {}", directory, e.getMessage());
			unreadable.add(new Unreadable(directory, e.getMessage()));
		}
		profiles.sort(Comparator.comparing((Profile profile) -> profile.displayName().toLowerCase(Locale.ROOT))
				.thenComparing(profile -> profile.getId() == null ? "" : profile.getId()));
		return new Listing(profiles, unreadable);
	}

	/**
	 * Read one profile.
	 *
	 * @param id the id
	 * @return the profile, or empty when absent or unreadable
	 */
	public Optional<Profile> find(String id) {
		if (!isSafeId(id)) {
			return Optional.empty();
		}
		var file = file(id);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			var profile = Json.read(file, Profile.class);
			if (profile == null) {
				return Optional.empty();
			}
			profile.setId(id);
			return Optional.of(profile);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/**
	 * Save a new profile, giving it an id and timestamps.
	 *
	 * @param draft the profile; not modified
	 * @return the saved copy
	 * @throws IOException when it cannot be written
	 */
	public Profile create(Profile draft) throws IOException {
		var profile = draft.copy();
		profile.setId(UUID.randomUUID().toString());
		var now = now();
		profile.setCreatedAt(now);
		profile.setUpdatedAt(now);
		write(profile);
		return profile;
	}

	/**
	 * Save an existing profile.
	 *
	 * @param edited the profile as edited; not modified
	 * @param force  overwrite even when the profile changed or vanished since it was read
	 * @return the saved copy, with a new {@code updatedAt}
	 * @throws IOException       when it cannot be written
	 * @throws ConflictException when it changed on disk since it was read, and {@code force} is off
	 */
	public Profile save(Profile edited, boolean force) throws IOException, ConflictException {
		if (!isSafeId(edited.getId())) {
			throw new IOException("Not a valid profile id: " + edited.getId());
		}
		if (!force) {
			var onDisk = find(edited.getId());
			if (onDisk.isEmpty()) {
				throw new ConflictException("\"" + edited.displayName() + "\" was deleted after you opened it.", true);
			}
			if (!Objects.equals(onDisk.get().getUpdatedAt(), edited.getUpdatedAt())) {
				throw new ConflictException("\"" + edited.displayName() + "\" was changed elsewhere after you opened it.",
						false);
			}
		}
		var profile = edited.copy();
		if (profile.getCreatedAt() == null) {
			profile.setCreatedAt(now());
		}
		profile.setUpdatedAt(later(edited.getUpdatedAt()));
		write(profile);
		return profile;
	}

	/**
	 * Delete a profile. Deleting one that is already gone is not an error.
	 *
	 * @param id the id
	 * @throws IOException when the file exists and cannot be removed
	 */
	public void delete(String id) throws IOException {
		if (isSafeId(id)) {
			Files.deleteIfExists(file(id));
		}
	}

	/**
	 * Copy a profile under a new id and a free name.
	 *
	 * @param original the profile to copy
	 * @return the saved copy
	 * @throws IOException when it cannot be written
	 */
	public Profile duplicate(Profile original) throws IOException {
		var copy = original.copy();
		copy.setName(uniqueName(original.displayName() + " (copy)", names(null)));
		return create(copy);
	}

	/**
	 * Import a profile file. It always becomes a new profile, renamed if the name
	 * is taken, so importing never replaces anything.
	 *
	 * @param file the file to read
	 * @return the saved profile
	 * @throws IOException when it cannot be read or written
	 */
	public Profile importFrom(Path file) throws IOException {
		var imported = Json.read(file, Profile.class);
		if (imported == null) {
			throw new IOException(file.getFileName() + " is empty.");
		}
		var name = imported.getName() == null || imported.getName().isBlank()
				? stripSuffix(file.getFileName().toString())
				: imported.getName().trim();
		imported.setName(uniqueName(name, names(null)));
		return create(imported);
	}

	/**
	 * Write a profile to a file of the user's choosing.
	 *
	 * @param profile the profile
	 * @param file    the destination
	 * @throws IOException when it cannot be written
	 */
	public void exportTo(Profile profile, Path file) throws IOException {
		Json.write(file, profile);
	}

	/**
	 * The names of every profile but one.
	 *
	 * @param exceptId the id to leave out, or {@code null}
	 * @return the names
	 */
	public List<String> names(String exceptId) {
		return list().profiles().stream()
				.filter(profile -> exceptId == null || !exceptId.equals(profile.getId()))
				.map(Profile::displayName)
				.toList();
	}

	/**
	 * {@code base}, or {@code base (2)}, {@code base (3)}... whichever is free,
	 * ignoring case.
	 *
	 * @param base  the wanted name
	 * @param taken the names in use
	 * @return a free name
	 */
	public static String uniqueName(String base, Collection<String> taken) {
		var lower = taken.stream().filter(Objects::nonNull).map(name -> name.trim().toLowerCase(Locale.ROOT)).toList();
		if (!lower.contains(base.toLowerCase(Locale.ROOT))) {
			return base;
		}
		for (var suffix = 2;; suffix++) {
			var candidate = base + " (" + suffix + ")";
			if (!lower.contains(candidate.toLowerCase(Locale.ROOT))) {
				return candidate;
			}
		}
	}

	/**
	 * A file name for exporting a profile: its name, made safe for any filesystem.
	 *
	 * @param profile the profile
	 * @return the file name, ending in {@link #EXPORT_SUFFIX}
	 */
	public static String exportFileName(Profile profile) {
		var safe = profile.displayName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.')) {
			safe = "profile";
		}
		return safe + EXPORT_SUFFIX;
	}

	private void write(Profile profile) throws IOException {
		Json.write(file(profile.getId()), profile);
	}

	private Path file(String id) {
		return directory.resolve(id + ".json");
	}

	private static boolean isProfileFile(Path file) {
		var name = file.getFileName().toString();
		return Files.isRegularFile(file) && name.endsWith(".json") && isSafeId(idOf(file));
	}

	private static String idOf(Path file) {
		var name = file.getFileName().toString();
		return name.substring(0, name.length() - ".json".length());
	}

	private static boolean isSafeId(String id) {
		return id != null && SAFE_ID.matcher(id).matches();
	}

	private static String stripSuffix(String fileName) {
		if (fileName.endsWith(EXPORT_SUFFIX)) {
			return fileName.substring(0, fileName.length() - EXPORT_SUFFIX.length());
		}
		return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}

	/** Now, but strictly after {@code previous}, so two quick saves never share a stamp. */
	private static Instant later(Instant previous) {
		var now = now();
		return previous != null && !now.isAfter(previous) ? previous.plusMillis(1) : now;
	}
}
