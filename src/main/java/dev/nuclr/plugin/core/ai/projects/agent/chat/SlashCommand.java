package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.function.Consumer;

/**
 * One command the composer understands, typed with a leading slash.
 *
 * <p>Commands are the window's, not the CLI's. A conversation window drives its agent
 * over a protocol - stream-JSON, an app server, an RPC - and the slash commands those
 * tools offer belong to their own terminal interfaces; typed here they would reach the
 * model as plain text. So {@code /model} is answered with a picker of this plugin's own,
 * built from what the CLI said it can do.
 *
 * @param name    what the user types after the slash, lower case
 * @param summary one line for the completion popup and {@code /help}
 * @param usage   how an argument is written, e.g. {@code [model]}, or empty when it takes none
 * @param run     given the argument the user typed, empty when they typed none
 */
record SlashCommand(String name, String summary, String usage, Consumer<String> run) {

	/**
	 * A command with no argument.
	 *
	 * @param name    what the user types after the slash
	 * @param summary one line about it
	 * @param run     what it does
	 * @return the command
	 */
	static SlashCommand of(String name, String summary, Runnable run) {
		return new SlashCommand(name, summary, "", argument -> run.run());
	}

	/** How the command reads in a list: {@code /model [model]}. */
	String display() {
		return "/" + name + (usage.isEmpty() ? "" : " " + usage);
	}
}
