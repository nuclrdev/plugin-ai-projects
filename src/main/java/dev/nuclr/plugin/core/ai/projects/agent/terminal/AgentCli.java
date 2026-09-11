package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The command-line agents this plugin ships window kinds for, and how to find
 * them on this machine.
 *
 * <p>Each entry is only a default: the executable and provider it names seed a
 * project harness, which the user is then free to change. Nothing here is
 * consulted once an agent has a resolved harness.
 *
 * @param id          suffix of the window kind, e.g. {@code claude-code}
 * @param displayName name shown in menus
 * @param executable  the command, without any platform extension
 * @param provider    provider identifier recorded in the harness
 * @param description one line for menus and tooltips
 */
public record AgentCli(String id, String displayName, String executable, String provider, String description) {

	/** Window-kind prefix shared by every terminal-backed agent. */
	public static final String KIND_PREFIX = "terminal.";

	private static final boolean WINDOWS =
			System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/** The agents offered out of the box, in menu order. */
	public static final List<AgentCli> BUILT_IN = List.of(
			new AgentCli("codex", "Codex", "codex", "openai",
					"OpenAI Codex CLI."),
			new AgentCli("claude-code", "Claude Code", "claude", "anthropic",
					"Anthropic Claude Code CLI."),
			new AgentCli("pi", "Pi", "pi", "pi",
					"Pi CLI."),
			new AgentCli("opencode", "OpenCode", "opencode", "opencode",
					"OpenCode CLI."),
			new AgentCli("shell", "Shell", null, null,
					"A plain shell in the agent's working directory."));

	/** The window kind this CLI backs. */
	public String kind() {
		return KIND_PREFIX + id;
	}

	/** Whether this entry is the plain shell rather than an agent CLI. */
	public boolean isShell() {
		return executable == null;
	}

	/**
	 * The harness settings a new agent of this kind starts with.
	 *
	 * @return a fresh spec, safe to modify
	 */
	public HarnessSpec defaultHarness() {
		var spec = new HarnessSpec();
		if (isShell()) {
			spec.setExecutable(defaultShell());
		} else {
			spec.setExecutable(executable);
			spec.setProvider(provider);
		}
		return spec;
	}

	/** The interactive shell for this platform. */
	public static String defaultShell() {
		if (WINDOWS) {
			return System.getenv().getOrDefault("COMSPEC", "cmd.exe");
		}
		var shell = System.getenv("SHELL");
		return shell == null || shell.isBlank() ? "/bin/bash" : shell;
	}

	/**
	 * Look an entry up by window kind.
	 *
	 * @param kind the window kind
	 * @return the entry, or empty when the kind is not one of ours
	 */
	public static Optional<AgentCli> byKind(String kind) {
		if (kind == null) {
			return Optional.empty();
		}
		return BUILT_IN.stream().filter(cli -> cli.kind().equals(kind)).findFirst();
	}

	/**
	 * Resolve a command against {@code PATH}, trying the Windows executable
	 * extensions as well.
	 *
	 * <p>Agent CLIs are usually installed by a package manager as a shim -
	 * {@code claude.cmd} on Windows - which a bare {@code claude} will not find.
	 * Resolving here means the failure the user sees is "not installed" rather
	 * than a pty error.
	 *
	 * @param command the command to resolve; may be a path already
	 * @return the resolved executable path, or empty when it is not on the path
	 */
	public static Optional<Path> resolveOnPath(String command) {
		if (command == null || command.isBlank()) {
			return Optional.empty();
		}
		var direct = Path.of(command);
		if (direct.isAbsolute() || command.contains("/") || command.contains("\\")) {
			return Files.isRegularFile(direct) ? Optional.of(direct) : Optional.empty();
		}
		var path = System.getenv("PATH");
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}
		var extensions = WINDOWS
				? List.of("", ".cmd", ".exe", ".bat", ".ps1")
				: List.of("");
		for (var directory : path.split(File.pathSeparator)) {
			if (directory.isBlank()) {
				continue;
			}
			for (var extension : extensions) {
				try {
					var candidate = Path.of(directory.trim(), command + extension);
					if (Files.isRegularFile(candidate)) {
						return Optional.of(candidate);
					}
				} catch (RuntimeException e) {
					// A malformed PATH entry is not worth failing over; try the next one.
					continue;
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Whether a command can be found on this machine, used to grey out a menu
	 * entry rather than let the user start an agent that cannot run.
	 *
	 * @param command the command
	 * @return whether it resolves
	 */
	public static boolean isInstalled(String command) {
		return resolveOnPath(command).isPresent();
	}
}
