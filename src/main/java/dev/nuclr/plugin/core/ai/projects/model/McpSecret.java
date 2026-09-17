package dev.nuclr.plugin.core.ai.projects.model;

import lombok.Data;

/**
 * Where a secret an MCP server needs - a bearer token, an API-key header - comes
 * from. Never the secret itself: definitions are written to files that are
 * committed, shared and exported.
 *
 * <ul>
 *   <li><b>stored</b> - kept in the OS credential store under {@link #key}. A
 *       stored secret whose key is {@code null} still has to be entered, which is
 *       how an imported profile arrives.</li>
 *   <li><b>environment</b> - read from the environment variable {@link #variable},
 *       which the user manages.</li>
 * </ul>
 */
@Data
public class McpSecret {

	/** The {@link #source} of a secret kept in the credential store. */
	public static final String STORED = "stored";

	/** The {@link #source} of a secret read from an environment variable. */
	public static final String ENVIRONMENT = "environment";

	/** {@link #STORED} or {@link #ENVIRONMENT}. */
	private String source = STORED;

	/** The credential-store key, for a stored secret; {@code null} until one is entered. */
	private String key;

	/** The environment variable, for an environment secret. */
	private String variable;

	/** Creates a stored secret that still has to be entered. */
	public McpSecret() {}

	/**
	 * A secret kept in the credential store.
	 *
	 * @param key the credential-store key, or {@code null} when not entered yet
	 * @return the reference
	 */
	public static McpSecret stored(String key) {
		var secret = new McpSecret();
		secret.setKey(key);
		return secret;
	}

	/**
	 * A secret read from an environment variable.
	 *
	 * @param variable the variable name
	 * @return the reference
	 */
	public static McpSecret environment(String variable) {
		var secret = new McpSecret();
		secret.setSource(ENVIRONMENT);
		secret.setVariable(variable);
		return secret;
	}

	/** Whether it comes from an environment variable rather than the credential store. */
	public boolean fromEnvironment() {
		return ENVIRONMENT.equalsIgnoreCase(source == null ? "" : source.trim());
	}

	/** Whether a stored secret still has to be entered. */
	public boolean needsEntry() {
		return !fromEnvironment() && (key == null || key.isBlank());
	}

	/** A copy. */
	public McpSecret copy() {
		var copy = new McpSecret();
		copy.source = source;
		copy.key = key;
		copy.variable = variable;
		return copy;
	}
}
