package dev.nuclr.plugin.core.ai.projects.ui.screen;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;

/**
 * Whether desktop notifications are wanted, and which of them.
 *
 * <p>Two switches rather than one because the two notifications are not equally
 * welcome. An agent asking permission is blocking and is always worth a toast;
 * an agent finishing a turn is a courtesy, and someone running six agents that
 * each finish a dozen turns an hour may well want that one off while keeping the
 * first.
 *
 * <p>Kept in the host's settings under the plugin's own namespace, so the choice
 * is the user's across every project and survives a restart. A notifier built
 * without settings - as the tests build it - keeps the values in memory and
 * defaults both to on.
 */
public final class ToastPreferences {

	/** Settings key for the notification shown when an agent wants input. */
	static final String KEY_INPUT = "notify.needsInput";

	/** Settings key for the notification shown when an agent finishes a turn. */
	static final String KEY_COMPLETED = "notify.completed";

	private final NuclrSettings settings;

	private boolean inputFallback = true;
	private boolean completedFallback = true;

	/**
	 * Read and write the preferences in the host's settings.
	 *
	 * @param settings the host settings store, or {@code null} to keep them in memory
	 */
	public ToastPreferences(NuclrSettings settings) {
		this.settings = settings;
	}

	/** Whether to notify when an agent is waiting for the user. */
	public boolean notifyOnInputNeeded() {
		return read(KEY_INPUT, inputFallback);
	}

	/** Whether to notify when an agent finishes a turn. */
	public boolean notifyOnCompleted() {
		return read(KEY_COMPLETED, completedFallback);
	}

	/**
	 * Turn the input notification on or off.
	 *
	 * @param wanted whether it is wanted
	 */
	public void setNotifyOnInputNeeded(boolean wanted) {
		inputFallback = wanted;
		write(KEY_INPUT, wanted);
	}

	/**
	 * Turn the completion notification on or off.
	 *
	 * @param wanted whether it is wanted
	 */
	public void setNotifyOnCompleted(boolean wanted) {
		completedFallback = wanted;
		write(KEY_COMPLETED, wanted);
	}

	private boolean read(String key, boolean fallback) {
		if (settings == null) {
			return fallback;
		}
		// Settings survive a round trip through JSON, where a boolean may come back as
		// the string it was written as; both shapes have to be understood.
		Object stored = settings.get(ProjectCatalog.NAMESPACE, key);
		if (stored instanceof Boolean flag) {
			return flag;
		}
		if (stored instanceof String text && !text.isBlank()) {
			return Boolean.parseBoolean(text.strip());
		}
		return fallback;
	}

	private void write(String key, boolean value) {
		if (settings != null) {
			settings.set(ProjectCatalog.NAMESPACE, key, value);
		}
	}
}
