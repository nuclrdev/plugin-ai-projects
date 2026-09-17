package dev.nuclr.plugin.core.ai.projects.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The document an agent is actually handed at launch: the content of every
 * resolved instruction, skill and injected file, the context variables, the
 * project knowledge and context-loading rules, and a note of anything referenced
 * but missing.
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
 * @param references how many project-knowledge references and context-loading rules it carries
 * @param missing   how many referenced documents could not be read
 */
public record AgentBriefing(String text, int documents, int variables, int references, int missing) {

	/** Longest single document included in full; a runaway file must not swamp the agent. */
	static final int DOCUMENT_LIMIT = 100_000;

	/** Whether there is nothing to deliver. */
	public boolean isEmpty() {
		return documents == 0 && variables == 0 && references == 0 && missing == 0;
	}

	/**
	 * Build the briefing for one agent. Reads every document the context names, so
	 * not on the event thread.
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

		var notRead = new java.util.ArrayList<ContextItem>();
		for (var kind : new ContextItem.Kind[] {
				ContextItem.Kind.INSTRUCTION, ContextItem.Kind.SKILL, ContextItem.Kind.INJECTED_FILE }) {
			var section = new StringBuilder();
			for (var item : context.of(kind)) {
				var content = item.available() ? read(item.path()) : null;
				if (content == null) {
					missing++;
					notRead.add(item);
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

		var knowledge = context.of(ContextItem.Kind.KNOWLEDGE);
		if (!knowledge.isEmpty()) {
			body.append("## ").append(ContextItem.Kind.KNOWLEDGE.groupLabel()).append("\n\n")
					.append("Consult these when they are relevant; they are not included here.\n\n");
			for (var item : knowledge) {
				body.append("- ").append(item.label()).append('\n');
			}
			body.append('\n');
		}

		var rules = context.of(ContextItem.Kind.LOADING_RULE);
		if (!rules.isEmpty()) {
			body.append("## ").append(ContextItem.Kind.LOADING_RULE.groupLabel()).append("\n\n")
					.append("Follow these when deciding what to read into your context.\n\n");
			for (var item : rules) {
				body.append("- ").append(item.label()).append('\n');
			}
			body.append('\n');
		}

		if (missing > 0) {
			body.append("## Not found\n\n")
					.append("These were configured for you but could not be read. Mention it if they matter.\n\n");
			// Listed from the first pass: reading every document a second time only to find the missing ones again
			// doubles the work, and a file could change in between.
			for (var item : notRead) {
				body.append("- ").append(item.label())
						.append(item.path() == null ? " (outside the permitted folders)" : " (" + item.path() + ")")
						.append('\n');
			}
			body.append('\n');
		}

		if (body.isEmpty()) {
			return new AgentBriefing("", 0, 0, 0, 0);
		}
		var text = "# Briefing for " + agentName + " in " + projectName + "\n\n"
				+ "Provided by Nuclr Commander from this project's configuration. Follow these instructions\n"
				+ "for the whole session; where they conflict with a later request, say so before acting.\n\n"
				+ body.toString().stripTrailing() + "\n";
		return new AgentBriefing(text, documents, variables.size(), knowledge.size() + rules.size(), missing);
	}

	/**
	 * A document's text, truncated if enormous; {@code null} when it cannot be read.
	 * Only as much as is used is read, so a huge file is never loaded whole.
	 */
	private static String read(Path path) {
		if (path == null) {
			return null;
		}
		try {
			var text = dev.nuclr.plugin.core.ai.projects.store.TextFiles.readBounded(path, DOCUMENT_LIMIT);
			return text.length() <= DOCUMENT_LIMIT ? text
					: text.substring(0, DOCUMENT_LIMIT) + "\n\n[... truncated by Nuclr Commander; read " + path
							+ " for the rest]";
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
