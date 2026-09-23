package dev.nuclr.plugin.core.ai.projects.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * What an agent was given the last time it was started: which profile, how its briefing
 * reached the CLI, what the profile holds that the CLI did not take, and the command it
 * finally ran - so the agent window can show what this session really has, rather than
 * what the profile would give a launch now.
 *
 * <p>Kept small and free of secrets. The briefing itself stays in the briefing file the
 * launch wrote, and is named here with a digest, so a file written by a later launch is
 * told apart. Variables are listed by name only; secret values are never recorded.
 */
@Data
public class LaunchSummary {

	/** An argument longer than this is shown by its length, not its content. */
	public static final int LONGEST_ARGUMENT = 300;

	/** When the process was started. */
	private Instant launchedAt;

	/** The CLI, as the window names it: "Claude Code", "Codex". */
	private String cli;

	/** The profile's name, or {@code null} when the agent started without one. */
	private String profileName;

	/** The profile as the agent named it, written as {@code ProfileRef#toString()}. */
	private String profileRef;

	/** A digest of the profile as it was read, to tell whether it has changed since. */
	private String profileDigest;

	/** The command line as it was run, with the briefing and other long arguments shortened. */
	private List<String> commandLine = new ArrayList<>();

	/** Where the agent ran. */
	private String workingDirectory;

	/** The model asked for, or {@code null} for the CLI's default. */
	private String model;

	/** The thinking level asked for, or {@code null} for the model's default. */
	private String effort;

	/** The access mode, as people read it, or {@code null} when nothing chose one. */
	private String access;

	/** Whether the access mode was chosen in the conversation, over the profile's. */
	private boolean accessChosenHere;

	/** How the briefing reached the CLI, or {@code null} when there was no briefing or no way to give it. */
	private String briefingDelivery;

	/** The briefing file the launch wrote, or {@code null} when it wrote none. */
	private String briefingFile;

	/** A digest of the briefing as written, to tell it from one a later launch wrote. */
	private String briefingDigest;

	/** What the profile holds that this CLI did not take. */
	private List<String> notApplied = new ArrayList<>();

	/** The variables the profile set, by name. */
	private List<String> environmentNames = new ArrayList<>();

	/** The variables filled from secrets, by name. */
	private List<String> secretNames = new ArrayList<>();

	/** Creates an empty summary. */
	public LaunchSummary() {}

	/** A copy, for completing one launch's summary without touching the one it came from. */
	public LaunchSummary copy() {
		var copy = new LaunchSummary();
		copy.launchedAt = launchedAt;
		copy.cli = cli;
		copy.profileName = profileName;
		copy.profileRef = profileRef;
		copy.profileDigest = profileDigest;
		copy.commandLine = new ArrayList<>(commandLine);
		copy.workingDirectory = workingDirectory;
		copy.model = model;
		copy.effort = effort;
		copy.access = access;
		copy.accessChosenHere = accessChosenHere;
		copy.briefingDelivery = briefingDelivery;
		copy.briefingFile = briefingFile;
		copy.briefingDigest = briefingDigest;
		copy.notApplied = new ArrayList<>(notApplied);
		copy.environmentNames = new ArrayList<>(environmentNames);
		copy.secretNames = new ArrayList<>(secretNames);
		return copy;
	}

	/**
	 * A command line fit to keep and to show: an argument too long to read is replaced by
	 * a note of its length.
	 *
	 * @param launched the command as run
	 * @return the command as recorded
	 */
	public static List<String> shortened(List<String> launched) {
		return shortened(launched, java.util.Set.of());
	}

	/**
	 * A command line fit to keep and to show: the arguments that carry the briefing inline
	 * are replaced by a note of their length, whatever it is - the briefing stays in its
	 * file - and any other argument too long to read likewise.
	 *
	 * @param launched the command as run
	 * @param briefing the positions of the arguments that carry the briefing
	 * @return the command as recorded
	 */
	public static List<String> shortened(List<String> launched, java.util.Set<Integer> briefing) {
		var shown = new ArrayList<String>(launched.size());
		for (var i = 0; i < launched.size(); i++) {
			var argument = launched.get(i);
			var length = String.format("%,d", argument.length());
			shown.add(briefing.contains(i) ? "<" + length + " characters - the briefing, passed inline>"
					: argument.length() > LONGEST_ARGUMENT ? "<" + length + " characters>"
					: argument);
		}
		return shown;
	}

	/**
	 * A digest of some text, for telling whether it has changed.
	 *
	 * @param text the text
	 * @return its SHA-256, in hex
	 */
	public static String digest(String text) {
		try {
			var hash = java.security.MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hash);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
