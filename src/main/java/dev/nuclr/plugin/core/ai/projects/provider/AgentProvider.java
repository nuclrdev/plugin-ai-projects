package dev.nuclr.plugin.core.ai.projects.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.connector.AgentConnectors;

/**
 * The agent CLIs a profile can target. Hard-coded on purpose: each one has a
 * connector of its own, so the list grows with the connectors, not with
 * configuration.
 *
 * <p>This is only the identity - id, name and command. What a CLI accepts and
 * how it is told belongs to its {@link AgentConnector}; the methods here that
 * answer those questions simply ask it.
 *
 * <p>The same settings mean the same thing for every provider, each spelled the
 * provider's own way:
 * <ul>
 *   <li><b>model</b> - stored exactly as the CLI takes it. For Pi, which fronts
 *       many model providers, that is {@code provider/model}.</li>
 *   <li><b>reasoning effort</b> - stored as the CLI's own value. The vocabularies
 *       differ, so no value is translated; changing provider clears it.</li>
 *   <li><b>access mode</b> - one of {@link AccessMode}, mapped by the connector.</li>
 *   <li>blank - let the CLI use its own default.</li>
 * </ul>
 */
public enum AgentProvider {

	/** Anthropic's Claude Code. */
	CLAUDE_CODE("claude-code", "Claude Code", "claude"),

	/** OpenAI's Codex CLI. */
	CODEX("codex", "Codex", "codex"),

	/** Pi, the multi-provider coding agent. */
	PI("pi", "Pi", "pi");

	private static final Map<String, String> EFFORT_LABELS = Map.of(
			"none", "None",
			"off", "Off",
			"minimal", "Minimal",
			"low", "Low",
			"medium", "Medium",
			"high", "High",
			"xhigh", "Extra high",
			"max", "Max",
			"ultra", "Ultra");

	private final String id;
	private final String displayName;
	private final String executable;

	AgentProvider(String id, String displayName, String executable) {
		this.id = id;
		this.displayName = displayName;
		this.executable = executable;
	}

	/** The value stored in a profile; the same id an {@code AgentCli} and a window kind use. */
	public String id() {
		return id;
	}

	/** Name shown to people. */
	public String displayName() {
		return displayName;
	}

	/** The command that starts it when a profile names no executable. */
	public String defaultExecutable() {
		return executable;
	}

	/** The connector that knows how to drive this CLI. */
	public AgentConnector connector() {
		return AgentConnectors.of(this);
	}

	/** How the CLI is told the reasoning effort, for the editor's hint. */
	public String effortSetting() {
		return connector().effortSetting();
	}

	/** Every reasoning effort the CLI accepts, lowest first; a model may allow fewer. */
	public List<String> efforts() {
		return connector().efforts();
	}

	/**
	 * The flags that put this CLI in an access mode.
	 *
	 * @param mode the mode
	 * @return the arguments, or empty when the CLI cannot honour the mode
	 */
	public Optional<List<String>> accessArguments(AccessMode mode) {
		return connector().accessArguments(mode);
	}

	/**
	 * Whether this CLI can honour an access mode.
	 *
	 * @param mode the mode
	 * @return whether it can
	 */
	public boolean supports(AccessMode mode) {
		return connector().supports(mode);
	}

	/** The mode a profile gets when it names none. */
	public AccessMode defaultAccessMode() {
		return connector().defaultAccessMode();
	}

	/**
	 * Why this CLI cannot honour a mode, for the editor.
	 *
	 * @param mode the unsupported mode
	 * @return one sentence
	 */
	public String unsupportedReason(AccessMode mode) {
		return connector().unsupportedReason(mode);
	}

	@Override
	public String toString() {
		return displayName;
	}

	/**
	 * A provider by its stored id.
	 *
	 * @param id the id, possibly {@code null}
	 * @return the provider, or empty when blank or unknown
	 */
	public static Optional<AgentProvider> byId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		for (var provider : values()) {
			if (provider.id.equalsIgnoreCase(id.trim())) {
				return Optional.of(provider);
			}
		}
		return Optional.empty();
	}

	/**
	 * A reasoning effort's display name.
	 *
	 * @param effort the stored value
	 * @return a readable label; the value itself when unknown
	 */
	public static String effortLabel(String effort) {
		return effort == null ? "" : EFFORT_LABELS.getOrDefault(effort, effort);
	}
}
