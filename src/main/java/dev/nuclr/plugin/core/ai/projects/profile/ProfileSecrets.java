package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import lombok.extern.slf4j.Slf4j;

/**
 * The secrets a profile refers to, kept in the OS credential store the host
 * provides.
 *
 * <p>A profile file holds only a reference - a random key - never a value, so
 * profiles can be shared and exported safely. Random keys also mean renaming a
 * server does not orphan its secret.
 *
 * <p>Every call here may block, or make the OS ask to unlock the store, so none
 * of them may run on the event thread. None caches a value, and none logs one.
 */
@Slf4j
public final class ProfileSecrets {

	/** Prefix of every key this plugin writes for profiles. */
	public static final String KEY_PREFIX = "profile-secret/";

	private final NuclrCredentialStore store;

	/**
	 * Secrets over a credential store.
	 *
	 * @param store the host's store, or {@code null} when there is none
	 */
	public ProfileSecrets(NuclrCredentialStore store) {
		this.store = store;
	}

	/** Whether there is a store at all; a store can still refuse later, as {@code UNAVAILABLE}. */
	public boolean available() {
		return store != null;
	}

	/** A fresh key for a new secret. */
	public static String newKey() {
		return KEY_PREFIX + UUID.randomUUID();
	}

	/**
	 * Store a secret.
	 *
	 * @param key   its key
	 * @param value the secret
	 * @throws NuclrCredentialException when the store refuses
	 */
	public void write(String key, String value) throws NuclrCredentialException {
		require().set(key, value);
	}

	/**
	 * Read a secret.
	 *
	 * @param key its key
	 * @return the value, or empty when nothing is stored under it
	 * @throws NuclrCredentialException when the store refuses
	 */
	public Optional<String> read(String key) throws NuclrCredentialException {
		return require().get(key);
	}

	/**
	 * Delete a secret, reporting nothing: used for clean-up, where a failure must not
	 * undo what the user just did.
	 *
	 * @param key its key
	 */
	public void deleteQuietly(String key) {
		if (store == null || key == null || key.isBlank()) {
			return;
		}
		try {
			store.delete(key);
		} catch (NuclrCredentialException | RuntimeException e) {
			log.warn("Could not delete a stored profile secret: {}", e.getMessage());
		}
	}

	/**
	 * Delete every secret a profile refers to, as it is deleted.
	 *
	 * @param profile the profile
	 */
	public void deleteAll(Profile profile) {
		keys(profile).forEach(this::deleteQuietly);
	}

	/**
	 * Give a copy of a profile secrets of its own: each stored secret is read and
	 * stored again under a new key, and the copy is changed to refer to that. A
	 * secret that is missing from the store becomes one to enter.
	 *
	 * @param copy the copy, changed in place
	 * @return the keys written, for undoing if the copy is not saved
	 * @throws NuclrCredentialException when the store refuses
	 */
	public List<String> copyInto(Profile copy) throws NuclrCredentialException {
		var written = new ArrayList<String>();
		try {
			for (var secret : storedSecrets(copy)) {
				var value = read(secret.getKey());
				if (value.isEmpty()) {
					secret.setKey(null);
					continue;
				}
				var key = newKey();
				write(key, value.get());
				written.add(key);
				secret.setKey(key);
			}
		} catch (NuclrCredentialException | RuntimeException e) {
			written.forEach(this::deleteQuietly);
			throw e;
		}
		return written;
	}

	/**
	 * Every stored secret a profile refers to that has a key.
	 *
	 * @param profile the profile
	 * @return the references, live
	 */
	public static List<McpSecret> storedSecrets(Profile profile) {
		var secrets = new ArrayList<McpSecret>();
		var servers = profile.getHarness() == null ? null : profile.getHarness().getMcpServers();
		if (servers == null) {
			return secrets;
		}
		for (var server : servers) {
			if (server == null) {
				continue;
			}
			for (var secret : server.secrets()) {
				if (!secret.fromEnvironment() && secret.getKey() != null && !secret.getKey().isBlank()) {
					secrets.add(secret);
				}
			}
		}
		return secrets;
	}

	/**
	 * The keys a profile refers to.
	 *
	 * @param profile the profile
	 * @return the keys
	 */
	public static Set<String> keys(Profile profile) {
		var keys = new LinkedHashSet<String>();
		storedSecrets(profile).forEach(secret -> keys.add(secret.getKey()));
		return keys;
	}

	/**
	 * Drop every stored secret's key, leaving secrets to be entered: what a profile
	 * looks like on its way out of, or into, this machine.
	 *
	 * @param profile the profile, changed in place
	 */
	public static void forget(Profile profile) {
		storedSecrets(profile).forEach(secret -> secret.setKey(null));
	}

	private NuclrCredentialStore require() throws NuclrCredentialException {
		if (store == null) {
			throw new NuclrCredentialException(NuclrCredentialException.Reason.UNAVAILABLE,
					"No credential store is available.");
		}
		return store;
	}
}
