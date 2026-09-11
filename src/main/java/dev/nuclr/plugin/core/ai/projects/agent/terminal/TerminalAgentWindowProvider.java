package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.nio.file.Path;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The window provider for a CLI agent run in a terminal.
 *
 * <p>One instance per entry in {@link AgentCli#BUILT_IN}, so Codex, Claude Code,
 * Pi, OpenCode and a plain shell are five window kinds sharing one
 * implementation rather than one kind with a mode switch. A sixth CLI is a line
 * in that list; a genuinely different kind of window - a log viewer, a task
 * board, a diff agent - is a different {@link AgentWindowProvider} and needs
 * nothing from this one.
 */
public final class TerminalAgentWindowProvider implements AgentWindowProvider {

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
	 * One provider per built-in CLI.
	 *
	 * @return the providers, in menu order
	 */
	public static List<AgentWindowProvider> builtIn() {
		return builtIn(AgentCli::resolveOnPath);
	}

	/** Built-in providers with an injectable executable resolver. */
	public static List<AgentWindowProvider> builtIn(Function<String, Optional<Path>> executableResolver) {
		return AgentCli.BUILT_IN.stream()
				.map(cli -> (AgentWindowProvider) new TerminalAgentWindowProvider(cli, executableResolver)).toList();
	}

	@Override
	public String kind() {
		return cli.kind();
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
