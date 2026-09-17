package dev.nuclr.plugin.core.ai.projects.connector;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;

/**
 * What every connector checks and names the same way for MCP servers: URLs,
 * header and variable names, and the environment variables secrets travel in.
 */
public final class McpSupport {

	/** An HTTP header name, as RFC 9110 allows it. */
	private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

	/** An environment variable name that every platform accepts. */
	private static final Pattern VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	/** Header names whose value is a credential by definition. */
	private static final List<String> CREDENTIAL_HEADERS = List.of("authorization", "proxy-authorization", "cookie",
			"x-api-key", "api-key", "x-auth-token");

	private McpSupport() {
	}

	/**
	 * The environment variable a stored secret is handed to the agent in. Prefixed,
	 * so it can never replace a variable the agent itself relies on.
	 *
	 * @param server the server
	 * @param part   what the secret is for, e.g. {@code TOKEN} or {@code HEADER_X_API_KEY}
	 * @return the variable name
	 */
	static String variable(McpServerSpec server, String part) {
		return "NUCLR_MCP_" + upper(server.getName()) + "_" + upper(part);
	}

	/**
	 * The variable a secret is read from at launch: its own variable when it comes
	 * from the environment, a Nuclr-named one when it is stored.
	 *
	 * @param server the server
	 * @param part   what the secret is for
	 * @param secret the reference
	 * @return the variable name
	 */
	static String variableFor(McpServerSpec server, String part, McpSecret secret) {
		return secret.fromEnvironment() ? text(secret.getVariable()) : variable(server, part);
	}

	/**
	 * Problems with one server that do not depend on the CLI.
	 *
	 * @param server the server
	 * @return messages
	 */
	public static List<String> problems(McpServerSpec server) {
		var problems = new ArrayList<String>();
		var name = "\"" + text(server.getName()) + "\"";
		if (server.remote()) {
			if (!isHttpUrl(server.getUrl())) {
				problems.add("MCP servers: " + name + " needs an http:// or https:// URL.");
			}
			var auth = server.authOrDefault();
			if (!List.of(McpServerSpec.AUTH_NONE, McpServerSpec.AUTH_BEARER, McpServerSpec.AUTH_OAUTH).contains(auth)) {
				problems.add("MCP servers: " + name + " has an unknown authentication \"" + server.getAuth() + "\".");
			}
			if (McpServerSpec.AUTH_BEARER.equals(auth)) {
				problems.addAll(secretProblems(name + " bearer token", server.getBearerToken()));
			}
			for (var header : nullToEmpty(server.getHeaders()).entrySet()) {
				if (!HEADER_NAME.matcher(header.getKey()).matches()) {
					problems.add("MCP servers: " + name + " header \"" + header.getKey() + "\" is not a valid header name.");
				} else if (looksLikeCredential(header.getKey(), header.getValue())) {
					problems.add("MCP servers: " + name + " header \"" + header.getKey() + "\" looks like a credential. "
							+ "Make it a stored secret, so it is not written into the profile.");
				}
			}
			for (var header : nullToEmpty(server.getSecretHeaders()).entrySet()) {
				if (!HEADER_NAME.matcher(header.getKey()).matches()) {
					problems.add("MCP servers: " + name + " header \"" + header.getKey() + "\" is not a valid header name.");
				}
				problems.addAll(secretProblems(name + " header \"" + header.getKey() + "\"", header.getValue()));
			}
			if (McpServerSpec.AUTH_BEARER.equals(auth) && nullToEmpty(server.getHeaders()).keySet().stream()
					.anyMatch(header -> header.equalsIgnoreCase("Authorization"))
					|| McpServerSpec.AUTH_BEARER.equals(auth) && nullToEmpty(server.getSecretHeaders()).keySet().stream()
							.anyMatch(header -> header.equalsIgnoreCase("Authorization"))) {
				problems.add("MCP servers: " + name + " sets an Authorization header and a bearer token; keep one.");
			}
		} else {
			if (server.getCommand() == null || server.getCommand().isBlank()) {
				problems.add("MCP servers: " + name + " has no command.");
			}
			for (var variable : nullToEmpty(server.getSecretEnv()).entrySet()) {
				if (!VARIABLE.matcher(variable.getKey()).matches()) {
					problems.add("MCP servers: " + name + " environment variable \"" + variable.getKey()
							+ "\" - use letters, digits and underscores.");
				}
				problems.addAll(secretProblems(name + " environment variable \"" + variable.getKey() + "\"",
						variable.getValue()));
			}
		}
		return problems;
	}

	private static List<String> secretProblems(String what, McpSecret secret) {
		if (secret == null) {
			return List.of("MCP servers: " + what + " is not set.");
		}
		if (secret.fromEnvironment() && (secret.getVariable() == null || !VARIABLE.matcher(secret.getVariable().strip()).matches())) {
			return List.of("MCP servers: " + what + " needs a valid environment variable name.");
		}
		// A stored secret with no key is one to enter - it arrives that way on import - and
		// does not stop a save. The editor shows it as needed.
		return List.of();
	}

	public static boolean isHttpUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			var uri = new URI(url.strip());
			var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Whether a fixed header value looks like something that must not be written into
	 * a shared file.
	 *
	 * @param name  the header name
	 * @param value its value
	 * @return whether to insist on a stored secret
	 */
	public static boolean looksLikeCredential(String name, String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		var lowerName = name.toLowerCase(Locale.ROOT);
		var lowerValue = value.strip().toLowerCase(Locale.ROOT);
		return CREDENTIAL_HEADERS.contains(lowerName) || lowerName.contains("token") || lowerName.contains("secret")
				|| lowerName.contains("password") || lowerValue.startsWith("bearer ") || lowerValue.startsWith("basic ");
	}

	static <V> Map<String, V> nullToEmpty(Map<String, V> map) {
		return map == null ? Map.of() : map;
	}

	private static String upper(String value) {
		return text(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
	}

	/**
	 * A value stripped, and empty rather than {@code null}: setup is also built for
	 * previews of servers not yet validated.
	 *
	 * @param value the value, possibly {@code null}
	 * @return the stripped value
	 */
	public static String text(String value) {
		return value == null ? "" : value.strip();
	}

	/**
	 * Two secrets handed to the agent in the same environment variable. They can meet
	 * when a CLI forwards variables by the name a server asks for - two Codex servers
	 * each wanting {@code API_KEY} - or when two server names differ only in
	 * punctuation, so their Nuclr variables come out the same. One would silently
	 * overwrite the other, so unless both are the same secret, it is refused.
	 *
	 * @param bindings every binding a setup produced
	 * @return messages, one per variable in conflict
	 */
	public static List<String> bindingConflicts(List<AgentConnector.SecretBinding> bindings) {
		var sources = new java.util.LinkedHashMap<String, String>();
		var conflicting = new java.util.LinkedHashSet<String>();
		for (var binding : bindings) {
			var source = sourceOf(binding.secret());
			var previous = sources.putIfAbsent(binding.variable(), source);
			if (previous != null && (!previous.equals(source) || source.startsWith("missing:"))) {
				conflicting.add(binding.variable());
			}
		}
		return conflicting.stream()
				.map(variable -> "MCP servers: more than one secret would be passed in the environment variable "
						+ variable + ", and one would overwrite the other. Use the same secret for both, or rename one.")
				.toList();
	}

	/** What a secret is, for telling two apart: a stored key, a variable, or a secret still to enter. */
	private static String sourceOf(McpSecret secret) {
		if (secret.fromEnvironment()) {
			return "environment:" + text(secret.getVariable());
		}
		return secret.needsEntry() ? "missing:" + System.identityHashCode(secret) : "stored:" + secret.getKey();
	}
}
