package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reading what the user typed in the composer: whether it is a command, which one, and
 * which commands a half-typed name could still become.
 *
 * <p>Kept apart from the window so the rules can be stated once and tested without a
 * screen. The rules are the ones every chat client has settled on: a slash at the very
 * start and nothing before it, a single line, and {@code //} to send a line that really
 * does begin with a slash.
 */
final class SlashCommands {

	private SlashCommands() {
	}

	/**
	 * One command the user asked for.
	 *
	 * @param name     the name typed after the slash, lower case, possibly empty
	 * @param argument the rest of the line, stripped; empty when there was none
	 */
	record Invocation(String name, String argument) {
	}

	/**
	 * Read a composed message as a command.
	 *
	 * @param text what is in the box
	 * @return the command, or empty when the text is an ordinary message
	 */
	static Optional<Invocation> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		var line = text.strip();
		// "//..." is an ordinary message that starts with a slash, and a command is one
		// line: a slash at the top of a multi-line paste is part of what is being sent.
		if (!line.startsWith("/") || line.startsWith("//") || line.contains("\n")) {
			return Optional.empty();
		}
		var body = line.substring(1);
		var space = body.indexOf(' ');
		var name = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
		var argument = space < 0 ? "" : body.substring(space + 1).strip();
		return Optional.of(new Invocation(name, argument));
	}

	/**
	 * A message as it should be sent, with an escaped leading slash unescaped.
	 *
	 * @param text what is in the box
	 * @return what the agent receives
	 */
	static String unescape(String text) {
		return text != null && text.stripLeading().startsWith("//") ? text.stripLeading().substring(1) : text;
	}

	/**
	 * The commands a half-typed name could still become, for the completion popup.
	 *
	 * <p>Only while the name itself is being typed: once there is a space the user has
	 * chosen a command and is writing its argument, and the popup gets out of the way.
	 *
	 * @param commands every command this window offers
	 * @param text     what is in the box
	 * @return the matches, in the order the commands were registered; empty when the
	 *         text is not the beginning of a command
	 */
	static List<SlashCommand> matching(List<SlashCommand> commands, String text) {
		if (text == null || !text.startsWith("/") || text.startsWith("//") || text.contains("\n")
				|| text.contains(" ")) {
			return List.of();
		}
		var prefix = text.substring(1).toLowerCase(Locale.ROOT);
		return commands.stream().filter(command -> command.name().startsWith(prefix)).toList();
	}

	/**
	 * Find a command by the name that was typed.
	 *
	 * @param commands every command this window offers
	 * @param name     the name, without its slash
	 * @return the command, or empty when there is no such command
	 */
	static Optional<SlashCommand> find(List<SlashCommand> commands, String name) {
		return commands.stream().filter(command -> command.name().equals(name)).findFirst();
	}
}
