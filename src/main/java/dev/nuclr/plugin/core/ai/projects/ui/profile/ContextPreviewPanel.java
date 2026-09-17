package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.ContextDelivery;
import dev.nuclr.plugin.core.ai.projects.connector.GitCheckouts;
import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.connector.LaunchPlan;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * The "What agents receive" tab: the briefing a profile's context becomes, how it
 * reaches the chosen provider, and how its skills are loaded.
 *
 * <p>Built from the profile as currently edited, with the same code a launch uses,
 * so the two cannot disagree. Nothing is fetched and nothing in an agent's working
 * folder is read: git sources show the copy from the last start, if there is one,
 * and relative paths are only resolved once an agent starts somewhere. Built off the
 * event thread, since linked files may be large or slow to reach.
 */
final class ContextPreviewPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Stands in for an agent's runtime folder, which only exists once it starts. */
	private static final Path RUNTIME = Path.of(System.getProperty("java.io.tmpdir"), "nuclr-profile-preview");

	private final Supplier<Profile> profile;
	private final GitSources git;
	private final WrappingNote summary = new WrappingNote();
	private final WrappingNote warning = new WrappingNote();
	private final JTextArea briefing = new JTextArea();
	private int generation;

	ContextPreviewPanel(Supplier<Profile> profile) {
		this(profile, GitCheckouts.inCommanderHome(ProjectPaths.defaultCommanderHome()).cachedOnly());
	}

	ContextPreviewPanel(Supplier<Profile> profile, GitSources git) {
		super(new BorderLayout(0, 8));
		this.profile = profile;
		this.git = git;

		var intro = new WrappingNote();
		intro.setText("What an agent started from this profile is told, as the profile stands now. The project's own "
				+ "context is added when it starts. Git sources show the copy from the last start, and relative paths "
				+ "are found in the agent's working folder.");
		warning.setWarning();
		briefing.setEditable(false);
		briefing.setLineWrap(true);
		briefing.setWrapStyleWord(true);
		briefing.setFont(new Font(Font.MONOSPACED, Font.PLAIN, briefing.getFont().getSize()));
		TextContextMenu.install(briefing);

		var refresh = Glyphs.decorate(new JButton(), Glyphs.REFRESH, "Refresh");
		refresh.setToolTipText("Build the preview again from the profile as it is now");
		refresh.addActionListener(event -> refresh());
		var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		buttons.add(refresh);

		var header = new JPanel(new BorderLayout(0, 6));
		header.add(intro, BorderLayout.NORTH);
		header.add(summary, BorderLayout.CENTER);
		var south = new JPanel(new BorderLayout(0, 6));
		south.add(warning, BorderLayout.NORTH);
		south.add(buttons, BorderLayout.SOUTH);
		header.add(south, BorderLayout.SOUTH);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(header, BorderLayout.NORTH);
		add(new JScrollPane(briefing), BorderLayout.CENTER);
	}

	/** Build the preview again, from the profile as edited now. On the event thread. */
	void refresh() {
		var edited = profile.get();
		var wanted = ++generation;
		summary.setText("Building the preview ...");
		warning.setText(null);
		Thread.ofVirtual().name("nuclr-profile-preview").start(() -> {
			var preview = build(edited, git);
			SwingUtilities.invokeLater(() -> {
				if (wanted != generation) {
					// A newer refresh started meanwhile; its result is the one to show.
					return;
				}
				summary.setText(preview.summary());
				warning.setText(preview.warning());
				briefing.setText(preview.briefing());
				briefing.setCaretPosition(0);
			});
		});
	}

	/** The text shown, for the tests. */
	String shownBriefing() {
		return briefing.getText();
	}

	/**
	 * What the preview shows.
	 *
	 * @param summary  how the context reaches the provider
	 * @param warning  what the user should know, or {@code null}
	 * @param briefing the briefing text
	 */
	record Preview(String summary, String warning, String briefing) {
	}

	/** Build a preview. Off the event thread. */
	static Preview build(Profile edited, GitSources git) {
		final LaunchPlan plan;
		try {
			plan = LaunchPlan.of(edited, RUNTIME, System.getProperty("user.home"), null, git);
		} catch (IllegalArgumentException e) {
			return new Preview(e.getMessage(), null, "");
		} catch (RuntimeException e) {
			return new Preview("The preview could not be built: " + e.getMessage(), null, "");
		}
		var provider = plan.provider();
		var connector = provider.connector();
		var lines = new ArrayList<String>();
		var text = plan.briefing();

		if (text.isEmpty()) {
			lines.add("Instructions: nothing from this profile.");
		} else {
			var delivery = ContextDelivery.plan(provider.defaultExecutable(), Path.of(provider.defaultExecutable()),
					RUNTIME.resolve("briefing.md"), text, Map.of());
			lines.add("Briefing: " + String.format("%,d", text.length()) + " characters. "
					+ (delivery.delivered() ? delivery.description().replace(RUNTIME.toString(), "the agent's runtime folder")
							: provider.displayName() + " has no way to be given it."));
		}
		var skillFlags = skillArguments(plan.arguments());
		lines.add("Skills: " + connector.skillsMeaning()
				+ (skillFlags.isEmpty() ? "" : " Passed as " + String.join(" ", skillFlags)
						.replace(RUNTIME.toString(), "<runtime folder>") + "."));
		if (!plan.notices().isEmpty()) {
			lines.add("Not applied: " + String.join("; ", plan.notices()) + ".");
		}

		String warning = null;
		if (text.length() > ContextDelivery.INLINE_LIMIT && !"pi".equals(provider.id())) {
			warning = "The briefing is longer than a command line can carry (" + String.format("%,d", ContextDelivery.INLINE_LIMIT)
					+ " characters), so agents are only told to read the briefing file. Consider moving long documents "
					+ "to Knowledge, which is pointed to rather than included.";
		}
		return new Preview(String.join("\n", lines), warning, text);
	}

	/** The arguments that load skills, to show on their own. */
	private static List<String> skillArguments(List<String> arguments) {
		var shown = new ArrayList<String>();
		for (var index = 0; index + 1 < arguments.size(); index++) {
			if (arguments.get(index).equals("--skill") || arguments.get(index).equals("--plugin-dir")) {
				shown.add(arguments.get(index));
				shown.add(arguments.get(index + 1));
				index++;
			}
		}
		return shown;
	}
}
