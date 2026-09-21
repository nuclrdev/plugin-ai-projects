package dev.nuclr.plugin.core.ai.projects.ui.screen;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;

/**
 * How the animated desktop background behaves, beyond which effect is chosen.
 *
 * <p>The effect itself is part of each project's desktop; whether it keeps moving
 * behind other applications is about the user's machine, not the project, so it is
 * kept in the host's settings under the plugin's own namespace. Built without
 * settings - as the tests build it - the value lives in memory and defaults to on.
 */
public final class BackgroundPreferences {

	/** Settings key for animating the background while the workspace is unfocused. */
	static final String KEY_ANIMATE_WHEN_INACTIVE = "background.animateWhenInactive";

	private final NuclrSettings settings;

	private boolean animateWhenInactiveFallback = true;

	/**
	 * Read and write the preferences in the host's settings.
	 *
	 * @param settings the host settings store, or {@code null} to keep them in memory
	 */
	public BackgroundPreferences(NuclrSettings settings) {
		this.settings = settings;
	}

	/** Whether the background keeps animating while another application has focus. */
	public boolean animateWhenInactive() {
		if (settings == null) {
			return animateWhenInactiveFallback;
		}
		// A boolean may come back from the JSON round trip as the string it was written as.
		Object stored = settings.get(ProjectCatalog.NAMESPACE, KEY_ANIMATE_WHEN_INACTIVE);
		if (stored instanceof Boolean flag) {
			return flag;
		}
		if (stored instanceof String text && !text.isBlank()) {
			return Boolean.parseBoolean(text.strip());
		}
		return animateWhenInactiveFallback;
	}

	/**
	 * Keep the background animating while unfocused, or hold it still.
	 *
	 * @param animate whether to keep animating
	 */
	public void setAnimateWhenInactive(boolean animate) {
		animateWhenInactiveFallback = animate;
		if (settings != null) {
			settings.set(ProjectCatalog.NAMESPACE, KEY_ANIMATE_WHEN_INACTIVE, animate);
		}
	}
}
