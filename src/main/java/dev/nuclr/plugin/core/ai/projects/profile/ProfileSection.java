package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every record list a profile holds, with what each one accepts.
 *
 * <p>Not every source makes sense everywhere. A software entry is a name, so it is plain
 * text; an allowed root is a folder, so it is a file link; an instruction can
 * be written in place, linked from disk or taken from a repository. The editor
 * offers exactly what is listed here and the validator enforces it.
 */
public enum ProfileSection {

	// Harness - what agents can do. Built-in tools are not records: they are plain
	// allowed and blocked name lists that a connector turns into flags.
	SOFTWARE(Group.HARNESS, "Software", "software entry",
			"Programs, package managers and services agents may drive, e.g. git, docker, npm.",
			TextStyle.VALUE, Browse.NONE, Set.of(RecordKind.TEXT),
			profile -> profile.getHarness().getSoftware(),
			(profile, records) -> profile.getHarness().setSoftware(records)),

	HARDWARE(Group.HARNESS, "Hardware", "hardware entry",
			"Devices and resources agents may reach, e.g. gpu, camera, /dev/ttyUSB0.",
			TextStyle.VALUE, Browse.NONE, Set.of(RecordKind.TEXT),
			profile -> profile.getHarness().getHardware(),
			(profile, records) -> profile.getHarness().setHardware(records)),

	PERMISSIONS(Group.HARNESS, "Permissions", "permission",
			"Permission grants in the harness's own vocabulary, or a settings file that holds them.",
			TextStyle.VALUE, Browse.FILES, Set.of(RecordKind.TEXT, RecordKind.FILE),
			profile -> profile.getHarness().getPermissions(),
			(profile, records) -> profile.getHarness().setPermissions(records)),

	ALLOWED_ROOTS(Group.HARNESS, "Allowed roots", "allowed root", "Folders agents may read and write.",
			TextStyle.VALUE, Browse.DIRECTORIES, Set.of(RecordKind.FILE),
			profile -> profile.getHarness().getAllowedRoots(),
			(profile, records) -> profile.getHarness().setAllowedRoots(records)),

	NETWORK(Group.HARNESS, "Network", "network rule", "Hosts, domains or networks agents may reach.",
			TextStyle.VALUE, Browse.NONE, Set.of(RecordKind.TEXT),
			profile -> profile.getHarness().getNetwork(),
			(profile, records) -> profile.getHarness().setNetwork(records)),

	ENVIRONMENT(Group.HARNESS, "Environment", "environment variable",
			"Variables set on the agent process, or a .env file to read them from.",
			TextStyle.NAME_VALUE, Browse.FILES, Set.of(RecordKind.TEXT, RecordKind.FILE),
			profile -> profile.getHarness().getEnvironment(),
			(profile, records) -> profile.getHarness().setEnvironment(records)),

	// Context - what agents know.
	INSTRUCTIONS(Group.CONTEXT, "Instructions", "instruction",
			"Instruction documents every agent is given.",
			TextStyle.DOCUMENT, Browse.FILES, Set.of(RecordKind.TEXT, RecordKind.FILE, RecordKind.GIT),
			profile -> profile.getContext().getInstructions(),
			(profile, records) -> profile.getContext().setInstructions(records)),

	SKILLS(Group.CONTEXT, "Skills", "skill",
			"Skill definitions. Stored in the profile; part of an agent's context once it is started with them.",
			TextStyle.DOCUMENT, Browse.FILES_AND_DIRECTORIES,
			Set.of(RecordKind.TEXT, RecordKind.FILE, RecordKind.GIT),
			profile -> profile.getContext().getSkills(),
			(profile, records) -> profile.getContext().setSkills(records)),

	KNOWLEDGE(Group.CONTEXT, "Knowledge", "knowledge source",
			"Documents, folders and repositories agents are pointed at, rather than handed in full.",
			TextStyle.DOCUMENT, Browse.FILES_AND_DIRECTORIES,
			Set.of(RecordKind.TEXT, RecordKind.FILE, RecordKind.GIT),
			profile -> profile.getContext().getKnowledge(),
			(profile, records) -> profile.getContext().setKnowledge(records)),

	FILES(Group.CONTEXT, "Files", "file", "Content placed in full in the agent's context at launch.",
			TextStyle.DOCUMENT, Browse.FILES, Set.of(RecordKind.TEXT, RecordKind.FILE, RecordKind.GIT),
			profile -> profile.getContext().getFiles(),
			(profile, records) -> profile.getContext().setFiles(records)),

	LOADING_RULES(Group.CONTEXT, "Loading rules", "loading rule",
			"Rules for what agents load into their context, e.g. exclude: target/**, or a file of rules.",
			TextStyle.VALUE, Browse.FILES, Set.of(RecordKind.TEXT, RecordKind.FILE),
			profile -> profile.getContext().getLoadingRules(),
			(profile, records) -> profile.getContext().setLoadingRules(records)),

	VARIABLES(Group.CONTEXT, "Variables", "variable", "Named values agents are told about.",
			TextStyle.NAME_VALUE, Browse.NONE, Set.of(RecordKind.TEXT),
			profile -> profile.getContext().getVariables(),
			(profile, records) -> profile.getContext().setVariables(records));

	/** Which half of the configuration a section belongs to. */
	public enum Group {
		/** What agents can do. */
		HARNESS,
		/** What agents know. */
		CONTEXT
	}

	/** How a plain-text record of this section is written. */
	public enum TextStyle {
		/** A single line, and nothing else: the value is its own name. */
		VALUE,
		/** A required name and a single-line value, like an environment variable. */
		NAME_VALUE,
		/** A required name and a multi-line document. */
		DOCUMENT
	}

	/** What a file link may point at. */
	public enum Browse {
		/** File links are not offered. */
		NONE,
		/** Files only. */
		FILES,
		/** Folders only. */
		DIRECTORIES,
		/** Either. */
		FILES_AND_DIRECTORIES
	}

	private final Group group;
	private final String title;
	private final String singular;
	private final String description;
	private final TextStyle textStyle;
	private final Browse browse;
	private final Set<RecordKind> kinds;
	private final Function<Profile, List<ProfileRecord>> getter;
	private final BiConsumer<Profile, List<ProfileRecord>> setter;

	ProfileSection(Group group, String title, String singular, String description, TextStyle textStyle,
			Browse browse, Set<RecordKind> kinds, Function<Profile, List<ProfileRecord>> getter,
			BiConsumer<Profile, List<ProfileRecord>> setter) {
		this.group = group;
		this.title = title;
		this.singular = singular;
		this.description = description;
		this.textStyle = textStyle;
		this.browse = browse;
		this.kinds = kinds;
		this.getter = getter;
		this.setter = setter;
	}

	/** The half this section belongs to. */
	public Group group() {
		return group;
	}

	/** Tab title. */
	public String title() {
		return title;
	}

	/** One record's noun, lower case: "Add " + singular. */
	public String singular() {
		return singular;
	}

	/** One sentence shown above the list. */
	public String description() {
		return description;
	}

	/** How plain text is written here. */
	public TextStyle textStyle() {
		return textStyle;
	}

	/** What a file link may point at. */
	public Browse browse() {
		return browse;
	}

	/** Whether a record kind is accepted here. */
	public boolean accepts(RecordKind kind) {
		return kinds.contains(kind);
	}

	/** The accepted kinds, in declaration order. */
	public List<RecordKind> kinds() {
		var accepted = new ArrayList<RecordKind>();
		for (var kind : RecordKind.values()) {
			if (kinds.contains(kind)) {
				accepted.add(kind);
			}
		}
		return accepted;
	}

	/**
	 * This section's records in a profile, never {@code null}.
	 *
	 * @param profile the profile
	 * @return the live list, or an empty one
	 */
	public List<ProfileRecord> records(Profile profile) {
		var records = getter.apply(profile);
		return records == null ? List.of() : records;
	}

	/**
	 * Replace this section's records.
	 *
	 * @param profile the profile
	 * @param records the new records
	 */
	public void setRecords(Profile profile, List<ProfileRecord> records) {
		setter.accept(profile, new ArrayList<>(records));
	}

	/**
	 * The sections of one half, in declaration order.
	 *
	 * @param group the half
	 * @return its sections
	 */
	public static List<ProfileSection> of(Group group) {
		var sections = new ArrayList<ProfileSection>();
		for (var section : values()) {
			if (section.group == group) {
				sections.add(section);
			}
		}
		return sections;
	}
}
