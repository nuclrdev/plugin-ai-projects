package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.util.List;
import java.util.Locale;

/**
 * A Windows command line that a program's {@code argv} splits back into exactly
 * the arguments it was built from.
 *
 * <p>Windows passes a program one string, not a list; the C runtime (and
 * {@code CommandLineToArgvW}) split it again. pty4j's own joining only wraps an
 * argument in quotes when it holds a space and never escapes a quote inside one, so
 * {@code "hi"} arrives as {@code hi} and a value like {@code say "hi" now} is torn
 * apart. Briefings, TOML values and tool patterns all carry quotes, so the command
 * line is built here instead, following the runtime's rules:
 * <ul>
 *   <li>an argument that is empty or holds whitespace or a quote is wrapped in quotes;</li>
 *   <li>a quote inside is written {@code \"}, and the backslashes before it doubled;</li>
 *   <li>backslashes before the closing quote are doubled; others are left alone.</li>
 * </ul>
 * A {@code .cmd} or {@code .bat} shim is read by {@code cmd.exe} first, which knows
 * none of this; {@link ContextDelivery} already keeps multi-line text away from those.
 */
public final class WindowsCommandLine {

	private static final boolean WINDOWS =
			System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private WindowsCommandLine() {
	}

	/** Whether this machine needs a command line built here. */
	public static boolean applies() {
		return WINDOWS;
	}

	/**
	 * Give a process builder its command so that every argument arrives unchanged:
	 * on Windows as a command line built here, elsewhere as the list itself.
	 *
	 * @param builder   the builder
	 * @param arguments the program, then its arguments
	 * @return the builder
	 */
	public static com.pty4j.PtyProcessBuilder setCommand(com.pty4j.PtyProcessBuilder builder, List<String> arguments) {
		return applies() ? builder.setCommand(new com.pty4j.Command.RawCommandString(join(arguments)))
				: builder.setCommand(arguments.toArray(String[]::new));
	}

	/**
	 * Join arguments into one command line.
	 *
	 * @param arguments the program, then its arguments
	 * @return the command line
	 */
	public static String join(List<String> arguments) {
		var line = new StringBuilder();
		for (var argument : arguments) {
			if (!line.isEmpty()) {
				line.append(' ');
			}
			quote(argument, line);
		}
		return line.toString();
	}

	private static void quote(String argument, StringBuilder line) {
		if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == 0x0B
				|| c == '"')) {
			line.append(argument);
			return;
		}
		line.append('"');
		var backslashes = 0;
		for (var index = 0; index < argument.length(); index++) {
			var c = argument.charAt(index);
			if (c == '\\') {
				backslashes++;
				continue;
			}
			if (c == '"') {
				line.append("\\".repeat(backslashes * 2 + 1)).append('"');
			} else {
				line.append("\\".repeat(backslashes)).append(c);
			}
			backslashes = 0;
		}
		line.append("\\".repeat(backslashes * 2)).append('"');
	}
}
