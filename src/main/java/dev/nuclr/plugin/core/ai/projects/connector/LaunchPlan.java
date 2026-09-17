package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector.SecretBinding;
import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector.SkillLoading;
import dev.nuclr.plugin.core.ai.projects.profile.ExtraFolders;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.TextFiles;

/**
 * How to start an agent from a profile, in a form any kind of session can use: the
 * command, the environment, the files to write first, the secrets to put in the
 * environment, and the briefing built from the profile's context.
 *
 * <p>Building a plan reads the files the profile links - a bounded amount of each,
 * but from disks that may be slow or remote - and fetches its git sources, so it
 * belongs off the event thread. It never touches the credential store. Secrets are
 * named here and read by {@link #resolveSecrets} when the process is about to start,
 * so a plan can be built - and shown - without unlocking anything.
 *
 * <p>The context becomes three things. Instructions are placed in full in the
 * briefing. Skills - folders with a {@code SKILL.md} - are given to the CLI as real
 * skills where it can take them ({@link SkillLoading}), and otherwise listed with
 * their descriptions. Knowledge is only pointed to: its paths are listed, never its
 * content.
 *
 * @param provider          the CLI
 * @param executable        the command, as the profile names it or the provider's own
 * @param arguments         everything after the executable
 * @param environment       variables to set, from the profile's Environment tab
 * @param secrets           variables whose values are secrets, resolved at launch
 * @param configFile        the MCP configuration file to write before launching, or {@code null}
 * @param configFileContent its content, or {@code null}
 * @param skillsPlugin      the plugin folder skill folders are copied into, or {@code null}
 * @param skillCopies       the skill folders to copy into it
 * @param briefing          Markdown from the profile's context; empty when there is none
 * @param notices           what the profile holds that this launch does not apply
 */
public record LaunchPlan(AgentProvider provider, String executable, List<String> arguments,
		Map<String, String> environment, List<SecretBinding> secrets, Path configFile, String configFileContent,
		Path skillsPlugin, List<SkillCopy> skillCopies, String briefing, List<String> notices) {

	/** Longest linked document read into the briefing, in characters. */
	public static final int DOCUMENT_LIMIT = 100_000;

	/** Longest environment file read, in characters; a larger one is not a {@code .env} file. */
	static final int ENVIRONMENT_FILE_LIMIT = 1_000_000;

	/** Most files copied from one skill folder. */
	static final int SKILL_FILE_LIMIT = 2_000;

	/** Most bytes copied from one skill folder. */
	static final long SKILL_BYTE_LIMIT = 50L * 1024 * 1024;

	/** The plugin folder Claude Code is given the profile's skills in; its name is the skills' namespace. */
	static final String SKILLS_PLUGIN = "nuclr-profile";

	/**
	 * One skill folder copied for the session.
	 *
	 * @param from the skill folder
	 * @param to   where it is copied
	 */
	public record SkillCopy(Path from, Path to) {
	}

	/** Defensive copies. */
	public LaunchPlan {
		arguments = List.copyOf(arguments);
		environment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(environment));
		secrets = List.copyOf(secrets);
		skillCopies = List.copyOf(skillCopies);
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
	 * @param workingDirectory the directory the agent runs in, or {@code null} for a preview with none, where
	 *                         relative paths are left for the start
	 * @param git              where git sources come from
	 * @return the plan
	 * @throws IllegalArgumentException with a message for the user when the profile cannot start an agent
	 */
	public static LaunchPlan of(Profile profile, Path runtimeDirectory, String home, Path workingDirectory,
			GitSources git) {

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
		var sources = new Sources(new Paths(home, workingDirectory), git, notices);

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
				var file = sources.paths().resolveOrRefuse(record.getPath(), "The environment file");
				if (file != null) {
					readEnvironmentFile(file, environment);
				}
			} else if (!blank(record.getName())) {
				environment.put(record.getName().strip(), record.getText() == null ? "" : record.getText());
			}
		}

		var skills = skills(profile, connector.skillLoading(), runtimeDirectory, sources);
		arguments.addAll(skills.arguments());
		var briefing = briefing(profile, skills.listed(), sources);

		return new LaunchPlan(provider, executable, arguments, environment, mcp.secrets(), configFile,
				mcp.configFileContent(), skills.plugin(), skills.copies(), briefing, notices);
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

	/**
	 * Write what the launch needs on disk: the MCP configuration, and a fresh copy of
	 * every skill folder in the plugin folder. Off the event thread.
	 *
	 * @throws IOException when a file cannot be written, or a skill folder is too large to copy
	 */
	public void writeFiles() throws IOException {
		if (configFile != null) {
			Files.createDirectories(configFile.getParent());
			Files.writeString(configFile, configFileContent, StandardCharsets.UTF_8);
		}
		if (skillsPlugin != null) {
			// Rebuilt every start, so a skill removed from the profile is gone from the session too.
			deleteTree(skillsPlugin);
			for (var copy : skillCopies) {
				copyTree(copy.from(), copy.to());
			}
		}
	}

	/** The command line, executable first. */
	public List<String> commandLine() {
		var command = new ArrayList<String>();
		command.add(executable);
		command.addAll(arguments);
		return command;
	}

	// ------------------------------------------------------------------ skills

	/**
	 * A skill folder found for the launch.
	 *
	 * @param name        the name to show: the {@code SKILL.md} name, or the record's
	 * @param description the {@code SKILL.md} description, or blank
	 * @param folder      the folder
	 */
	record Skill(String name, String description, Path folder) {
	}

	private record Skills(List<String> arguments, Path plugin, List<SkillCopy> copies, List<Skill> listed) {
	}

	private static Skills skills(Profile profile, SkillLoading loading, Path runtimeDirectory, Sources sources) {
		var arguments = new ArrayList<String>();
		var copies = new ArrayList<SkillCopy>();
		var listed = new ArrayList<Skill>();
		var plugin = runtimeDirectory.resolve(SKILLS_PLUGIN);
		var names = new HashSet<String>();
		for (var record : enabled(profile.getContext().getSkills())) {
			var located = sources.locate(record, "Skill");
			if (located == null) {
				continue;
			}
			var folder = located;
			if (Files.isRegularFile(folder) && folder.getFileName().toString().equalsIgnoreCase("SKILL.md")) {
				folder = folder.getParent();
			}
			if (!Files.isRegularFile(folder.resolve("SKILL.md"))) {
				sources.unavailable(record, "Skill", "has no SKILL.md in " + folder);
				continue;
			}
			var skill = readSkill(record, folder);
			switch (loading) {
				case OWN_FLAG -> {
					arguments.add("--skill");
					arguments.add(folder.toString());
				}
				case PLUGIN_FOLDER -> copies.add(new SkillCopy(folder,
						plugin.resolve("skills").resolve(unique(safeName(skill.name()), names))));
				case BRIEFING -> listed.add(skill);
			}
		}
		if (!copies.isEmpty()) {
			arguments.add("--plugin-dir");
			arguments.add(plugin.toString());
		}
		return new Skills(arguments, copies.isEmpty() ? null : plugin, copies, listed);
	}

	/** A skill's name and description, from the front matter of its {@code SKILL.md}. */
	static Skill readSkill(ProfileRecord record, Path folder) {
		String name = null;
		var description = "";
		try {
			var text = TextFiles.readBounded(folder.resolve("SKILL.md"), 16_000);
			var lines = text.split("\\R");
			if (lines.length > 0 && lines[0].strip().equals("---")) {
				for (var index = 1; index < lines.length && !lines[index].strip().equals("---"); index++) {
					var line = lines[index];
					if (line.startsWith("name:")) {
						name = unquote(line.substring("name:".length()));
					} else if (line.startsWith("description:")) {
						description = unquote(line.substring("description:".length()));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// The name and description are only a courtesy; the folder is still the skill.
		}
		if (blank(name)) {
			name = blank(record.getName()) ? folder.getFileName().toString() : record.getName().strip();
		}
		return new Skill(name, description, folder);
	}

	private static String unquote(String value) {
		var text = value.strip();
		if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
				|| text.startsWith("'") && text.endsWith("'"))) {
			text = text.substring(1, text.length() - 1);
		}
		return text;
	}

	/** A folder name a skill can be copied under, on every file system. */
	private static String safeName(String name) {
		var safe = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
		return safe.isEmpty() ? "skill" : safe;
	}

	private static String unique(String name, Set<String> taken) {
		var candidate = name;
		for (var suffix = 2; !taken.add(candidate); suffix++) {
			candidate = name + "-" + suffix;
		}
		return candidate;
	}

	// ------------------------------------------------------------------ briefing

	private static String briefing(Profile profile, List<Skill> skills, Sources sources) {
		var context = profile.getContext();
		var body = new StringBuilder();

		var instructions = new StringBuilder();
		for (var record : enabled(context.getInstructions())) {
			var content = instruction(record, sources);
			if (content != null) {
				instructions.append("### ").append(record.displayName()).append("\n\n").append(content.strip())
						.append("\n\n");
			}
		}
		if (!instructions.isEmpty()) {
			body.append("## Instructions\n\n").append(instructions);
		}

		if (!skills.isEmpty()) {
			body.append("## Skills\n\nRead a skill's SKILL.md, and follow it, when its description matches the task.\n\n");
			for (var skill : skills) {
				body.append("- ").append(skill.name());
				if (!skill.description().isBlank()) {
					body.append(": ").append(skill.description());
				}
				body.append(" (").append(skill.folder().resolve("SKILL.md")).append(")\n");
			}
			body.append('\n');
		}

		var knowledge = new StringBuilder();
		for (var record : enabled(context.getKnowledge())) {
			var located = sources.locate(record, "Knowledge source");
			if (located != null) {
				knowledge.append("- ").append(record.displayName()).append(": ").append(located);
				if (record.getKind() == RecordKind.GIT) {
					knowledge.append(" (a copy of ").append(record.getRepository().strip())
							.append(blank(record.getRef()) ? "" : " at " + record.getRef().strip()).append(')');
				}
				knowledge.append('\n');
			}
		}
		if (!knowledge.isEmpty()) {
			body.append("## Knowledge\n\nConsult these when they are relevant; they are not included here.\n\n")
					.append(knowledge).append('\n');
		}

		if (!sources.notFound().isEmpty()) {
			body.append("## Not found\n\nThese were configured for you but could not be read. "
					+ "Mention it if they matter.\n\n");
			sources.notFound().forEach(item -> body.append("- ").append(item).append('\n'));
		}
		if (body.isEmpty()) {
			return "";
		}
		return "# Profile: " + profile.displayName() + "\n\n" + body.toString().stripTrailing() + "\n";
	}

	/** An instruction's text: written in place, or read from its file or from a file in its repository. */
	private static String instruction(ProfileRecord record, Sources sources) {
		if (record.getKind() == null || record.getKind() == RecordKind.TEXT) {
			return record.getText();
		}
		var path = sources.locate(record, "Instruction");
		if (path == null) {
			return null;
		}
		if (!Files.isRegularFile(path)) {
			sources.unavailable(record, "Instruction", "is not a file: " + path);
			return null;
		}
		try {
			var text = TextFiles.readBounded(path, DOCUMENT_LIMIT);
			return text.length() <= DOCUMENT_LIMIT ? text
					: text.substring(0, DOCUMENT_LIMIT) + "\n\n[... truncated; read " + path + " for the rest]";
		} catch (IOException | RuntimeException e) {
			sources.unavailable(record, "Instruction", "cannot be read at " + path);
			return null;
		}
	}

	// ------------------------------------------------------------------ where records point

	/** Where a profile's paths point: {@code ~} is the home folder, and a relative path is under the working directory. */
	private record Paths(String home, Path workingDirectory) {

		/** The path, or {@code null} when it is relative and there is no working directory yet. */
		Path resolve(String written) {
			var path = Path.of(ExtraFolders.expandHome(written, home));
			if (path.isAbsolute()) {
				return path;
			}
			return workingDirectory == null ? null : workingDirectory.resolve(path).normalize();
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

	/** Finds records on disk, fetching each repository once, and keeps what could not be found. */
	private static final class Sources {

		private final Paths paths;
		private final GitSources git;
		private final List<String> notices;
		private final List<String> notFound = new ArrayList<>();
		private final Map<String, Object> checkouts = new HashMap<>();

		Sources(Paths paths, GitSources git, List<String> notices) {
			this.paths = paths;
			this.git = git;
			this.notices = notices;
		}

		Paths paths() {
			return paths;
		}

		List<String> notFound() {
			return notFound;
		}

		/**
		 * Where a file or git record is on this machine.
		 *
		 * @return the path, or {@code null} after noting why there is none
		 */
		Path locate(ProfileRecord record, String noun) {
			if (record.getKind() == RecordKind.GIT) {
				return inRepository(record, noun);
			}
			try {
				var path = paths.resolve(record.getPath());
				if (path == null) {
					notices.add(noun + " \"" + record.displayName() + "\" is relative, so it is found in the agent's "
							+ "working folder when it starts");
					return null;
				}
				if (!Files.exists(path)) {
					unavailable(record, noun, "was not found at " + path);
					return null;
				}
				return path;
			} catch (RuntimeException e) {
				unavailable(record, noun, "has a path that is not valid here: " + record.getPath());
				return null;
			}
		}

		private Path inRepository(ProfileRecord record, String noun) {
			var repository = record.getRepository().strip();
			var ref = blank(record.getRef()) ? "" : record.getRef().strip();
			var key = repository + "\n" + ref;
			var result = checkouts.computeIfAbsent(key, ignored -> {
				try {
					var checkout = git.checkout(repository, ref);
					if (checkout.cached()) {
						notices.add("Used the copy of " + repository + " from an earlier start, as it could not be "
								+ "updated: " + checkout.reason());
					}
					return checkout;
				} catch (IOException e) {
					return e.getMessage();
				}
			});
			if (result instanceof String reason) {
				unavailable(record, noun, "could not be fetched from " + repository + ": " + reason);
				return null;
			}
			var copy = ((GitSources.Checkout) result).copy();
			var path = blank(record.getPath()) ? copy : copy.resolve(record.getPath().strip()).normalize();
			if (!path.startsWith(copy) || !Files.exists(path)) {
				unavailable(record, noun, "names " + record.getPath() + ", which is not in " + repository);
				return null;
			}
			return path;
		}

		void unavailable(ProfileRecord record, String noun, String why) {
			notFound.add(record.displayName() + " (" + why + ")");
			notices.add(noun + " \"" + record.displayName() + "\" " + why);
		}
	}

	// ------------------------------------------------------------------ files

	/**
	 * Read {@code NAME=value} lines. Blank lines and {@code #} comments are skipped,
	 * {@code export} is allowed, and one pair of surrounding quotes is removed.
	 */
	static void readEnvironmentFile(Path file, Map<String, String> into) {
		final String text;
		try {
			text = TextFiles.readBounded(file, ENVIRONMENT_FILE_LIMIT);
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

	/** Copy a skill folder, leaving out version control, within the limits a skill should fit. */
	private static void copyTree(Path from, Path to) throws IOException {
		final List<Path> files;
		try (var walk = Files.walk(from)) {
			files = walk.filter(path -> !from.relativize(path).toString().replace('\\', '/').matches("(^|.*/)\\.git(/.*)?"))
					.toList();
		}
		var count = 0;
		var bytes = 0L;
		for (var path : files) {
			if (Files.isRegularFile(path)) {
				count++;
				bytes += Files.size(path);
			}
		}
		if (count > SKILL_FILE_LIMIT || bytes > SKILL_BYTE_LIMIT) {
			throw new IOException("The skill folder " + from + " is too large to load as a skill (" + count
					+ " files, " + (bytes / (1024 * 1024)) + " MB).");
		}
		for (var path : files) {
			var target = to.resolve(from.relativize(path).toString());
			if (Files.isDirectory(path)) {
				Files.createDirectories(target);
			} else if (Files.isRegularFile(path)) {
				Files.createDirectories(target.getParent());
				Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void deleteTree(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (var walk = Files.walk(folder)) {
			for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
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
