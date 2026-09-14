package dev.nuclr.plugin.core.ai.projects.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The document an agent is actually handed at launch: the content of every
 * resolved instruction, skill and injected file, the context variables, and a
 * note of anything referenced but missing.
 *
 * <p>Resolving context only describes it. Until this existed a terminal agent
 * was started with a command line and an environment, and an instruction such as
 * "No code changes" appeared in the Resolved Context view while the agent itself
 * never saw a word of it. The briefing is what closes that gap: one Markdown
 * document, built from the same {@link ResolvedContext} the view shows, so the
 * two cannot disagree.
 *
 * @param text      the briefing, empty when there is nothing to tell the agent
 * @param documents how many documents it carries
 * @param variables how many context variables it carries
 * @param missing   how many referenced documents could not be read
 */
public record AgentBriefing(String text, int documents, int variables, int missing) {

	/** Longest single document included in full; a runaway file must not swamp the agent. */
	static final int DOCUMENT_LIMIT = 100_000;

	/** Whether there is nothing to deliver. */
	public boolean isEmpty() {
		return documents == 0 && variables == 0 && missing == 0;
	}

	/**
	 * Build the briefing for one agent.
	 *
	 * @param projectName the project's display name
	 * @param agentName   the agent's display name
	 * @param context     the agent's resolved context
	 * @return the briefing, never {@code null}
	 */
	public static AgentBriefing of(String projectName, String agentName, ResolvedContext context) {

		var body = new StringBuilder();
		var documents = 0;
		var missing = 0;

		for (var kind : new ContextItem.Kind[] {
				ContextItem.Kind.INSTRUCTION, ContextItem.Kind.SKILL, ContextItem.Kind.INJECTED_FILE }) {
			var section = new StringBuilder();
			for (var item : context.of(kind)) {
				var content = item.available() ? read(item.path()) : null;
				if (content == null) {
					missing++;
					continue;
				}
				documents++;
				section.append("### ").append(item.label()).append("\n\n")
						.append(content.strip()).append("\n\n");
			}
			if (!section.isEmpty()) {
				body.append("## ").append(kind.groupLabel()).append("\n\n").append(section);
			}
		}

		var variables = context.of(ContextItem.Kind.VARIABLE);
		if (!variables.isEmpty()) {
			body.append("## ").append(ContextItem.Kind.VARIABLE.groupLabel()).append("\n\n");
			for (var variable : variables) {
				body.append("- `").append(variable.label()).append("` = ").append(variable.detail()).append('\n');
			}
			body.append('\n');
		}

		if (missing > 0) {
			body.append("## Not found\n\n")
					.append("These were configured for you but could not be read. Mention it if they matter.\n\n");
			for (var kind : new ContextItem.Kind[] {
					ContextItem.Kind.INSTRUCTION, ContextItem.Kind.SKILL, ContextItem.Kind.INJECTED_FILE }) {
				for (var item : context.of(kind)) {
					if (!item.available() || read(item.path()) == null) {
						body.append("- ").append(item.label())
								.append(item.path() == null ? " (outside the permitted folders)" : " (" + item.path() + ")")
								.append('\n');
					}
				}
			}
			body.append('\n');
		}

		if (body.isEmpty()) {
			return new AgentBriefing("", 0, 0, 0);
		}
		var text = "# Briefing for " + agentName + " in " + projectName + "\n\n"
				+ "Provided by Nuclr Commander from this project's configuration. Follow these instructions\n"
				+ "for the whole session; where they conflict with a later request, say so before acting.\n\n"
				+ body.toString().stripTrailing() + "\n";
		return new AgentBriefing(text, documents, variables.size(), missing);
	}

	/** A document's text, truncated if enormous; {@code null} when it cannot be read. */
	private static String read(Path path) {
		if (path == null) {
			return null;
		}
		try {
			var text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			return text.length() <= DOCUMENT_LIMIT ? text
					: text.substring(0, DOCUMENT_LIMIT) + "\n\n[... truncated by Nuclr Commander; read " + path
							+ " for the rest]";
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
