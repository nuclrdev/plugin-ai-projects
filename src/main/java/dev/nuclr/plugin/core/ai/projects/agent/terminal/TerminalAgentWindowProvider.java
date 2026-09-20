package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.nio.file.Path;

import dev.nuclr.plugin.core.ai.projects.agent.AgentCli;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The window provider for a command run in a terminal.
 *
 * <p>Only the plain shell is registered: a terminal is for reaching the disk - running a
 * build, looking at a file, fixing a checkout - and an agent is a conversation, drawn by
 * this plugin from what the CLI reports rather than scraped off a screen it painted. See
 * {@link dev.nuclr.plugin.core.ai.projects.agent.chat.ChatAgentWindowProvider}.
 *
 * <p>The class itself still runs any {@link AgentCli}, since nothing about it is particular
 * to the shell; what changed is which of them {@link #builtIn(Function)} hands out.
 */
public final class TerminalAgentWindowProvider implements AgentWindowProvider {

	/** Window-kind prefix of every terminal, followed by the {@link AgentCli#id()} it runs. */
	public static final String KIND_PREFIX = "terminal.";

	/** The one terminal an installation offers: a plain shell in the agent's folder. */
	public static final String SHELL_KIND = KIND_PREFIX + "shell";

	private final AgentCli cli;
	private final Function<String, Optional<Path>> executableResolver;

	/**
	 * Create the provider for one CLI.
	 *
	 * @param cli the CLI this provider runs
	 */
	public TerminalAgentWindowProvider(AgentCli cli) {
		this(cli, AgentCli::resolveOnPath);
	}

	/** Constructor with an injectable executable resolver for deterministic hosts and tests. */
	public TerminalAgentWindowProvider(AgentCli cli, Function<String, Optional<Path>> executableResolver) {
		this.cli = cli;
		this.executableResolver = executableResolver == null ? AgentCli::resolveOnPath : executableResolver;
	}

	/**
	 * The terminal windows this installation offers: the plain shell, and nothing else.
	 *
	 * @return the providers, in menu order
	 */
	public static List<AgentWindowProvider> builtIn() {
		return builtIn(AgentCli::resolveOnPath);
	}

	/** Built-in providers with an injectable executable resolver. */
	public static List<AgentWindowProvider> builtIn(Function<String, Optional<Path>> executableResolver) {
		return AgentCli.BUILT_IN.stream().filter(AgentCli::isShell)
				.map(cli -> (AgentWindowProvider) new TerminalAgentWindowProvider(cli, executableResolver)).toList();
	}

	/**
	 * The kind of the terminal that runs a CLI.
	 *
	 * @param cli the CLI
	 * @return the window kind
	 */
	public static String kindFor(AgentCli cli) {
		return KIND_PREFIX + cli.id();
	}

	@Override
	public String kind() {
		return kindFor(cli);
	}

	@Override
	public String displayName() {
		return cli.displayName();
	}

	@Override
	public String description() {
		return cli.description();
	}

	@Override
	public boolean isAvailable() {
		return TerminalStack.isAvailable();
	}

	@Override
	public String unavailableReason() {
		return TerminalStack.unavailableReason();
	}

	/**
	 * Whether the CLI itself is installed on this machine.
	 *
	 * <p>Kept separate from {@link #isAvailable()}: a missing CLI is the user's to
	 * fix and the agent can still be defined now and started later, whereas a
	 * missing terminal stack means no window at all. The menu greys out only the
	 * second and annotates the first.
	 *
	 * @return whether the default executable resolves on PATH
	 */
	public boolean isCliInstalled() {
		var executable = cli.defaultHarness().getExecutable();
		return executable != null && AgentCli.isInstalled(executable);
	}

	/** The CLI this provider runs. */
	public AgentCli cli() {
		return cli;
	}

	@Override
	public HarnessSpec defaultHarness() {
		return cli.defaultHarness();
	}

	@Override
	public AgentWindow createWindow(AgentWindowContext context) {
		return new TerminalAgentWindow(context, cli, executableResolver);
	}
}
