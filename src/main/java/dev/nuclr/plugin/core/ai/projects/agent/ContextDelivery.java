package dev.nuclr.plugin.core.ai.projects.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How an agent's briefing reaches the CLI it runs.
 *
 * <p>There is no portable way to hand a CLI its instructions, so each agent
 * gets the mechanism its own {@code --help} documents:
 * <ul>
 *   <li><b>Claude Code</b> - {@code --append-system-prompt <text>};</li>
 *   <li><b>Pi</b> - {@code --append-system-prompt <file>}, which it reads itself;</li>
 *   <li><b>Codex</b> - {@code -c developer_instructions="..."}, which Codex sends
 *       as a developer message, leaving its single {@code [PROMPT]} to the user;</li>
 *   <li><b>OpenCode</b> - {@code OPENCODE_CONFIG_CONTENT} naming the briefing as
 *       an instructions file, or {@code --prompt} when that variable is already
 *       the user's.</li>
 * </ul>
 * Anything else - a shell, an unknown command - gets nothing, and the launch
 * notice says so.
 *
 * <p>The CLI is recognised by the executable's name, not the window kind: a
 * "Terminal" agent overrides its executable with a shell, and a Codex window
 * pointed at {@code claude} runs Claude.
 *
 * <p>Text passed as an argument is replaced by a one-line pointer to the
 * briefing file when it would not survive the trip: through a {@code .cmd} or
 * {@code .bat} shim, where {@code cmd.exe} splits arguments at line breaks, or
 * past a length the Windows command line cannot carry.
 *
 * @param arguments   appended to the command line
 * @param environment added to the process environment
 * @param description one line for the transcript saying how the briefing was delivered
 */
public record ContextDelivery(List<String> arguments, Map<String, String> environment, String description) {

	/** Nothing delivered. */
	public static final ContextDelivery NONE = new ContextDelivery(List.of(), Map.of(), "");

	/** Longest briefing passed inline; Windows caps a whole command line at 32,767 characters. */
	public static final int INLINE_LIMIT = 24_000;

	/** OpenCode's inline configuration variable. */
	static final String OPENCODE_CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT";

	/** Defensive copies. */
	public ContextDelivery {
		arguments = List.copyOf(arguments);
		environment = Map.copyOf(environment);
	}

	/** Whether this plan hands the briefing to the agent at all. */
	public boolean delivered() {
		return !arguments.isEmpty() || !environment.isEmpty();
	}

	/**
	 * Decide how to deliver a briefing.
	 *
	 * @param executable         the executable as configured, e.g. {@code claude}
	 * @param resolvedExecutable where it was found, which tells a shim from a binary
	 * @param briefingFile       the written briefing
	 * @param briefingText       the briefing's content
	 * @param environment        the environment the agent will get, to avoid clobbering
	 * @return the plan; {@link #NONE} for a CLI with no known mechanism
	 */
	public static ContextDelivery plan(String executable, Path resolvedExecutable, Path briefingFile,
			String briefingText, Map<String, String> environment) {

		if (briefingText == null || briefingText.isBlank() || briefingFile == null) {
			return NONE;
		}
		var inline = inlineSafe(resolvedExecutable, briefingText);
		var pointer = "Read " + briefingFile + " now and follow it for this whole session: it holds your"
				+ " instructions, skills and context from Nuclr Commander.";

		return switch (cliName(executable)) {
			case "claude" -> new ContextDelivery(
					List.of("--append-system-prompt", inline ? briefingText : pointer), Map.of(),
					"Briefing delivered with --append-system-prompt" + (inline ? "" : " (as a pointer to " + briefingFile + ")"));
			case "pi" -> new ContextDelivery(
					List.of("--append-system-prompt", briefingFile.toString()), Map.of(),
					"Briefing delivered with --append-system-prompt " + briefingFile);
			// Not the [PROMPT]: Codex takes only one, and the startup arguments may already hold it.
			// The pointer goes unquoted: it is there because the value passes through cmd.exe, which
			// splits a quoted TOML string at its spaces. Unquoted it is not TOML, and Codex takes
			// a value that is not as the raw string.
			case "codex" -> new ContextDelivery(
					List.of("-c", "developer_instructions=" + (inline ? tomlString(briefingText) : pointer)),
					Map.of(),
					"Briefing delivered as Codex developer instructions"
							+ (inline ? "" : " (as a pointer to " + briefingFile + ")"));
			case "opencode" -> environment != null && environment.containsKey(OPENCODE_CONFIG_CONTENT)
					? new ContextDelivery(List.of("--prompt", pointer), Map.of(),
							"Briefing delivered as the opening prompt (" + OPENCODE_CONFIG_CONTENT + " was already set)")
					: new ContextDelivery(List.of(),
							Map.of(OPENCODE_CONFIG_CONTENT, "{\"instructions\":[\"" + json(briefingFile.toString()) + "\"]}"),
							"Briefing delivered as an OpenCode instructions file via " + OPENCODE_CONFIG_CONTENT);
			default -> NONE;
		};
	}

	/** The command's bare name: no directory, no platform extension, lower case. */
	static String cliName(String executable) {
		if (executable == null || executable.isBlank()) {
			return "";
		}
		var name = executable.trim().replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		for (var extension : List.of(".exe", ".cmd", ".bat", ".com", ".ps1")) {
			if (name.endsWith(extension)) {
				return name.substring(0, name.length() - extension.length());
			}
		}
		return name;
	}

	private static boolean inlineSafe(Path resolvedExecutable, String text) {
		if (text.length() > INLINE_LIMIT) {
			return false;
		}
		var name = resolvedExecutable == null || resolvedExecutable.getFileName() == null ? ""
				: resolvedExecutable.getFileName().toString().toLowerCase(Locale.ROOT);
		return !name.endsWith(".cmd") && !name.endsWith(".bat");
	}

	/**
	 * A TOML basic string, on one line. Codex parses a {@code -c} value as TOML, so a
	 * quoted value is taken exactly, where raw text could parse as something else.
	 */
	public static String tomlString(String text) {
		var escaped = new StringBuilder("\"");
		for (var c : text.toCharArray()) {
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				// TOML allows no other control character in a basic string, DEL included.
				default -> escaped.append(c < 0x20 || c == 0x7f ? String.format("\\u%04X", (int) c) : String.valueOf(c));
			}
		}
		return escaped.append('"').toString();
	}

	private static String json(String text) {
		var escaped = new StringBuilder();
		for (var c : text.toCharArray()) {
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				default -> escaped.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
			}
		}
		return escaped.toString();
	}
}
