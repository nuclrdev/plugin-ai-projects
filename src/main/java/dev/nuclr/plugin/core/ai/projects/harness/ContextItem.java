package dev.nuclr.plugin.core.ai.projects.harness;

import java.nio.file.Path;

/**
 * One thing an agent receives, as it will actually receive it.
 *
 * @param kind      what sort of thing this is
 * @param label     its name, as the agent sees it
 * @param detail    the resolved value: a path, a command line, a variable value
 * @param source    which level contributed it
 * @param path      the file it resolves to, or {@code null} when it is not a file
 * @param available whether that file exists; a missing instruction is worth seeing
 * @param linked    whether that file lives outside the project, linked from elsewhere
 */
public record ContextItem(Kind kind, String label, String detail, Provenance source, Path path, boolean available,
		boolean linked) {

	/** The categories the resolved-context view groups by. */
	public enum Kind {

		/** Instruction document, from the harness or from a context spec. */
		INSTRUCTION("Instructions"),

		/** Skill document from the project's skills directory. */
		SKILL("Skills"),

		/** A file whose content is injected into the agent's context. */
		INJECTED_FILE("Injected files"),

		/** An MCP server or tool provider definition. */
		MCP_TOOL("MCP / tools"),

		/** An environment variable set on the agent process. */
		ENVIRONMENT("Environment"),

		/** A permission the harness runs with. */
		PERMISSION("Permissions"),

		/** A root the agent is allowed to touch. */
		ALLOWED_ROOT("Allowed roots"),

		/** A free-form context variable. */
		VARIABLE("Context variables"),

		/** Project knowledge the agent is pointed at: a document, folder or URL. */
		KNOWLEDGE("Project knowledge"),

		/** A rule for what the agent loads into its context. */
		LOADING_RULE("Context-loading rules"),

		/** A built-in tool the harness grants. */
		TOOL("Tools"),

		/** Software the agent may drive. */
		SOFTWARE("Software access"),

		/** Hardware the agent may reach. */
		HARDWARE("Hardware access"),

		/** A host or network the agent may reach. */
		NETWORK("Network access"),

		/** The sandbox, or a limit on a run. */
		LIMIT("Runtime and limits");

		private final String groupLabel;

		Kind(String groupLabel) {
			this.groupLabel = groupLabel;
		}

		/** Heading for this category in the resolved-context view. */
		public String groupLabel() {
			return groupLabel;
		}
	}

	/**
	 * A file-backed item.
	 *
	 * @param kind   the category
	 * @param label  display name
	 * @param path   the resolved file, possibly missing
	 * @param source contributing level
	 * @return the item
	 */
	public static ContextItem file(Kind kind, String label, Path path, Provenance source) {
		return file(kind, label, path, source, false);
	}

	/**
	 * A file-backed item that may be linked from outside the project.
	 *
	 * @param kind   the category
	 * @param label  display name
	 * @param path   the resolved file, possibly missing
	 * @param source contributing level
	 * @param linked whether the file lives outside the project
	 * @return the item
	 */
	public static ContextItem file(Kind kind, String label, Path path, Provenance source, boolean linked) {
		var exists = path != null && java.nio.file.Files.isRegularFile(path);
		return new ContextItem(kind, label, path == null ? "" : path.toString(), source, path, exists,
				path != null && linked);
	}

	/**
	 * A value-backed item.
	 *
	 * @param kind   the category
	 * @param label  display name
	 * @param detail the value
	 * @param source contributing level
	 * @return the item
	 */
	public static ContextItem value(Kind kind, String label, String detail, Provenance source) {
		return new ContextItem(kind, label, detail == null ? "" : detail, source, null, true, false);
	}
}
