package dev.nuclr.plugin.core.ai.projects.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent CLIs a profile can target. Hard-coded on purpose: each one gets a
 * connector of its own, so the list grows with the connectors, not with
 * configuration.
 *
 * <p>The same three settings mean the same thing everywhere, and each provider
 * says how it spells them:
 * <ul>
 *   <li><b>model</b> - stored exactly as the CLI takes it. For Pi, which fronts
 *       many model providers, that is {@code provider/model}.</li>
 *   <li><b>reasoning effort</b> - stored as the CLI's own value: Claude Code's
 *       {@code --effort}, Codex's {@code model_reasoning_effort}, Pi's
 *       {@code --thinking}. The vocabularies differ, so no value is translated;
 *       changing provider clears it.</li>
 *   <li>blank - let the CLI use its own default.</li>
 * </ul>
 */
public enum AgentProvider {

	/** Anthropic's Claude Code. */
	CLAUDE_CODE("claude-code", "Claude Code", "claude", "--effort",
			List.of("low", "medium", "high", "xhigh", "max")),

	/** OpenAI's Codex CLI. */
	CODEX("codex", "Codex", "codex", "-c model_reasoning_effort",
			List.of("low", "medium", "high", "xhigh", "max", "ultra")),

	/** Pi, the multi-provider coding agent. */
	PI("pi", "Pi", "pi", "--thinking",
			List.of("off", "minimal", "low", "medium", "high", "xhigh", "max"));

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
	private final String effortSetting;
	private final List<String> efforts;

	AgentProvider(String id, String displayName, String executable, String effortSetting, List<String> efforts) {
		this.id = id;
		this.displayName = displayName;
		this.executable = executable;
		this.effortSetting = effortSetting;
		this.efforts = efforts;
	}

	/** The value stored in a profile; matches the terminal window kind's suffix. */
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

	/** How the CLI is told the reasoning effort, for the editor's hint. */
	public String effortSetting() {
		return effortSetting;
	}

	/** Every reasoning effort the CLI accepts, lowest first; a model may allow fewer. */
	public List<String> efforts() {
		return efforts;
	}

	@Override
	public String toString() {
		return displayName;
	}

	/**
	 * The flags that put this CLI in an access mode.
	 *
	 * <ul>
	 *   <li>Claude Code - {@code --permission-mode}: plan, manual, auto, bypassPermissions.</li>
	 *   <li>Codex - a sandbox ({@code -s}) and an approval policy ({@code -a}), or the
	 *       automatic reviewer ({@code --approve-for-me}).</li>
	 *   <li>Pi - has neither approvals nor a sandbox. It can be made read-only by
	 *       allowing only its reading tools; otherwise it has full access.</li>
	 * </ul>
	 *
	 * @param mode the mode
	 * @return the arguments, empty for {@link AccessMode#CUSTOM}; absent when this
	 *         provider cannot honour the mode
	 */
	public Optional<List<String>> accessArguments(AccessMode mode) {
		if (mode == AccessMode.CUSTOM) {
			return Optional.of(List.of());
		}
		return Optional.ofNullable(switch (this) {
			case CLAUDE_CODE -> switch (mode) {
				case READ_ONLY -> List.of("--permission-mode", "plan");
				case ASK -> List.of("--permission-mode", "manual");
				case AUTO -> List.of("--permission-mode", "auto");
				case FULL_ACCESS -> List.of("--permission-mode", "bypassPermissions");
				case CUSTOM -> List.<String>of();
			};
			case CODEX -> switch (mode) {
				case READ_ONLY -> List.of("--sandbox", "read-only");
				case ASK -> List.of("--sandbox", "workspace-write", "--ask-for-approval", "on-request");
				case AUTO -> List.of("--sandbox", "workspace-write", "--approve-for-me");
				case FULL_ACCESS -> List.of("--dangerously-bypass-approvals-and-sandbox");
				case CUSTOM -> List.<String>of();
			};
			case PI -> switch (mode) {
				case READ_ONLY -> List.of("--tools", "read,grep,find,ls");
				case FULL_ACCESS -> List.<String>of();
				case ASK, AUTO -> null;
				case CUSTOM -> List.<String>of();
			};
		});
	}

	/**
	 * Whether this CLI can honour an access mode.
	 *
	 * @param mode the mode
	 * @return whether {@link #accessArguments(AccessMode)} has an answer
	 */
	public boolean supports(AccessMode mode) {
		return accessArguments(mode).isPresent();
	}

	/**
	 * The mode a profile gets when it names none: Ask wherever the CLI can ask, so
	 * nothing happens unseen by default. Pi cannot ask, and has full access.
	 */
	public AccessMode defaultAccessMode() {
		return supports(AccessMode.ASK) ? AccessMode.ASK : AccessMode.FULL_ACCESS;
	}

	/**
	 * Why this CLI cannot honour a mode, for the editor.
	 *
	 * @param mode the unsupported mode
	 * @return one sentence
	 */
	public String unsupportedReason(AccessMode mode) {
		return this == PI
				? "Pi has no approval prompts or sandbox, so it cannot " + (mode == AccessMode.ASK ? "ask first" : "review actions")
						+ ". Choose Read-only, or Full access in an isolated environment."
				: displayName + " does not support " + mode.label() + ".";
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
