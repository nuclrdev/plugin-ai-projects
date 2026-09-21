package dev.nuclr.plugin.core.ai.projects.provider;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.agent.AgentCli;
import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import lombok.extern.slf4j.Slf4j;

/**
 * What each provider offers, asked of its connector and remembered.
 *
 * <p>Asking starts the CLI, so answers are cached per provider and executable
 * until {@link #refresh} is called, and every request runs off the event
 * thread. When a CLI is not installed or cannot be asked, the connector's
 * built-in answer is used with a note saying why, so the editor always has
 * something to offer and a model id can still be typed by hand.
 */
@Slf4j
public final class ModelCatalogs {

	/** Asks a provider's CLI what it offers. */
	@FunctionalInterface
	public interface Discovery {

		/**
		 * Ask.
		 *
		 * @param provider   the provider
		 * @param executable the resolved executable
		 * @return the live catalogue
		 * @throws IOException when the CLI cannot be asked
		 */
		ModelCatalog discover(AgentProvider provider, String executable) throws IOException;
	}

	private static final ModelCatalogs SHARED = new ModelCatalogs(
			(provider, executable) -> provider.connector().discover(executable, AgentConnector.DISCOVERY_TIMEOUT),
			(provider, executable) -> provider.connector().configuredMcpServers(executable,
					AgentConnector.DISCOVERY_TIMEOUT),
			command -> AgentCli.resolveOnPath(command).map(Object::toString),
			Executors.newVirtualThreadPerTaskExecutor());

	/** Asks a provider's CLI which MCP servers the user configured. */
	@FunctionalInterface
	public interface McpDiscovery {

		/**
		 * Ask.
		 *
		 * @param provider   the provider
		 * @param executable the resolved executable
		 * @return the server names
		 * @throws IOException when the CLI cannot be asked
		 */
		java.util.List<String> configuredServers(AgentProvider provider, String executable) throws IOException;
	}

	private final Discovery discovery;
	private final McpDiscovery mcpDiscovery;
	private final Map<String, CompletableFuture<java.util.List<String>>> mcpCache = new ConcurrentHashMap<>();
	private final Function<String, Optional<String>> resolver;
	private final Executor executor;
	private final Map<String, CompletableFuture<ModelCatalog>> cache = new ConcurrentHashMap<>();

	/**
	 * A catalogue source.
	 *
	 * @param discovery how a CLI is asked
	 * @param resolver  finds a command on this machine, empty when it is not installed
	 * @param executor  where the asking happens
	 */
	public ModelCatalogs(Discovery discovery, Function<String, Optional<String>> resolver, Executor executor) {
		this(discovery, (provider, executable) -> java.util.List.of(), resolver, executor);
	}

	/**
	 * A catalogue source that also asks which MCP servers are configured.
	 *
	 * @param discovery    how a CLI is asked for its models
	 * @param mcpDiscovery how a CLI is asked for its configured MCP servers
	 * @param resolver     finds a command on this machine, empty when it is not installed
	 * @param executor     where the asking happens
	 */
	public ModelCatalogs(Discovery discovery, McpDiscovery mcpDiscovery, Function<String, Optional<String>> resolver,
			Executor executor) {
		this.discovery = discovery;
		this.mcpDiscovery = mcpDiscovery;
		this.resolver = resolver;
		this.executor = executor;
	}

	/** The one the editor uses, so every open form shares one answer per CLI. */
	public static ModelCatalogs shared() {
		return SHARED;
	}

	/**
	 * A source that answers without starting anything, on the calling thread, for tests.
	 *
	 * @param discovery the answers
	 * @return the source
	 */
	public static ModelCatalogs answering(Discovery discovery) {
		return new ModelCatalogs(discovery, Optional::of, Runnable::run);
	}

	/**
	 * What a provider offers, from the cache when it has been asked before.
	 *
	 * @param provider   the provider
	 * @param executable the executable to ask, or blank for the provider's default
	 * @return the catalogue, completing off the calling thread; never completes exceptionally
	 */
	public CompletableFuture<ModelCatalog> catalog(AgentProvider provider, String executable) {
		return cache.computeIfAbsent(key(provider, executable), ignored -> load(provider, executable));
	}

	/**
	 * Ask again, forgetting any cached answer.
	 *
	 * @param provider   the provider
	 * @param executable the executable to ask, or blank for the provider's default
	 * @return the fresh catalogue
	 */
	public CompletableFuture<ModelCatalog> refresh(AgentProvider provider, String executable) {
		cache.remove(key(provider, executable));
		return catalog(provider, executable);
	}

	/**
	 * The MCP servers configured in a provider's CLI, from the cache when asked
	 * before. Empty when the CLI cannot say or cannot be run.
	 *
	 * @param provider   the provider
	 * @param executable the executable to ask, or blank for the provider's default
	 * @return the names, completing off the calling thread; never completes exceptionally
	 */
	public CompletableFuture<java.util.List<String>> configuredMcpServers(AgentProvider provider, String executable) {
		return mcpCache.computeIfAbsent(key(provider, executable), ignored -> {
			var command = executable == null || executable.isBlank() ? provider.defaultExecutable() : executable.trim();
			return CompletableFuture.supplyAsync(() -> {
				try {
					var resolved = resolver.apply(command).orElse(null);
					if (resolved == null) {
						return java.util.List.<String>of();
					}
					return mcpDiscovery.configuredServers(provider, resolved);
				} catch (IOException | RuntimeException e) {
					log.info("Could not list {} MCP servers: {}", provider.displayName(), e.getMessage());
					return java.util.List.<String>of();
				}
			}, executor);
		});
	}

	private CompletableFuture<ModelCatalog> load(AgentProvider provider, String executable) {
		var command = executable == null || executable.isBlank() ? provider.defaultExecutable() : executable.trim();
		return CompletableFuture.supplyAsync(() -> ask(provider, command), executor);
	}

	private ModelCatalog ask(AgentProvider provider, String command) {
		var connector = provider.connector();
		final String resolved;
		try {
			resolved = resolver.apply(command).orElse(null);
		} catch (RuntimeException e) {
			// A hand-edited or imported path the file system cannot even parse.
			return connector.builtIn("\"" + command + "\" is not a valid path (" + firstLine(e.getMessage())
					+ "); type a model id.");
		}
		if (resolved == null) {
			return connector.builtIn("\"" + command + "\" was not found on this machine; type a model id.");
		}
		try {
			return discovery.discover(provider, resolved);
		} catch (IOException | RuntimeException e) {
			log.info("Could not ask {} for its models with {}: {}", provider.displayName(), command, e.getMessage());
			return connector.builtIn("Could not ask " + provider.displayName() + " for its models ("
					+ firstLine(e.getMessage()) + "); type a model id.");
		}
	}

	private static String key(AgentProvider provider, String executable) {
		return provider.id() + "|" + (executable == null ? "" : executable.trim());
	}

	private static String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "no details";
		}
		var line = message.strip().lines().findFirst().orElse("");
		return line.length() > 160 ? line.substring(0, 157) + "..." : line;
	}
}
