package dev.nuclr.plugin.core.ai.projects.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * Finds out which models each provider offers, by asking the installed CLI.
 *
 * <ul>
 *   <li><b>Codex</b> - {@code codex debug models} prints its catalog as JSON,
 *       with each model's reasoning efforts and default.</li>
 *   <li><b>Pi</b> - {@code pi --list-models} prints a table of the models from
 *       every provider it is signed in to, and whether each can think.</li>
 *   <li><b>Claude Code</b> - has no listing command, so its aliases and current
 *       models are built in.</li>
 * </ul>
 *
 * <p>Asking is slow (a CLI start-up, sometimes a network call), so answers are
 * cached per provider and executable until {@link #refresh} is asked for, and
 * every request runs off the event thread. When a CLI cannot be run the
 * built-in list is returned with a note saying why, so the editor always has
 * something to offer and a model id can still be typed by hand.
 */
@Slf4j
public final class ModelCatalogs {

	/** Longest a CLI is given to answer. */
	static final long TIMEOUT_SECONDS = 30;

	/**
	 * Runs a command line and returns its standard output.
	 *
	 * <p>Throws when the command cannot be started, times out or exits non-zero.
	 */
	@FunctionalInterface
	public interface CommandRunner {

		/**
		 * Run a command.
		 *
		 * @param command the command and its arguments
		 * @return what it printed on standard output
		 * @throws IOException when it did not run successfully
		 */
		String run(List<String> command) throws IOException;
	}

	private static final ModelCatalogs SHARED = new ModelCatalogs(ModelCatalogs::runProcess,
			command -> AgentCli.resolveOnPath(command).map(Object::toString), Executors.newVirtualThreadPerTaskExecutor());

	private final CommandRunner runner;
	private final Function<String, java.util.Optional<String>> resolver;
	private final Executor executor;
	private final Map<String, CompletableFuture<ModelCatalog>> cache = new ConcurrentHashMap<>();

	/**
	 * A catalogue source over a given way of running commands.
	 *
	 * @param runner   runs the CLIs
	 * @param resolver finds a command on this machine, empty when it is not installed
	 * @param executor where the asking happens
	 */
	public ModelCatalogs(CommandRunner runner, Function<String, java.util.Optional<String>> resolver,
			Executor executor) {
		this.runner = runner;
		this.resolver = resolver;
		this.executor = executor;
	}

	/** The one the editor uses, so every open form shares one answer per CLI. */
	public static ModelCatalogs shared() {
		return SHARED;
	}

	/**
	 * The models a provider offers, from the cache when it has been asked before.
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

	private CompletableFuture<ModelCatalog> load(AgentProvider provider, String executable) {
		var command = executable == null || executable.isBlank() ? provider.defaultExecutable() : executable.trim();
		return CompletableFuture.supplyAsync(() -> ask(provider, command), executor);
	}

	private ModelCatalog ask(AgentProvider provider, String command) {
		if (provider == AgentProvider.CLAUDE_CODE) {
			return builtIn(provider, "Claude Code has no command that lists models; these are its aliases and "
					+ "current models. Any model id can be typed.");
		}
		var resolved = resolver.apply(command).orElse(null);
		if (resolved == null) {
			return builtIn(provider, "\"" + command + "\" was not found on this machine; type a model id.");
		}
		try {
			return switch (provider) {
				case CODEX -> live(provider, parseCodex(runner.run(List.of(resolved, "debug", "models"))),
						"Models reported by codex debug models.");
				case PI -> live(provider, parsePi(runner.run(List.of(resolved, "--list-models"))),
						"Models from the providers Pi is signed in to (pi --list-models).");
				case CLAUDE_CODE -> throw new IllegalStateException("unreachable");
			};
		} catch (IOException | RuntimeException e) {
			log.info("Could not list {} models with {}: {}", provider.displayName(), command, e.getMessage());
			return builtIn(provider, "Could not ask " + provider.displayName() + " for its models ("
					+ firstLine(e.getMessage()) + "); type a model id.");
		}
	}

	private ModelCatalog live(AgentProvider provider, List<ModelCatalog.Model> models, String note) {
		if (models.isEmpty()) {
			return builtIn(provider, provider.displayName() + " reported no models; type a model id.");
		}
		return new ModelCatalog(provider, models, true, note + " " + models.size() + " available.");
	}

	/**
	 * The list shipped with the plugin, used when the CLI cannot be asked.
	 *
	 * @param provider the provider
	 * @param note     why it is being used
	 * @return the catalogue
	 */
	public static ModelCatalog builtIn(AgentProvider provider, String note) {
		var models = switch (provider) {
			case CLAUDE_CODE -> List.of(
					model("fable", "Fable (latest)"),
					model("opus", "Opus (latest)"),
					model("sonnet", "Sonnet (latest)"),
					model("haiku", "Haiku (latest)"),
					model("claude-fable-5-1", "Claude Fable 5.1"),
					model("claude-opus-5", "Claude Opus 5"),
					model("claude-sonnet-5", "Claude Sonnet 5"),
					model("claude-haiku-4-5", "Claude Haiku 4.5"));
			case CODEX, PI -> List.<ModelCatalog.Model>of();
		};
		return new ModelCatalog(provider, models, false, note);
	}

	private static ModelCatalog.Model model(String id, String label) {
		return new ModelCatalog.Model(id, label, List.of(), null);
	}

	/**
	 * Read {@code codex debug models}: a {@code models} array whose entries carry a
	 * {@code slug}, a {@code display_name}, a {@code visibility} ({@code list} or
	 * {@code hide}) and their reasoning levels. Hidden models are internal and left out.
	 *
	 * @param json the command's output
	 * @return the listed models
	 * @throws IOException when the output is not that shape
	 */
	public static List<ModelCatalog.Model> parseCodex(String json) throws IOException {
		var root = Json.fromJson(json, JsonNode.class);
		var entries = root == null ? null : root.has("models") ? root.get("models") : root;
		if (entries == null || !entries.isArray()) {
			throw new IOException("unexpected output from codex debug models");
		}
		var models = new ArrayList<ModelCatalog.Model>();
		for (var entry : entries) {
			var slug = text(entry, "slug");
			if (slug == null || "hide".equalsIgnoreCase(text(entry, "visibility"))) {
				continue;
			}
			var efforts = new ArrayList<String>();
			var levels = entry.get("supported_reasoning_levels");
			if (levels != null && levels.isArray()) {
				for (var level : levels) {
					var effort = level.isString() ? level.asString() : text(level, "effort");
					if (effort != null && !effort.isBlank()) {
						efforts.add(effort);
					}
				}
			}
			var label = text(entry, "display_name");
			models.add(new ModelCatalog.Model(slug, label == null ? slug : label, efforts,
					text(entry, "default_reasoning_level")));
		}
		return models;
	}

	/**
	 * Read {@code pi --list-models}: a header row, then one row per model with
	 * whitespace-separated columns {@code provider model context max-out thinking images}.
	 *
	 * @param table the command's output
	 * @return the models, as {@code provider/model}
	 * @throws IOException when there is no header to recognise
	 */
	public static List<ModelCatalog.Model> parsePi(String table) throws IOException {
		var lines = table.strip().split("\\R");
		var header = -1;
		for (var index = 0; index < lines.length; index++) {
			if (lines[index].trim().toLowerCase(java.util.Locale.ROOT).startsWith("provider")) {
				header = index;
				break;
			}
		}
		if (header < 0) {
			throw new IOException("unexpected output from pi --list-models");
		}
		var columns = List.of(lines[header].trim().toLowerCase(java.util.Locale.ROOT).split("\\s+"));
		var thinkingColumn = columns.indexOf("thinking");
		var models = new ArrayList<ModelCatalog.Model>();
		for (var index = header + 1; index < lines.length; index++) {
			var cells = lines[index].trim().split("\\s+");
			if (cells.length < 2 || cells[0].isBlank()) {
				continue;
			}
			var id = cells[0] + "/" + cells[1];
			var thinks = thinkingColumn < 0 || thinkingColumn >= cells.length || "yes".equalsIgnoreCase(cells[thinkingColumn]);
			models.add(new ModelCatalog.Model(id, id, thinks ? List.of() : List.of("off"), null));
		}
		return models;
	}

	private static String text(JsonNode node, String field) {
		var value = node == null ? null : node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	private static String key(AgentProvider provider, String executable) {
		return provider.id() + "|" + (executable == null ? "" : executable.trim());
	}

	private static String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "no details";
		}
		var line = message.strip().lines().findFirst().orElse("");
		return line.length() > 120 ? line.substring(0, 117) + "..." : line;
	}

	/** Run a command, capturing standard output and giving up after {@link #TIMEOUT_SECONDS}. */
	static String runProcess(List<String> command) throws IOException {
		var process = new ProcessBuilder(command).redirectErrorStream(false).start();
		process.getOutputStream().close();
		var output = new ByteArrayOutputStream();
		var errors = new ByteArrayOutputStream();
		var readOut = Thread.ofVirtual().start(() -> copy(process.getInputStream(), output));
		var readErr = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), errors));
		try {
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				throw new IOException("no answer within " + TIMEOUT_SECONDS + " seconds");
			}
			readOut.join();
			readErr.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted", e);
		}
		if (process.exitValue() != 0) {
			var detail = errors.toString(StandardCharsets.UTF_8).strip();
			throw new IOException("exit code " + process.exitValue() + (detail.isEmpty() ? "" : ": " + detail));
		}
		return output.toString(StandardCharsets.UTF_8);
	}

	private static void copy(java.io.InputStream in, ByteArrayOutputStream out) {
		try (in) {
			in.transferTo(out);
		} catch (IOException e) {
			// The process went away; what was read is what there is.
		}
	}

	/**
	 * A catalogue source that answers with a fixed function, for tests.
	 *
	 * @param answer maps a command line to its output
	 * @return the source, running on the calling thread
	 */
	public static ModelCatalogs answering(Function<List<String>, String> answer) {
		return new ModelCatalogs(answer::apply, java.util.Optional::of, Runnable::run);
	}
}
