package dev.nuclr.plugin.core.ai.projects.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.agent.chat.ChatAgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.TerminalAgentWindowProvider;

/**
 * The kinds of agent window available in this installation.
 *
 * <p>Ships with a conversation per agent CLI and one terminal - the plain shell, for
 * reaching the disk - and takes registrations for anything else, so adding a log viewer
 * or a task board later is a registration rather than a change to the desktop. Lookups
 * are by the stable kind string recorded in {@code project.json}, and a kind that
 * resolves to nothing yields a {@link MissingProviderWindow} rather than an error - a
 * project must open even when part of it cannot.
 *
 * <p>Agent CLIs had a terminal kind of their own once. They are drawn as conversations
 * now, and a project that still names {@code terminal.codex} is carried over when it is
 * opened; see {@code ProjectMigration.toConversations}.
 */
public final class AgentWindowRegistry {

	private final Map<String, AgentWindowProvider> providers = new LinkedHashMap<>();
	private final Function<String, Optional<Path>> executableResolver;

	/** Create a registry holding the built-in providers. */
	public AgentWindowRegistry() {
		this(AgentCli::resolveOnPath);
	}

	/** Create a registry with an injectable executable resolver. */
	public AgentWindowRegistry(Function<String, Optional<Path>> executableResolver) {
		this.executableResolver = executableResolver == null ? AgentCli::resolveOnPath : executableResolver;
		ChatAgentWindowProvider.builtIn(this.executableResolver).forEach(this::register);
		TerminalAgentWindowProvider.builtIn(this.executableResolver).forEach(this::register);
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
	 * The kind new agents get when nothing else is specified: a conversation with the
	 * first installed agent CLI, or the plain shell when none of them are installed.
	 *
	 * <p>A conversation is what an agent gets because it is told what a terminal has to
	 * infer from the look of the screen - when a turn has ended, what a permission prompt
	 * is asking and what answers it takes - and because it needs nothing of the host's
	 * terminal libraries. The shell is the one terminal left, and it is a way to reach the
	 * disk rather than a way to talk to an agent.
	 *
	 * <p>Registration order is what "first" means, so the default is the conversation at
	 * the top of the same menu the user is about to see rather than one chosen by a
	 * separate order of its own.
	 *
	 * @return a window kind, never {@code null}
	 */
	public String defaultKind() {
		for (var provider : providers.values()) {
			if (!provider.kind().startsWith(ChatAgentWindowProvider.KIND_PREFIX) || !provider.isAvailable()) {
				continue;
			}
			var executable = provider.defaultHarness().getExecutable();
			if (executable != null && executableResolver.apply(executable).isPresent()) {
				return provider.kind();
			}
		}
		return TerminalAgentWindowProvider.SHELL_KIND;
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
