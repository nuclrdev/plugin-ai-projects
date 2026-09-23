package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.runtime.LaunchSummary;
import lombok.Getter;

/**
 * What an agent was given when it was last started, as a tree of things to look at:
 * the launch, the briefing and each of its parts, the variables, and what the profile
 * holds that the CLI did not take.
 *
 * <p>Built from what the launch recorded, not from the profile as it is now: a running
 * agent has what it was started with, and a profile edited since has not reached it.
 * The two are compared, and the view says when they differ.
 *
 * <p>No Swing here, so what is shown can be tested without a display.
 */
final class AgentContextView {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());

	/** One thing in the tree: what it is called, what it shows, and whether it wants attention. */
	@Getter
	static final class Entry {

		private final String label;
		private final String content;
		private final boolean warning;
		private final List<Entry> children = new ArrayList<>();

		Entry(String label, String content, boolean warning) {
			this.label = label;
			this.content = content;
			this.warning = warning;
		}

		Entry add(Entry child) {
			children.add(child);
			return this;
		}

		/** Whether this or anything under it wants attention. */
		boolean needsAttention() {
			return warning || children.stream().anyMatch(Entry::needsAttention);
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** The profile as it is now, as far as it could be read. */
	enum ProfileNow {
		/** Read, and the same as at the launch. */
		SAME,
		/** Read, and changed since the launch. */
		CHANGED,
		/** No longer there. */
		GONE,
		/** Not looked at: the agent started without one, or it could not be read. */
		UNKNOWN
	}

	private final List<Entry> entries;
	private final String banner;
	private final String briefingFile;

	private AgentContextView(List<Entry> entries, String banner, String briefingFile) {
		this.entries = List.copyOf(entries);
		this.banner = banner;
		this.briefingFile = briefingFile;
	}

	/** The top-level entries, in order. */
	List<Entry> entries() {
		return entries;
	}

	/** What to say above the tree, or {@code null}: a launch that no longer matches its profile. */
	String banner() {
		return banner;
	}

	/** The briefing file the launch wrote, or {@code null} when it wrote none or there was no launch. */
	String briefingFile() {
		return briefingFile;
	}

	/**
	 * Build the view.
	 *
	 * @param launch   what the last launch recorded, or {@code null} when none has been
	 * @param briefing the briefing file's text now, or {@code null} when it is not there
	 * @param profile  how the profile compares now
	 * @return the view
	 */
	static AgentContextView of(LaunchSummary launch, String briefing, ProfileNow profile) {
		if (launch == null) {
			return new AgentContextView(List.of(new Entry("Not started yet",
					"This agent has not been started since its context began to be recorded. Start it, and this "
							+ "shows what it was given: its profile's instructions, skills and knowledge, how they "
							+ "reached the CLI, and what the CLI did not take.",
					false)), null, null);
		}
		var entries = new ArrayList<Entry>();
		entries.add(launchEntry(launch));
		entries.add(briefingEntry(launch, briefing));
		if (launch.getProfileName() != null) {
			entries.add(environmentEntry(launch));
			entries.add(notAppliedEntry(launch));
		}
		String banner = switch (profile) {
			case CHANGED -> "The profile \"" + launch.getProfileName() + "\" has changed since this agent started. "
					+ "Restart the agent to give it the changes.";
			case GONE -> "The profile \"" + launch.getProfileName() + "\" no longer exists. "
					+ "This agent keeps what it was started with until it is restarted.";
			case SAME, UNKNOWN -> null;
		};
		return new AgentContextView(entries, banner, launch.getBriefingFile());
	}

	private static Entry launchEntry(LaunchSummary launch) {
		var text = new StringBuilder();
		line(text, "Started", launch.getLaunchedAt() == null ? "unknown" : WHEN.format(launch.getLaunchedAt()));
		line(text, "CLI", launch.getCli());
		line(text, "Profile", launch.getProfileName() == null ? "none - the CLI's own settings" : launch.getProfileName());
		line(text, "Folder", launch.getWorkingDirectory());
		line(text, "Model", launch.getModel() == null ? "the CLI's default" : launch.getModel());
		line(text, "Thinking", launch.getEffort() == null ? "the model's default" : launch.getEffort());
		line(text, "Access", launch.getAccess() == null ? "the CLI's own settings"
				: launch.getAccess() + (launch.isAccessChosenHere() ? " (chosen in the conversation)" : ""));
		return new Entry("Launch", text.toString().stripTrailing(), false)
				.add(new Entry("Command line", String.join("\n", launch.getCommandLine()), false));
	}

	private static void line(StringBuilder text, String name, String value) {
		text.append(String.format("%-10s", name)).append(value == null ? "" : value).append('\n');
	}

	private static Entry briefingEntry(LaunchSummary launch, String briefing) {
		// A chat window briefs even an agent without a profile, on how to use the window.
		if (launch.getProfileName() == null && launch.getBriefingFile() == null) {
			return new Entry("Briefing", "Started without a profile, so Nuclr gave " + launch.getCli()
					+ " nothing of its own. It works from its own settings and the files it reads itself -"
					+ " CLAUDE.md, AGENTS.md and the like.", false);
		}
		if (launch.getBriefingFile() == null) {
			return new Entry("Briefing", "The profile holds no instructions, skills or knowledge, so there was"
					+ " no briefing to give.", false);
		}
		var how = launch.getBriefingDelivery() == null
				? launch.getCli() + " has no way to be given a briefing, so it was written but not given."
				: launch.getBriefingDelivery() + ".";
		if (briefing == null) {
			return new Entry("Briefing", how + "\n\nThe briefing file is gone: " + launch.getBriefingFile(), true);
		}
		var changed = !LaunchSummary.digest(briefing).equals(launch.getBriefingDigest());
		var head = how + "\nFile: " + launch.getBriefingFile()
				+ (changed ? "\n\nThe file has been written again since this launch; what is shown is the file now." : "");
		var entry = new Entry("Briefing", head + "\n\n" + briefing.strip(), changed || launch.getBriefingDelivery() == null);
		for (var section : sections(briefing, "## ")) {
			var node = new Entry(section.getTitle(), section.getBody(), "Not found".equals(section.getTitle()));
			if ("Instructions".equals(section.getTitle())) {
				for (var instruction : sections(section.getBody(), "### ")) {
					node.add(new Entry(instruction.getTitle(), instruction.getBody(), false));
				}
			}
			entry.add(node);
		}
		return entry;
	}

	private static Entry environmentEntry(LaunchSummary launch) {
		if (launch.getEnvironmentNames().isEmpty() && launch.getSecretNames().isEmpty()) {
			return new Entry("Environment", "The profile sets no variables.", false);
		}
		var text = new StringBuilder("Variables the profile set, by name; values are not shown.\n\n");
		launch.getEnvironmentNames().forEach(name -> text.append(name).append('\n'));
		launch.getSecretNames().forEach(name -> text.append(name).append("  (from a secret)\n"));
		return new Entry("Environment", text.toString().stripTrailing(), false);
	}

	private static Entry notAppliedEntry(LaunchSummary launch) {
		if (launch.getNotApplied().isEmpty()) {
			return new Entry("Not applied", "Everything in the profile was applied.", false);
		}
		return new Entry("Not applied", "The profile holds these, and " + launch.getCli() + " did not take them:\n\n- "
				+ String.join("\n- ", launch.getNotApplied()), true);
	}

	/** A heading and what follows it, up to the next heading of the same level. */
	@Getter
	@lombok.AllArgsConstructor
	private static final class Section {
		private final String title;
		private final String body;
	}

	/** The sections of Markdown under headings that start with a marker, such as {@code "## "}. */
	private static List<Section> sections(String markdown, String marker) {
		var sections = new ArrayList<Section>();
		String title = null;
		var body = new StringBuilder();
		for (var line : markdown.split("\n", -1)) {
			if (line.startsWith(marker)) {
				if (title != null) {
					sections.add(new Section(title, body.toString().strip()));
				}
				title = line.substring(marker.length()).strip();
				body.setLength(0);
			} else if (title != null) {
				body.append(line).append('\n');
			}
		}
		if (title != null) {
			sections.add(new Section(title, body.toString().strip()));
		}
		return sections;
	}
}
