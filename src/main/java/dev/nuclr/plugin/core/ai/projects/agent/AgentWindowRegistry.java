package dev.nuclr.plugin.core.ai.projects.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.TerminalAgentWindowProvider;

/**
 * The kinds of agent window available in this installation.
 *
 * <p>Ships with the terminal providers and takes registrations for anything
 * else, so adding a log viewer or a task board later is a registration rather
 * than a change to the desktop. Lookups are by the stable kind string recorded
 * in {@code project.json}, and a kind that resolves to nothing yields a
 * {@link MissingProviderWindow} rather than an error - a project must open even
 * when part of it cannot.
 */
public final class AgentWindowRegistry {

	private final Map<String, AgentWindowProvider> providers = new LinkedHashMap<>();

	/** Create a registry holding the built-in providers. */
	public AgentWindowRegistry() {
		this(AgentCli::resolveOnPath);
	}

	/** Create a registry with an injectable executable resolver. */
	public AgentWindowRegistry(Function<String, Optional<Path>> executableResolver) {
		TerminalAgentWindowProvider.builtIn(executableResolver).forEach(this::register);
	}

	/**
	 * Add a provider, replacing any with the same kind.
	 *
	 * @param provider the provider to add
	 */
	public void register(AgentWindowProvider provider) {
		if (provider != null && provider.kind() != null) {
			providers.put(provider.kind(), provider);
		}
	}

	/**
	 * Every registered provider, in registration order.
	 *
	 * @return the providers
	 */
	public List<AgentWindowProvider> providers() {
		return List.copyOf(providers.values());
	}

	/**
	 * Find a provider by kind.
	 *
	 * @param kind the window kind
	 * @return the provider, or empty
	 */
	public Optional<AgentWindowProvider> find(String kind) {
		return Optional.ofNullable(kind).map(providers::get);
	}

	/**
	 * The kind new agents get when nothing else is specified: the first installed
	 * agent CLI, or the plain shell when none of them are installed.
	 *
	 * @return a window kind, never {@code null}
	 */
	public String defaultKind() {
		for (var provider : providers.values()) {
			if (provider instanceof TerminalAgentWindowProvider terminal
					&& !terminal.cli().isShell()
					&& terminal.isCliInstalled()) {
				return terminal.kind();
			}
		}
		return AgentCli.KIND_PREFIX + "shell";
	}

	/**
	 * Build the window for an agent.
	 *
	 * @param context the agent and its resolved configuration
	 * @return the window; a {@link MissingProviderWindow} when the kind is unknown
	 */
	public AgentWindow createWindow(AgentWindowContext context) {
		var kind = context.agent().getWindowKind();
		var provider = find(kind).orElse(null);
		if (provider == null) {
			return new MissingProviderWindow(context.agent().displayName(), String.valueOf(kind),
					"No window provider is installed for this kind.");
		}
		try {
			if (!provider.isAvailable()) {
				return new MissingProviderWindow(context.agent().displayName(), String.valueOf(kind),
						provider.unavailableReason());
			}
			return provider.createWindow(context);
		} catch (LinkageError | RuntimeException e) {
			return new MissingProviderWindow(context.agent().displayName(), String.valueOf(kind),
					"The provider could not be loaded: "
							+ (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
		}
	}
}
