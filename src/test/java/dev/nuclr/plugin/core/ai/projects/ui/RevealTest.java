package dev.nuclr.plugin.core.ai.projects.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Showing a saved file to the user, on each system in the way that system understands. */
class RevealTest {

	private static final Path FILE = Path.of("C:", "pictures", "chart.png").toAbsolutePath();

	@Test
	void windowsSelectsTheFileRatherThanOpeningTheFolder() {

		// One argument, no space after the comma. Explorer parses the rest itself, and
		// given "/select, path" it opens the folder without selecting anything.
		var command = Reveal.command("Windows 11", FILE);

		assertEquals(List.of("explorer.exe", "/select," + FILE), command);
	}

	@Test
	void macOsRevealsIt() {
		assertEquals(List.of("open", "-R", FILE.toString()), Reveal.command("Mac OS X", FILE));
	}

	@Test
	void aLinuxDesktopIsGivenTheFolder() {

		// No portable way to ask a Linux file manager to select something inside a
		// folder, so it is opened at the folder and the user sees the file in it.
		var command = Reveal.command("Linux", FILE);

		assertEquals(List.of("xdg-open", FILE.getParent().toString()), command);
	}

	@Test
	void anUnknownSystemIsTreatedAsALinuxOne() {
		assertEquals(List.of("xdg-open", FILE.getParent().toString()), Reveal.command("Plan 9", FILE));
		assertEquals(List.of("xdg-open", FILE.getParent().toString()), Reveal.command(null, FILE));
	}

	@Test
	void aRelativePathIsMadeAbsoluteBeforeItIsHandedOver() {

		// A file manager is started in its own folder and would resolve a relative path
		// against the wrong one.
		var command = Reveal.command("Windows 11", Path.of("out", "chart.png"));

		assertTrue(command.get(1).startsWith("/select,"), command.toString());
		assertTrue(Path.of(command.get(1).substring("/select,".length())).isAbsolute(), command.toString());
	}

	@Test
	void aFileAtTheRootOfADriveHasSomewhereToOpen() {

		// getParent() is null at a root; the command must still name a folder.
		var command = Reveal.command("Linux", Path.of("/").toAbsolutePath());

		assertEquals(2, command.size());
		assertTrue(!command.get(1).isBlank(), command.toString());
	}
}
