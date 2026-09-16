package dev.nuclr.plugin.core.ai.projects.connector;

import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/** The connector for each provider. */
public final class AgentConnectors {

	private static final AgentConnector CLAUDE_CODE = new ClaudeCodeConnector();
	private static final AgentConnector CODEX = new CodexConnector();
	private static final AgentConnector PI = new PiConnector();

	private AgentConnectors() {
	}

	/**
	 * The connector that drives a provider's CLI.
	 *
	 * @param provider the provider
	 * @return its connector
	 */
	public static AgentConnector of(AgentProvider provider) {
		return switch (provider) {
			case CLAUDE_CODE -> CLAUDE_CODE;
			case CODEX -> CODEX;
			case PI -> PI;
		};
	}
}
