package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nuclr.platform.NuclrCredentialException;

/**
 * The secrets entered while one profile is being edited, held until it is saved.
 *
 * <p>Nothing reaches the credential store while editing: a secret typed and then
 * cancelled must not be left behind in the OS keychain. Nor is anything taken back
 * while editing - dialogs nest and can each be cancelled, so a staged secret is only
 * ever dropped by not being referred to when the profile is saved. On save, the secrets the
 * profile still refers to are written first - so a saved profile never refers to
 * a secret that is not there - then the profile, and only after that are the
 * secrets it no longer refers to deleted. If the profile cannot be saved, the
 * secrets just written are removed again.
 */
public final class SecretSession {

	private final ProfileSecrets secrets;
	private final Set<String> original;
	private final Map<String, String> staged = new LinkedHashMap<>();

	/**
	 * Start a session.
	 *
	 * @param secrets  the store
	 * @param original the profile as it was before editing
	 */
	public SecretSession(ProfileSecrets secrets, Profile original) {
		this.secrets = secrets;
		this.original = ProfileSecrets.keys(original);
	}

	/** Whether secrets can be stored at all. */
	public boolean available() {
		return secrets.available();
	}

	/**
	 * Hold a secret the user entered, until the profile is saved.
	 *
	 * @param value the secret
	 * @return the key the profile should refer to
	 */
	public String stage(String value) {
		var key = ProfileSecrets.newKey();
		staged.put(key, value);
		return key;
	}

	/**
	 * Whether a key was entered in this session and is not saved yet.
	 *
	 * @param key the key
	 * @return whether it is waiting to be written
	 */
	public boolean isStaged(String key) {
		return staged.containsKey(key);
	}

	/**
	 * Write the entered secrets the profile still refers to. Off the event thread.
	 *
	 * @param edited the profile about to be saved
	 * @return the keys written, for {@link #rollback} if saving fails
	 * @throws NuclrCredentialException when the store refuses; nothing is left written
	 */
	public List<String> writeStaged(Profile edited) throws NuclrCredentialException {
		var referenced = ProfileSecrets.keys(edited);
		var written = new ArrayList<String>();
		try {
			for (var entry : staged.entrySet()) {
				if (referenced.contains(entry.getKey())) {
					secrets.write(entry.getKey(), entry.getValue());
					written.add(entry.getKey());
				}
			}
		} catch (NuclrCredentialException | RuntimeException e) {
			rollback(written);
			throw e;
		}
		return written;
	}

	/**
	 * Remove secrets written for a save that did not happen. Off the event thread.
	 *
	 * @param written the keys {@link #writeStaged} returned
	 */
	public void rollback(List<String> written) {
		written.forEach(secrets::deleteQuietly);
	}

	/**
	 * The profile is saved: forget the entered values, and delete the secrets it had
	 * before editing but no longer refers to. Off the event thread.
	 *
	 * @param saved the profile as saved
	 */
	public void finish(Profile saved) {
		staged.clear();
		var referenced = ProfileSecrets.keys(saved);
		for (var key : original) {
			if (!referenced.contains(key)) {
				secrets.deleteQuietly(key);
			}
		}
	}
}
