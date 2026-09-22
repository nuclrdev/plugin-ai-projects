package dev.nuclr.plugin.core.ai.projects.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemToastTest {

	@Test
	void macOsSkipsTheTray() {
		assertFalse(SystemToast.usesTray("Mac OS X"));
	}

	@Test
	void windowsAndLinuxUseTheTray() {
		assertTrue(SystemToast.usesTray("Windows 11"));
		assertTrue(SystemToast.usesTray("Linux"));
	}
}
