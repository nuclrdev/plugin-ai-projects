package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * The window provider for an agent drawn as a conversation rather than a terminal.
 *
 * <p>One per {@link ChatBackend}: {@code chat.claude-code}, {@code chat.codex},
 * {@code chat.pi}, and {@code chat.opencode} for OpenCode over the Agent Client
 * Protocol. The window is the same for all of them; the backend is what differs.
 */
public final class ChatAgentWindowProvider implements AgentWindowProvider {

	/** Window-kind prefix shared by every conversation-backed agent. */
	public static final String KIND_PREFIX = "chat.";

	private final ChatBackend backend;
	private final Function<String, Optional<Path>> executableResolver;

	private ChatAgentWindowProvider(ChatBackend backend, Function<String, Optional<Path>> executableResolver) {
		this.backend = backend;
		this.executableResolver = executableResolver == null ? AgentCli::resolveOnPath : executableResolver;
	}

	/**
	 * One provider per built-in backend.
	 *
	 * @param executableResolver finds the CLI; injectable for tests
	 * @return the providers, in menu order
	 */
	public static List<AgentWindowProvider> builtIn(Function<String, Optional<Path>> executableResolver) {
		return ChatBackend.BUILT_IN.stream()
				.map(backend -> (AgentWindowProvider) new ChatAgentWindowProvider(backend, executableResolver)).toList();
	}

	/**
	 * The window kind of a provider's conversation window.
	 *
	 * @param provider the CLI
	 * @return the kind
	 */
	public static String kindFor(AgentProvider provider) {
		return KIND_PREFIX + provider.id();
	}

	@Override
	public String kind() {
		return KIND_PREFIX + backend.id();
	}

	@Override
	public String displayName() {
		return backend.displayName();
	}

	@Override
	public String description() {
		return backend.description();
	}

	@Override
	public HarnessSpec defaultHarness() {
		return AgentCli.byKind(AgentCli.KIND_PREFIX + backend.id()).map(AgentCli::defaultHarness)
				.orElseGet(HarnessSpec::new);
	}

	@Override
	public AgentWindow createWindow(AgentWindowContext context) {
		return new ChatAgentWindow(context, backend, executableResolver);
	}
}
