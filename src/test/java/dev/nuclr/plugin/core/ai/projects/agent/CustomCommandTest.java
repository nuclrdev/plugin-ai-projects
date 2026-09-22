package dev.nuclr.plugin.core.ai.projects.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.WindowsCommandLine;

/** An agent's own command runs through the shell with the window's arguments intact. */
class CustomCommandTest {

	/** Prints each argument as its character codes, one per line. */
	public static final class Echo {

		public static void main(String[] args) {
			for (var argument : args) {
				var codes = new ArrayList<String>();
				argument.chars().forEach(c -> codes.add(Integer.toString(c)));
				System.out.println("ARG[" + String.join(",", codes) + "]");
			}
		}
	}

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/** Arguments a conversation window really passes: flags, values with spaces and quotes, paths. */
	private static final List<String> ARGUMENTS = List.of("app-server", "-c", "model=\"gpt-5\"", "has space",
			"say \"hi\" now", "C:\\dir with space\\", "");

	@Test
	void posixRunsTheCommandInTheUsersInteractiveLoginShell() {
		var wrapped = CustomCommand.wrap("nvm use 21 && codex", List.of("app-server", "a b"), "Mac OS X",
				"/bin/zsh", "cmd.exe");

		assertEquals(List.of("/bin/zsh", "-l", "-i", "-c", "nvm use 21 && codex \"$@\"", "nuclr-agent", "app-server",
				"a b"), wrapped);
	}

	@Test
	void aShellThatDoesNotSpeakPosixIsReplaced() {
		var wrapped = CustomCommand.wrap("codex", List.of(), "Linux", "/usr/bin/fish", "cmd.exe");

		assertTrue(List.of("/bin/zsh", "/bin/bash", "/bin/sh").contains(wrapped.getFirst()), wrapped.getFirst());
	}

	@Test
	void windowsRunsTheCommandThroughCmd() {
		var wrapped = CustomCommand.wrap("nvm use 21 && codex", List.of("app-server", "a b"), "Windows 11", null,
				"C:\\Windows\\system32\\cmd.exe");

		assertEquals(List.of("C:\\Windows\\system32\\cmd.exe", "/d", "/s", "/c",
				"\"nvm use 21 && codex app-server \"a b\"\""), wrapped);
	}

	@Test
	void argumentsArriveUnchangedThroughTheShell() throws Exception {
		var javaExecutable = ProcessHandle.current().info().command().orElseThrow();
		var classes = Path.of(Echo.class.getProtectionDomain().getCodeSource().getLocation()
				.toURI()).toString();
		// A setup step first, as with "nvm use 21 && codex", so the command really is a shell line.
		var command = (WINDOWS ? "echo setup>nul && " : "true && ")
				+ WindowsCommandLine.join(List.of(javaExecutable, "-cp", classes, Echo.class.getName()));
		if (!WINDOWS) {
			Assumptions.assumeTrue(javaExecutable.indexOf('\'') < 0 && classes.indexOf('\'') < 0);
			command = "true && '" + javaExecutable + "' -cp '" + classes + "' " + Echo.class.getName();
		}

		var process = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(CustomCommand.wrap(command, ARGUMENTS)))
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		process.getOutputStream().close();
		var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(60, TimeUnit.SECONDS));

		assertEquals(ARGUMENTS, decode(output), output);
	}

	private static List<String> decode(String output) {
		var received = new ArrayList<String>();
		var matcher = java.util.regex.Pattern.compile("ARG\\[([0-9,]*)]").matcher(output);
		while (matcher.find()) {
			var text = new StringBuilder();
			if (!matcher.group(1).isEmpty()) {
				for (var code : matcher.group(1).split(",")) {
					text.append((char) Integer.parseInt(code));
				}
			}
			received.add(text.toString());
		}
		return received;
	}
}
