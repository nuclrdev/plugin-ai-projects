package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ToastPreferences;

/**
 * Whether the user wants desktop notifications, and whether that choice is kept.
 */
class ToastPreferencesTest {

	@Test
	void bothNotificationsAreOnUntilTurnedOff() {

		var preferences = new ToastPreferences(new FakePluginContext().getSettings());

		assertTrue(preferences.notifyOnInputNeeded());
		assertTrue(preferences.notifyOnCompleted());
	}

	@Test
	void eachNotificationIsSwitchedOnItsOwn() {

		var settings = new FakePluginContext().getSettings();
		var preferences = new ToastPreferences(settings);

		preferences.setNotifyOnCompleted(false);

		assertTrue(preferences.notifyOnInputNeeded(), "the blocking one stays on");
		assertFalse(preferences.notifyOnCompleted());
		// A second desktop on the same settings must see the same answer, which is the
		// point of storing it in the host rather than in the project.
		assertFalse(new ToastPreferences(settings).notifyOnCompleted());
	}

	@Test
	void aChoiceWrittenAsTextIsStillUnderstood() {

		// Settings survive a round trip through JSON, and a false written by an older
		// version may come back as the string "false".
		var settings = new FakePluginContext().getSettings();
		settings.set(ProjectCatalog.NAMESPACE, "notify.needsInput", "false");

		assertFalse(new ToastPreferences(settings).notifyOnInputNeeded());
	}

	@Test
	void withoutSettingsTheChoiceLastsForTheSession() {

		var preferences = new ToastPreferences(null);
		preferences.setNotifyOnInputNeeded(false);

		assertFalse(preferences.notifyOnInputNeeded());
		assertTrue(preferences.notifyOnCompleted());
	}
}
