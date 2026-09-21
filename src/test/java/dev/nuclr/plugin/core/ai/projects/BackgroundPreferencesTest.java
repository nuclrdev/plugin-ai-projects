package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.ui.screen.BackgroundPreferences;

/**
 * Whether the background keeps animating behind other applications, and whether that choice is kept.
 */
class BackgroundPreferencesTest {

	@Test
	void animatesWhenInactiveUntilTurnedOff() {
		assertTrue(new BackgroundPreferences(new FakePluginContext().getSettings()).animateWhenInactive());
	}

	@Test
	void theChoiceIsSharedThroughTheHostSettings() {

		var settings = new FakePluginContext().getSettings();
		new BackgroundPreferences(settings).setAnimateWhenInactive(false);

		assertFalse(new BackgroundPreferences(settings).animateWhenInactive());
	}

	@Test
	void aChoiceWrittenAsTextIsStillUnderstood() {

		var settings = new FakePluginContext().getSettings();
		settings.set(ProjectCatalog.NAMESPACE, "background.animateWhenInactive", "false");

		assertFalse(new BackgroundPreferences(settings).animateWhenInactive());
	}

	@Test
	void withoutSettingsTheChoiceLastsForTheSession() {

		var preferences = new BackgroundPreferences(null);
		preferences.setAnimateWhenInactive(false);

		assertFalse(preferences.animateWhenInactive());
	}
}
