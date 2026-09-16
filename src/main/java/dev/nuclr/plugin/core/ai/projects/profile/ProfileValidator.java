package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * Everything that makes a profile unsaveable, as messages a person can act on.
 *
 * <p>Only real errors are reported. A linked file that does not exist on this
 * machine is not one: profiles are shared, and the file may exist where the
 * profile is used. The editor shows that as a warning instead.
 */
public final class ProfileValidator {

	/** Longest profile name. */
	public static final int MAX_NAME_LENGTH = 80;

	private static final Pattern URL_REPOSITORY = Pattern.compile("^(https?|ssh|git|file)://\\S+$",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SCP_REPOSITORY = Pattern.compile("^[\\w.-]+@[\\w.-]+:\\S+$");

	private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

	private ProfileValidator() {
	}

	/**
	 * One problem.
	 *
	 * @param section the section it is in, or {@code null} for the profile itself
	 * @param index   the record's position in that section, or {@code -1}
	 * @param message what is wrong
	 */
	public record Problem(ProfileSection section, int index, String message) {
	}

	/**
	 * Validate a whole profile.
	 *
	 * @param profile    the profile
	 * @param otherNames the names of every other profile, for uniqueness
	 * @return the problems, empty when the profile can be saved
	 */
	public static List<Problem> validate(Profile profile, Collection<String> otherNames) {

		var problems = new ArrayList<Problem>();

		var name = profile.getName() == null ? "" : profile.getName().trim();
		if (name.isEmpty()) {
			problems.add(new Problem(null, -1, "Give the profile a name."));
		} else if (name.length() > MAX_NAME_LENGTH) {
			problems.add(new Problem(null, -1, "The name is longer than " + MAX_NAME_LENGTH + " characters."));
		} else if (otherNames != null && otherNames.stream()
				.anyMatch(other -> other != null && other.trim().equalsIgnoreCase(name))) {
			problems.add(new Problem(null, -1, "Another profile is already called \"" + name + "\"."));
		}

		var harness = profile.getHarness();
		if (harness != null && harness.getProvider() != null && !harness.getProvider().isBlank()
				&& AgentProvider.byId(harness.getProvider()).isEmpty()) {
			problems.add(new Problem(null, -1, "Provider: \"" + harness.getProvider().trim()
					+ "\" is not a supported provider. Choose Claude Code, Codex or Pi."));
		}
		if (harness != null && harness.getAccessMode() != null && !harness.getAccessMode().isBlank()) {
			var mode = AccessMode.byId(harness.getAccessMode());
			var provider = AgentProvider.byId(harness.getProvider());
			if (mode.isEmpty()) {
				problems.add(new Problem(null, -1, "Access mode: \"" + harness.getAccessMode().trim()
						+ "\" is not a known access mode."));
			} else if (provider.isPresent() && !provider.get().supports(mode.get())) {
				problems.add(new Problem(null, -1, "Access mode: " + provider.get().unsupportedReason(mode.get())));
			}
		}
		if (harness != null) {
			problems.addAll(toolProblems(harness));
		}
		if (harness != null) {
			positive(problems, harness.getMaxTurns(), "Max turns");
			positive(problems, harness.getTimeoutMinutes(), "Timeout");
			if (harness.getMaxBudgetUsd() != null
					&& !(harness.getMaxBudgetUsd() > 0 && Double.isFinite(harness.getMaxBudgetUsd()))) {
				problems.add(new Problem(null, -1, "Max budget must be greater than zero."));
			}
			if (harness.getMcpServers() != null) {
				var names = new HashSet<String>();
				for (var server : harness.getMcpServers()) {
					if (server == null || server.getName() == null || server.getName().isBlank()) {
						problems.add(new Problem(null, -1, "Every MCP server needs a name."));
					} else if (!names.add(server.getName().trim().toLowerCase(Locale.ROOT))) {
						problems.add(new Problem(null, -1, "Two MCP servers are called \"" + server.getName().trim() + "\"."));
					} else if (server.getCommand() == null || server.getCommand().isBlank()) {
						problems.add(new Problem(null, -1, "MCP server \"" + server.getName().trim() + "\" has no command."));
					}
				}
			}
		}

		for (var section : ProfileSection.values()) {
			var records = section.records(profile);
			var keys = new HashSet<String>();
			for (var index = 0; index < records.size(); index++) {
				var record = records.get(index);
				for (var message : validateRecord(section, record)) {
					problems.add(new Problem(section, index, message));
				}
				var key = duplicateKey(section, record);
				if (key != null && !keys.add(key)) {
					problems.add(new Problem(section, index, section.title() + ": \"" + record.displayName()
							+ "\" is listed more than once."));
				}
			}
		}
		return problems;
	}

	/** Tool lists only mean something to a provider, which also knows which names and patterns it takes. */
	private static List<Problem> toolProblems(Profile.Harness harness) {
		var problems = new ArrayList<Problem>();
		var allowed = nullToEmpty(harness.getAllowedTools());
		var blocked = nullToEmpty(harness.getBlockedTools());
		var restricted = harness.restrictsTools();
		if (allowed.isEmpty() && blocked.isEmpty() && !restricted) {
			return problems;
		}
		var provider = AgentProvider.byId(harness.getProvider());
		if (provider.isPresent()) {
			var connector = provider.get().connector();
			if (restricted && !connector.canRestrictTools()) {
				problems.add(new Problem(null, -1, "Tools: " + provider.get().displayName()
						+ " cannot be limited to selected tools. Choose All tools."));
				return problems;
			}
			if (restricted && allowed.isEmpty()) {
				problems.add(new Problem(null, -1,
						"Tools: \"Only selected tools\" is chosen but no tool is selected. Add tools, or choose All tools."));
				return problems;
			}
			if (connector.canRestrictTools() && !restricted) {
				// A list left over from "Only selected tools" is not in force.
				allowed = List.of();
			}
		}
		if (provider.isEmpty()) {
			problems.add(new Problem(null, -1,
					"Tools: choose a provider first - tool names are specific to each provider."));
			return problems;
		}
		var mode = AccessMode.byId(harness.getAccessMode()).orElse(provider.get().defaultAccessMode());
		for (var message : provider.get().connector().toolProblems(allowed, blocked, mode)) {
			problems.add(new Problem(null, -1, message));
		}
		for (var name : allowed) {
			if (java.util.Collections.frequency(allowed, name) > 1) {
				problems.add(new Problem(null, -1, "Tools: \"" + name + "\" is allowed more than once."));
				break;
			}
		}
		return problems;
	}

	private static List<String> nullToEmpty(List<String> values) {
		return values == null ? List.of() : values;
	}

	/**
	 * Validate one record against its section.
	 *
	 * @param section the section
	 * @param record  the record
	 * @return messages, empty when valid
	 */
	public static List<String> validateRecord(ProfileSection section, ProfileRecord record) {

		var problems = new ArrayList<String>();
		if (record == null) {
			problems.add("The entry is empty.");
			return problems;
		}
		var kind = record.getKind() == null ? RecordKind.TEXT : record.getKind();
		var noun = capitalise(section.singular());
		if (!section.accepts(kind)) {
			problems.add(noun + ": " + kind.label().toLowerCase(Locale.ROOT) + " is not available here.");
			return problems;
		}

		switch (kind) {
			case TEXT -> {
				switch (section.textStyle()) {
					case VALUE -> {
						if (blank(record.getText())) {
							problems.add(noun + ": enter a value.");
						} else if (record.getText().strip().contains("\n")) {
							problems.add(noun + ": the value must be a single line.");
						}
					}
					case NAME_VALUE -> {
						if (blank(record.getName())) {
							problems.add(noun + ": enter a name.");
						} else if (!VARIABLE_NAME.matcher(record.getName().trim()).matches()) {
							problems.add(noun + ": \"" + record.getName().trim()
									+ "\" is not a valid name - use letters, digits and underscores.");
						}
						if (record.getText() != null && record.getText().strip().contains("\n")) {
							problems.add(noun + ": the value must be a single line.");
						}
					}
					case DOCUMENT -> {
						if (blank(record.getName())) {
							problems.add(noun + ": enter a name.");
						}
						if (blank(record.getText())) {
							problems.add(noun + ": the text is empty.");
						}
					}
				}
			}
			case FILE -> {
				if (blank(record.getPath())) {
					problems.add(noun + ": choose a " + (section.browse() == ProfileSection.Browse.DIRECTORIES
							? "folder." : "file."));
				}
			}
			case GIT -> {
				if (blank(record.getRepository())) {
					problems.add(noun + ": enter the repository URL.");
				} else if (!isRepository(record.getRepository())) {
					problems.add(noun + ": \"" + record.getRepository().trim()
							+ "\" does not look like a git repository URL.");
				}
				if (!blank(record.getRef()) && record.getRef().trim().chars().anyMatch(Character::isWhitespace)) {
					problems.add(noun + ": a branch, tag or commit cannot contain spaces.");
				}
				if (!blank(record.getPath()) && !isRelativePath(record.getPath().trim())) {
					problems.add(noun + ": the path inside the repository must be relative, without \"..\".");
				}
			}
		}
		return problems;
	}

	/**
	 * Whether a string looks like something {@code git clone} accepts: a URL, or
	 * the {@code user@host:path} form.
	 *
	 * @param repository the candidate
	 * @return whether it is plausible
	 */
	public static boolean isRepository(String repository) {
		if (blank(repository)) {
			return false;
		}
		var value = repository.trim();
		return URL_REPOSITORY.matcher(value).matches() || SCP_REPOSITORY.matcher(value).matches();
	}

	private static boolean isRelativePath(String path) {
		if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
			return false;
		}
		for (var segment : path.split("[/\\\\]")) {
			if (segment.equals("..")) {
				return false;
			}
		}
		return true;
	}

	/** What makes two records in one section the same thing, or {@code null} when duplicates are harmless. */
	private static String duplicateKey(ProfileSection section, ProfileRecord record) {
		if (record == null) {
			return null;
		}
		var kind = record.getKind() == null ? RecordKind.TEXT : record.getKind();
		return switch (kind) {
			case TEXT -> switch (section.textStyle()) {
				case VALUE -> blank(record.getText()) ? null : "text:" + record.getText().strip().toLowerCase(Locale.ROOT);
				case NAME_VALUE, DOCUMENT -> blank(record.getName()) ? null
						: "name:" + record.getName().trim().toLowerCase(Locale.ROOT);
			};
			case FILE -> blank(record.getPath()) ? null : "file:" + record.getPath().trim().toLowerCase(Locale.ROOT);
			case GIT -> blank(record.getRepository()) ? null
					: "git:" + record.getRepository().trim().toLowerCase(Locale.ROOT)
							+ "@" + (blank(record.getRef()) ? "" : record.getRef().trim())
							+ ":" + (blank(record.getPath()) ? "" : record.getPath().trim());
		};
	}

	private static void positive(List<Problem> problems, Integer value, String label) {
		if (value != null && value <= 0) {
			problems.add(new Problem(null, -1, label + " must be greater than zero."));
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String capitalise(String value) {
		return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
