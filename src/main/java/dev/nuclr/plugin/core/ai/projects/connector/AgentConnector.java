package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;

/**
 * Everything the plugin knows about driving one agent CLI, in one place: which
 * models and reasoning efforts it offers, which access modes it can honour, and
 * the flags that select each.
 *
 * <p>Deliberately the bare minimum for now - models, thinking and permissions.
 * The same connector will later start sessions, which is why the discovery and
 * the flags live together: the code that asks a CLI what it accepts is the code
 * that tells it what to do, so the two cannot drift apart.
 *
 * <p>Discovery uses each CLI's own structured protocol, not its human-readable
 * output: Codex's app-server, Pi's RPC mode, Claude Code's control protocol.
 */
public interface AgentConnector {

	/** How long discovery may take before giving up. */
	Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);

	/** The provider this drives. */
	AgentProvider provider();

	/** Every reasoning effort the CLI accepts, lowest first; a model may accept fewer. */
	List<String> efforts();

	/** How the effort reaches the CLI, for the editor's hint, e.g. {@code --effort}. */
	String effortSetting();

	/**
	 * The flags that select a model.
	 *
	 * @param model the model id, as the CLI takes it
	 * @return the arguments
	 */
	List<String> modelArguments(String model);

	/**
	 * The flags that select a reasoning effort.
	 *
	 * @param effort the effort, in the CLI's own vocabulary
	 * @return the arguments
	 */
	List<String> effortArguments(String effort);

	/**
	 * The flags that put the CLI in an access mode.
	 *
	 * @param mode the mode
	 * @return the arguments, empty for {@link AccessMode#CUSTOM}; absent when the CLI
	 *         cannot honour the mode
	 */
	Optional<List<String>> accessArguments(AccessMode mode);

	/**
	 * Why the CLI cannot honour a mode.
	 *
	 * @param mode the unsupported mode
	 * @return one sentence
	 */
	String unsupportedReason(AccessMode mode);

	/**
	 * Ask the installed CLI what it offers.
	 *
	 * @param executable the resolved executable to run
	 * @param timeout    how long to allow
	 * @return a live catalogue
	 * @throws IOException when the CLI cannot be asked or answers with something unusable
	 */
	ModelCatalog discover(String executable, Duration timeout) throws IOException;

	/**
	 * What is known without asking, for when the CLI cannot be.
	 *
	 * @param reason why it is being used, shown to the user
	 * @return a catalogue marked as not live
	 */
	ModelCatalog builtIn(String reason);

	/**
	 * Whether the CLI can honour an access mode.
	 *
	 * @param mode the mode
	 * @return whether {@link #accessArguments} has an answer
	 */
	default boolean supports(AccessMode mode) {
		return accessArguments(mode).isPresent();
	}

	/** Ask wherever the CLI can ask, so nothing happens unseen by default; otherwise full access. */
	default AccessMode defaultAccessMode() {
		return supports(AccessMode.ASK) ? AccessMode.ASK : AccessMode.FULL_ACCESS;
	}

	/**
	 * The flags for a model, effort and access mode together; blank values are left out.
	 *
	 * @param model  the model, or blank for the CLI's default
	 * @param effort the effort, or blank for the model's default
	 * @param mode   the access mode, or {@code null} for {@link #defaultAccessMode()}
	 * @return the arguments, in that order
	 * @throws IllegalArgumentException when the CLI cannot honour the access mode
	 */
	default List<String> launchArguments(String model, String effort, AccessMode mode) {
		var arguments = new ArrayList<String>();
		if (model != null && !model.isBlank()) {
			arguments.addAll(modelArguments(model.trim()));
		}
		if (effort != null && !effort.isBlank()) {
			arguments.addAll(effortArguments(effort.trim()));
		}
		var access = mode == null ? defaultAccessMode() : mode;
		arguments.addAll(accessArguments(access)
				.orElseThrow(() -> new IllegalArgumentException(unsupportedReason(access))));
		return List.copyOf(arguments);
	}
}
