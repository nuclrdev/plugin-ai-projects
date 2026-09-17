package dev.nuclr.plugin.core.ai.projects.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * One MCP server an agent is allowed to reach: either a local process the CLI
 * starts, or a remote server it connects to.
 *
 * <ul>
 *   <li><b>local</b> ({@code stdio}) - {@link #command}, {@link #args} and
 *       {@link #env}, with {@link #secretEnv} for variables that hold secrets;</li>
 *   <li><b>remote</b> ({@code http} or {@code sse}) - {@link #url}, an
 *       {@link #auth authentication} method, and {@link #headers} with
 *       {@link #secretHeaders} for headers that carry secrets.</li>
 * </ul>
 * Secrets are never stored here, only {@link McpSecret references} to them.
 *
 * <p>Fields that do not apply to the transport are left {@code null} and so
 * absent from the file, keeping existing local-server definitions unchanged.
 */
@Data
public class McpServerSpec {

	/** Local server: a process started over stdio. */
	public static final String STDIO = "stdio";
	/** Remote server over streamable HTTP. */
	public static final String HTTP = "http";
	/** Remote server over server-sent events. */
	public static final String SSE = "sse";

	/** No authentication. */
	public static final String AUTH_NONE = "none";
	/** A bearer token, sent as {@code Authorization: Bearer ...}. */
	public static final String AUTH_BEARER = "bearer";
	/** OAuth, signed in through the CLI, which keeps its own tokens. */
	public static final String AUTH_OAUTH = "oauth";

	/** Name the agent sees, and the key used when an agent overrides one server. */
	private String name;

	/** {@link #STDIO} (also when {@code null}), {@link #HTTP} or {@link #SSE}. */
	private String transport;

	/** Executable that starts a local server. */
	private String command;

	/** Arguments passed to {@link #command}. */
	private List<String> args = List.of();

	/** Extra environment for a local server's process. */
	private Map<String, String> env = new LinkedHashMap<>();

	/** Environment variables of a local server whose values are secrets. */
	private Map<String, McpSecret> secretEnv;

	/** A remote server's address. */
	private String url;

	/** {@link #AUTH_NONE} (also when {@code null}), {@link #AUTH_BEARER} or {@link #AUTH_OAUTH}. */
	private String auth;

	/** The token, for {@link #AUTH_BEARER}. */
	private McpSecret bearerToken;

	/** Fixed HTTP headers sent to a remote server. */
	private Map<String, String> headers;

	/** HTTP headers whose values are secrets. */
	private Map<String, McpSecret> secretHeaders;

	/** Disabled entries stay in the file so a project can turn one off without losing it. */
	private boolean enabled = true;

	/** Creates an empty spec. */
	public McpServerSpec() {}

	/**
	 * A local server.
	 *
	 * @param name    server name
	 * @param command executable
	 * @param args    arguments
	 * @return a new enabled spec
	 */
	public static McpServerSpec of(String name, String command, List<String> args) {
		var spec = new McpServerSpec();
		spec.setName(name);
		spec.setCommand(command);
		spec.setArgs(copyArguments(args));
		return spec;
	}

	/**
	 * A remote server.
	 *
	 * @param name      server name
	 * @param transport {@link #HTTP} or {@link #SSE}
	 * @param url       its address
	 * @return a new enabled spec with no authentication
	 */
	public static McpServerSpec remote(String name, String transport, String url) {
		var spec = new McpServerSpec();
		spec.setName(name);
		spec.setTransport(transport);
		spec.setUrl(url);
		spec.setArgs(null);
		spec.setEnv(null);
		return spec;
	}

	/** Whether this is a remote server. */
	public boolean remote() {
		return HTTP.equalsIgnoreCase(normalized(transport)) || SSE.equalsIgnoreCase(normalized(transport));
	}

	/** The transport, {@link #STDIO} when unset. */
	public String transportOrDefault() {
		var value = normalized(transport);
		return value.isEmpty() ? STDIO : value.toLowerCase(java.util.Locale.ROOT);
	}

	/** The authentication method, {@link #AUTH_NONE} when unset. */
	public String authOrDefault() {
		var value = normalized(auth);
		return value.isEmpty() ? AUTH_NONE : value.toLowerCase(java.util.Locale.ROOT);
	}

	/** Every secret this server refers to, in no particular order. */
	public List<McpSecret> secrets() {
		var secrets = new ArrayList<McpSecret>();
		if (bearerToken != null) {
			secrets.add(bearerToken);
		}
		if (secretHeaders != null) {
			secrets.addAll(secretHeaders.values());
		}
		if (secretEnv != null) {
			secrets.addAll(secretEnv.values());
		}
		secrets.removeIf(java.util.Objects::isNull);
		return secrets;
	}

	/** A deep copy, so merging never aliases the project's own lists or secrets. */
	public McpServerSpec copy() {
		var copy = new McpServerSpec();
		copy.name = name;
		copy.transport = transport;
		copy.command = command;
		copy.args = args == null ? null : copyArguments(args);
		copy.env = env == null ? null : copyValues(env);
		copy.secretEnv = copySecrets(secretEnv);
		copy.url = url;
		copy.auth = auth;
		copy.bearerToken = bearerToken == null ? null : bearerToken.copy();
		copy.headers = headers == null ? null : copyValues(headers);
		copy.secretHeaders = copySecrets(secretHeaders);
		copy.enabled = enabled;
		return copy;
	}

	/** What the server is, as shown in lists: the command line, or the URL. */
	public String displayCommandLine() {
		if (remote()) {
			return url == null ? "" : url;
		}
		var text = new StringBuilder(command == null ? "" : command);
		if (args != null) {
			for (var arg : args) {
				text.append(' ').append(arg);
			}
		}
		return text.toString();
	}

	private static String normalized(String value) {
		return value == null ? "" : value.trim();
	}

	private static List<String> copyArguments(List<String> arguments) {
		return arguments == null ? List.of()
				: arguments.stream().filter(java.util.Objects::nonNull).toList();
	}

	private static Map<String, String> copyValues(Map<String, String> values) {
		var copy = new LinkedHashMap<String, String>();
		values.forEach((key, value) -> {
			if (key != null && value != null) {
				copy.put(key, value);
			}
		});
		return copy;
	}

	private static Map<String, McpSecret> copySecrets(Map<String, McpSecret> secrets) {
		if (secrets == null) {
			return null;
		}
		var copy = new LinkedHashMap<String, McpSecret>();
		secrets.forEach((key, value) -> {
			if (key != null && value != null) {
				copy.put(key, value.copy());
			}
		});
		return copy;
	}
}
