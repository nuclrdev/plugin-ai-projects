package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector.SecretBinding;
import dev.nuclr.plugin.core.ai.projects.profile.ExtraFolders;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * How to start an agent from a profile, in a form any kind of session can use: the
 * command, the environment, a configuration file to write first, the secrets to
 * put in the environment, and the briefing built from the profile's context.
 *
 * <p>Building a plan reads the files the profile links - a bounded amount of each,
 * but from disks that may be slow or remote - so it belongs off the event thread.
 * It never touches the credential store. Secrets are named here and read by
 * {@link #resolveSecrets} when the process is about to start, off the event thread,
 * so a plan can be built - and shown - without unlocking anything.
 *
 * @param provider          the CLI
 * @param executable        the command, as the profile names it or the provider's own
 * @param arguments         everything after the executable
 * @param environment       variables to set, from the profile's Environment tab
 * @param secrets           variables whose values are secrets, resolved at launch
 * @param configFile        a file to write before launching, or {@code null}
 * @param configFileContent its content, or {@code null}
 * @param briefing          Markdown from the profile's context; empty when there is none
 * @param notices           what the profile holds that this launch does not apply
 */
public record LaunchPlan(AgentProvider provider, String executable, List<String> arguments,
		Map<String, String> environment, List<SecretBinding> secrets, Path configFile, String configFileContent,
		String briefing, List<String> notices) {

	/** Longest linked document read into the briefing, in characters. */
	static final int DOCUMENT_LIMIT = 100_000;

	/** Longest environment file read, in characters; a larger one is not a {@code .env} file. */
	static final int ENVIRONMENT_FILE_LIMIT = 1_000_000;

	/** Defensive copies. */
	public LaunchPlan {
		arguments = List.copyOf(arguments);
		environment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(environment));
		secrets = List.copyOf(secrets);
		notices = List.copyOf(notices);
	}

	/**
	 * Plan a launch.
	 *
	 * <p>A relative path in the profile - a {@code .env} file, an instruction, a skill
	 * or a knowledge folder - is resolved against the agent's working directory, the
	 * one place a shared profile can mean the same thing in every project.
	 *
	 * @param profile          the profile
	 * @param runtimeDirectory where files for this launch may be written
	 * @param home             the user's home folder, for {@code ~}
	 * @param workingDirectory the directory the agent runs in
	 * @return the plan
	 * @throws IllegalArgumentException with a message for the user when the profile cannot start an agent
	 */
	public static LaunchPlan of(Profile profile, Path runtimeDirectory, String home, Path workingDirectory) {
		var paths = new Paths(home, workingDirectory);

		var problems = ProfileValidator.validate(profile, List.of());
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("Profile \"" + profile.displayName() + "\" cannot be used yet: "
					+ problems.getFirst().message());
		}
		var harness = profile.getHarness();
		var provider = AgentProvider.byId(harness.getProvider()).orElseThrow(() -> new IllegalArgumentException(
				"Profile \"" + profile.displayName() + "\" names no provider. Choose one on its Model / runtime tab."));
		var connector = provider.connector();
		var notices = new ArrayList<String>();

		var executable = blank(harness.getExecutable()) ? provider.defaultExecutable() : harness.getExecutable().strip();
		var mode = AccessMode.byId(harness.getAccessMode()).orElse(null);
		var effectiveMode = mode == null ? connector.defaultAccessMode() : mode;
		var allowedTools = connector.canRestrictTools() && !harness.restrictsTools() ? List.<String>of()
				: nullToEmpty(harness.getAllowedTools());

		var arguments = new ArrayList<String>();
		// The profile's own arguments first: a prompt among them stays ahead of options that take lists.
		arguments.addAll(nullToEmpty(harness.getStartupArgs()));
		arguments.addAll(connector.launchArguments(harness.getModel(), harness.getEffort(), mode, allowedTools,
				nullToEmpty(harness.getBlockedTools()), strip(harness.getAllowedCommands()),
				strip(harness.getBlockedCommands())));
		arguments.addAll(connector.extraFolderArguments(ExtraFolders.resolve(harness, home)));
		if (harness.isSandboxNetworkAccess()) {
			arguments.addAll(connector.sandboxNetworkArguments(effectiveMode));
		}

		var mcp = connector.mcpSetup(harness.getMcpServers(), harness.restrictsMcpServers(),
				nullToEmpty(harness.getSwitchedOffMcpServers()), runtimeDirectory);
		arguments.addAll(mcp.arguments());
		var configFile = mcp.configFileName() == null ? null : runtimeDirectory.resolve(mcp.configFileName());

		var environment = new LinkedHashMap<String, String>();
		for (var record : enabled(harness.getEnvironment())) {
			if (record.getKind() == RecordKind.FILE) {
				readEnvironmentFile(paths.resolveOrRefuse(record.getPath(), "The environment file"), environment);
			} else if (!blank(record.getName())) {
				environment.put(record.getName().strip(), record.getText() == null ? "" : record.getText());
			}
		}

		var skills = new ArrayList<ProfileRecord>();
		for (var skill : enabled(profile.getContext().getSkills())) {
			if (provider == AgentProvider.PI && skill.getKind() == RecordKind.FILE) {
				arguments.add("--skill");
				arguments.add(paths.resolveOrRefuse(skill.getPath(), "The skill \"" + skill.displayName() + "\"").toString());
			} else {
				skills.add(skill);
			}
		}
		var briefing = briefing(profile, skills, paths, notices);

		return new LaunchPlan(provider, executable, arguments, environment, mcp.secrets(), configFile,
				mcp.configFileContent(), briefing, notices);
	}

	/**
	 * The secret variables' values, for the agent's environment. Off the event
	 * thread: the credential store may block or ask to be unlocked. Values are never
	 * logged, and no message names one.
	 *
	 * @param secrets            the plan's secrets
	 * @param store              the credential store
	 * @param processEnvironment where secrets taken from the environment are read
	 * @return variable to value
	 * @throws IllegalStateException with a message for the user when a secret is missing or cannot be read
	 */
	public static Map<String, String> resolveSecrets(List<SecretBinding> secrets, ProfileSecrets store,
			Map<String, String> processEnvironment) {
		var values = new LinkedHashMap<String, String>();
		for (var binding : secrets) {
			var secret = binding.secret();
			if (secret.fromEnvironment()) {
				// The configuration already names the variable itself; it only has to exist.
				var value = processEnvironment.get(secret.getVariable());
				if (value == null) {
					throw new IllegalStateException("An MCP server needs the environment variable "
							+ secret.getVariable() + ", which is not set.");
				}
				values.put(binding.variable(), value);
				continue;
			}
			if (secret.needsEntry()) {
				throw new IllegalStateException("An MCP server secret has not been entered on this machine. "
						+ "Edit the profile and enter it.");
			}
			final Optional<String> value;
			try {
				value = store.read(secret.getKey());
			} catch (NuclrCredentialException e) {
				throw new IllegalStateException("Could not read an MCP server secret from the credential store: "
						+ e.getMessage(), e);
			}
			values.put(binding.variable(), value.orElseThrow(() -> new IllegalStateException(
					"An MCP server secret is missing from the credential store. Edit the profile and enter it again.")));
		}
		return values;
	}

	/** Write the configuration file, if there is one. */
	public void writeConfigFile() throws IOException {
		if (configFile != null) {
			Files.createDirectories(configFile.getParent());
			Files.writeString(configFile, configFileContent, StandardCharsets.UTF_8);
		}
	}

	/** The command line, executable first. */
	public List<String> commandLine() {
		var command = new ArrayList<String>();
		command.add(executable);
		command.addAll(arguments);
		return command;
	}

	private static String briefing(Profile profile, List<ProfileRecord> skills, Paths home, List<String> notices) {
		var context = profile.getContext();
		var body = new StringBuilder();
		var notFound = new ArrayList<String>();

		var instructions = new StringBuilder();
		for (var record : enabled(context.getInstructions())) {
			var content = content(record, home, notices, notFound, "Instruction");
			if (content != null) {
				instructions.append("### ").append(record.displayName()).append("\n\n").append(content.strip())
						.append("\n\n");
			}
		}
		if (!instructions.isEmpty()) {
			body.append("## Instructions\n\n").append(instructions);
		}

		var skillText = new StringBuilder();
		for (var record : skills) {
			if (record.getKind() == RecordKind.TEXT) {
				skillText.append("### ").append(record.displayName()).append("\n\n")
						.append(record.getText() == null ? "" : record.getText().strip()).append("\n\n");
			} else {
				skillText.append("- ").append(record.displayName()).append(": ").append(reference(record, home))
						.append(" - read its SKILL.md when the skill is relevant\n");
			}
		}
		if (!skillText.isEmpty()) {
			body.append("## Skills\n\n").append(skillText.toString().stripTrailing()).append("\n\n");
		}

		var knowledge = new StringBuilder();
		for (var record : enabled(context.getKnowledge())) {
			if (record.getKind() == RecordKind.TEXT) {
				knowledge.append("### ").append(record.displayName()).append("\n\n")
						.append(record.getText() == null ? "" : record.getText().strip()).append("\n\n");
			} else {
				knowledge.append("- ").append(record.displayName()).append(": ").append(reference(record, home))
						.append('\n');
			}
		}
		if (!knowledge.isEmpty()) {
			body.append("## Knowledge\n\nConsult these when they are relevant; they are not included here.\n\n")
					.append(knowledge.toString().stripTrailing()).append("\n\n");
		}

		if (!notFound.isEmpty()) {
			body.append("## Not found\n\nThese were configured for you but could not be read. "
					+ "Mention it if they matter.\n\n");
			notFound.forEach(item -> body.append("- ").append(item).append('\n'));
		}
		if (body.isEmpty()) {
			return "";
		}
		return "# Profile: " + profile.displayName() + "\n\n" + body.toString().stripTrailing() + "\n";
	}

	/** An instruction's text: written in place, or read from its file; git sources are not fetched yet. */
	private static String content(ProfileRecord record, Paths home, List<String> notices, List<String> notFound,
			String noun) {
		return switch (record.getKind() == null ? RecordKind.TEXT : record.getKind()) {
			case TEXT -> record.getText();
			case FILE -> {
				final Path path;
				try {
					path = home.resolve(record.getPath());
				} catch (RuntimeException e) {
					notFound.add(record.displayName() + " (" + record.getPath() + ")");
					notices.add(noun + " \"" + record.displayName() + "\" has a path that is not valid here");
					yield null;
				}
				try {
					var text = readBounded(path, DOCUMENT_LIMIT);
					yield text.length() <= DOCUMENT_LIMIT ? text
							: text.substring(0, DOCUMENT_LIMIT) + "\n\n[... truncated; read " + path + " for the rest]";
				} catch (IOException | RuntimeException e) {
					notFound.add(record.displayName() + " (" + path + ")");
					notices.add(noun + " \"" + record.displayName() + "\" was not found at " + path);
					yield null;
				}
			}
			case GIT -> {
				notices.add(noun + " \"" + record.displayName() + "\" is in a git repository, which is not fetched yet");
				notFound.add(record.displayName() + " (" + record.getRepository() + ")");
				yield null;
			}
		};
	}

	private static String reference(ProfileRecord record, Paths home) {
		if (record.getKind() == RecordKind.GIT) {
			var text = new StringBuilder(record.getRepository() == null ? "" : record.getRepository());
			if (!blank(record.getRef())) {
				text.append(" at ").append(record.getRef().strip());
			}
			if (!blank(record.getPath())) {
				text.append(", ").append(record.getPath().strip());
			}
			return text.toString();
		}
		try {
			return home.resolve(record.getPath() == null ? "" : record.getPath()).toString();
		} catch (RuntimeException e) {
			return record.getPath();
		}
	}

	/** Where a profile's paths point: {@code ~} is the home folder, and a relative path is under the working directory. */
	private record Paths(String home, Path workingDirectory) {

		Path resolve(String written) {
			var path = Path.of(ExtraFolders.expandHome(written, home));
			return path.isAbsolute() ? path : workingDirectory.resolve(path).normalize();
		}

		/** {@link #resolve}, refusing the launch when the path cannot exist on this system. */
		Path resolveOrRefuse(String written, String what) {
			try {
				return resolve(written);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(what + " has a path that is not valid here: " + written);
			}
		}
	}

	private static String readBounded(Path file, int limit) throws IOException {
		return dev.nuclr.plugin.core.ai.projects.store.TextFiles.readBounded(file, limit);
	}

	/**
	 * Read {@code NAME=value} lines. Blank lines and {@code #} comments are skipped,
	 * {@code export} is allowed, and one pair of surrounding quotes is removed.
	 */
	static void readEnvironmentFile(Path file, Map<String, String> into) {
		final String text;
		try {
			text = readBounded(file, ENVIRONMENT_FILE_LIMIT);
		} catch (IOException | RuntimeException e) {
			throw new IllegalArgumentException("The environment file " + file + " cannot be read.");
		}
		if (text.length() > ENVIRONMENT_FILE_LIMIT) {
			throw new IllegalArgumentException("The environment file " + file + " is too large to be a .env file.");
		}
		for (var raw : text.split("\\R")) {
			var line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("export ")) {
				line = line.substring("export ".length()).strip();
			}
			var equals = line.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			var value = line.substring(equals + 1).strip();
			if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
					|| value.startsWith("'") && value.endsWith("'"))) {
				value = value.substring(1, value.length() - 1);
			}
			into.put(line.substring(0, equals).strip(), value);
		}
	}

	private static List<ProfileRecord> enabled(List<ProfileRecord> records) {
		return records == null ? List.of()
				: records.stream().filter(record -> record != null && record.isEnabled()).toList();
	}

	private static List<String> strip(List<String> values) {
		return nullToEmpty(values).stream().map(String::strip).toList();
	}

	private static <T> List<T> nullToEmpty(List<T> values) {
		return values == null ? List.of() : values;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
