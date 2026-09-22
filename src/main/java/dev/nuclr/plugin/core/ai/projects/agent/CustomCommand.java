package dev.nuclr.plugin.core.ai.projects.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.WindowsCommandLine;

/**
 * An agent's own command line, run in place of its CLI's executable.
 *
 * <p>A CLI installed through a version manager is often not runnable by name from a
 * desktop application: macOS gives apps started from the Dock a bare {@code PATH},
 * and a tool such as {@code nvm} is a shell function that exists only once the
 * user's shell has read its startup files. So the command the user types -
 * {@code nvm use 21 && codex} - is handed to their shell exactly as a terminal
 * would run it, and the arguments the window needs are appended to it.
 *
 * <p>On macOS and Linux that is the user's login shell, started interactive so it
 * reads {@code .zshrc} or {@code .bashrc}, where version managers are set up.
 * Anything those files print is harmless: a conversation reads only the JSON lines
 * its CLI writes. On Windows it is {@code cmd.exe}.
 */
public final class CustomCommand {

	/** The shells whose {@code "$@"} this relies on; any other falls back to one of these. */
	private static final Set<String> POSIX_SHELLS = Set.of("sh", "bash", "zsh", "ksh", "dash");

	/** {@code $0} for the shell script, which is what error messages from the shell are prefixed with. */
	private static final String SCRIPT_NAME = "nuclr-agent";

	private CustomCommand() {
	}

	/**
	 * The command line to spawn.
	 *
	 * @param command   what the user typed, e.g. {@code nvm use 21 && codex}
	 * @param arguments the arguments the window adds after the CLI's executable
	 * @return the program, then its arguments
	 */
	public static List<String> wrap(String command, List<String> arguments) {
		return wrap(command, arguments, System.getProperty("os.name", ""), System.getenv("SHELL"),
				System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
	}

	/**
	 * The command line to spawn, for a given platform.
	 *
	 * @param command   what the user typed
	 * @param arguments the arguments the window adds after the CLI's executable
	 * @param osName    the value of {@code os.name}
	 * @param shell     the user's {@code SHELL}, or {@code null}
	 * @param comspec   the Windows command interpreter
	 * @return the program, then its arguments
	 */
	static List<String> wrap(String command, List<String> arguments, String osName, String shell, String comspec) {
		var line = command.strip();
		var wrapped = new ArrayList<String>();
		if (osName.toLowerCase(Locale.ROOT).contains("win")) {
			// cmd.exe /s strips the outer quotes and runs the rest as typed; the arguments
			// are quoted as the CLI's runtime reads them.
			var full = arguments.isEmpty() ? line : line + " " + WindowsCommandLine.join(arguments);
			wrapped.add(comspec);
			wrapped.add("/d");
			wrapped.add("/s");
			wrapped.add("/c");
			wrapped.add('"' + full + '"');
			return wrapped;
		}
		// The arguments travel as the script's positional parameters, so none of them is
		// ever parsed by the shell: "$@" hands each one over exactly as it was.
		wrapped.add(posixShell(shell));
		wrapped.add("-l");
		wrapped.add("-i");
		wrapped.add("-c");
		wrapped.add(line + " \"$@\"");
		wrapped.add(SCRIPT_NAME);
		wrapped.addAll(arguments);
		return wrapped;
	}

	/**
	 * A stand-in for the executable, for code that asks where the CLI was found.
	 *
	 * <p>On Windows it names a batch file, so a briefing is never put on the command
	 * line, where {@code cmd.exe} would misread it; it goes by file instead.
	 *
	 * @return the stand-in
	 */
	public static Path resolvedStandIn() {
		return Path.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
				? "custom-command.cmd"
				: "custom-command");
	}

	/**
	 * Whether a Windows program is {@code cmd.exe}, whose command line is its own and must
	 * reach it untouched.
	 *
	 * @param program the program
	 * @return whether it is the command interpreter
	 */
	public static boolean isCommandInterpreter(String program) {
		var name = Path.of(program).getFileName();
		return name != null && name.toString().toLowerCase(Locale.ROOT).equals("cmd.exe");
	}

	private static String posixShell(String shell) {
		if (shell != null && !shell.isBlank()) {
			var name = Path.of(shell).getFileName();
			if (name != null && POSIX_SHELLS.contains(name.toString())) {
				return shell;
			}
		}
		// fish and friends spell "$@" differently; use a shell that reads what we write.
		for (var candidate : List.of("/bin/zsh", "/bin/bash")) {
			if (Files.isExecutable(Path.of(candidate))) {
				return candidate;
			}
		}
		return "/bin/sh";
	}
}
