package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * The profile editor window.
 *
 * <p>Save checks everything first and points at the first problem instead of
 * closing. Saving itself is handed back to the caller, which reports its own
 * failures; if it does not succeed the window stays open with the edits intact.
 * Cancel, Escape and the close button all ask before discarding changes.
 */
public final class ProfileEditorDialog {

	/** Most problems listed at once; the rest are summarised. */
	private static final int PROBLEMS_SHOWN = 8;

	private final JDialog dialog;
	private final ProfileForm form;
	private final Profile baseline;
	private final Collection<String> otherNames;
	private final Predicate<Profile> saver;
	private boolean saved;
	/** Storing secrets keeps the interface responsive; a second Save meanwhile is ignored. */
	private boolean saving;

	private ProfileEditorDialog(Component parent, Profile profile, boolean isNew, Collection<String> otherNames,
			dev.nuclr.plugin.core.ai.projects.profile.SecretSession session, Predicate<Profile> saver) {

		this.otherNames = otherNames;
		this.saver = saver;
		this.form = new ProfileForm(profile, dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs.shared(), session);
		this.baseline = form.toProfile();

		var owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, isNew ? "New profile" : "Edit profile - " + profile.displayName(),
				Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				cancel();
			}

			@Override
			public void windowOpened(WindowEvent event) {
				form.nameField().requestFocusInWindow();
			}
		});

		var save = new JButton(isNew ? "Create" : "Save");
		save.setToolTipText("Save the profile (Ctrl+S)");
		save.addActionListener(event -> save());
		var cancel = new JButton("Cancel");
		cancel.addActionListener(event -> cancel());
		dialog.getRootPane().setDefaultButton(save);

		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		buttons.add(save);
		buttons.add(cancel);

		var note = new JLabel("* required.  Profiles are shared across projects; nothing uses them yet.");
		note.setEnabled(false);

		var footer = new JPanel(new BorderLayout());
		footer.add(note, BorderLayout.CENTER);
		footer.add(buttons, BorderLayout.EAST);

		var root = new JPanel(new BorderLayout(0, 10));
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		root.add(form, BorderLayout.CENTER);
		root.add(footer, BorderLayout.SOUTH);
		dialog.setContentPane(root);
		TextContextMenu.installTree(root);

		Dialogs.closeOnEscape(dialog, this::cancel);
		RecordEditorDialog.bind(dialog.getRootPane(),
				KeyStroke.getKeyStroke(KeyEvent.VK_S, Dialogs.menuShortcutMask()), "profile.save", this::save);

		dialog.pack();
		dialog.setMinimumSize(new java.awt.Dimension(640, 480));
		dialog.setLocationRelativeTo(owner);
	}

	/**
	 * Show the editor until the profile is saved or the user gives up.
	 *
	 * @param parent     component to centre on
	 * @param profile    the profile to edit, or a blank one to create
	 * @param isNew      whether this creates a profile
	 * @param otherNames the names of every other profile, for uniqueness
	 * @param session    holds the secrets entered while editing, for the saver to store
	 * @param saver      saves the edited profile; returns {@code true} on success,
	 *                   having told the user about any failure itself
	 * @return whether the profile was saved
	 */
	public static boolean edit(Component parent, Profile profile, boolean isNew, Collection<String> otherNames,
			dev.nuclr.plugin.core.ai.projects.profile.SecretSession session, Predicate<Profile> saver) {
		if (Dialogs.isHeadless()) {
			return false;
		}
		var editor = new ProfileEditorDialog(parent, profile, isNew, otherNames, session, saver);
		editor.dialog.setVisible(true);
		editor.dialog.dispose();
		return editor.saved;
	}

	private void save() {

		var inputProblems = form.inputProblems();
		if (!inputProblems.isEmpty()) {
			form.revealLimits();
			Dialogs.error(dialog, dialog.getTitle(), String.join("\n", inputProblems));
			return;
		}

		var profile = form.toProfile();
		var problems = ProfileValidator.validate(profile, otherNames);
		if (!problems.isEmpty()) {
			form.reveal(problems.getFirst());
			Dialogs.error(dialog, "Cannot save yet", describe(problems));
			return;
		}

		var needed = profile.getHarness().getMcpServers() == null ? List.<String>of()
				: profile.getHarness().getMcpServers().stream()
						.filter(server -> server != null && server.isEnabled()
								&& server.secrets().stream().anyMatch(secret -> secret.needsEntry()))
						.map(server -> server.getName())
						.toList();
		if (!needed.isEmpty() && !Dialogs.ask(dialog, dialog.getTitle(), "These MCP servers have secrets that are not "
				+ "entered on this machine: " + String.join(", ", needed) + ".\n\nThey cannot connect until the secrets "
				+ "are entered. Save anyway?")) {
			form.revealMcp();
			return;
		}

		if (saving) {
			return;
		}
		saving = true;
		try {
			if (saver.test(profile)) {
				saved = true;
				dialog.setVisible(false);
			}
		} finally {
			saving = false;
		}
	}

	private void cancel() {
		if (form.differsFrom(baseline)
				&& !Dialogs.choose(dialog, dialog.getTitle(), "Discard your changes to this profile?",
						"Discard", "Keep editing")) {
			return;
		}
		dialog.setVisible(false);
	}

	private static String describe(List<ProfileValidator.Problem> problems) {
		var lines = new ArrayList<String>();
		for (var index = 0; index < Math.min(PROBLEMS_SHOWN, problems.size()); index++) {
			var problem = problems.get(index);
			lines.add("- " + problem.message() + (problem.section() == null ? ""
					: "   [" + problem.section().title() + ", entry " + (problem.index() + 1) + "]"));
		}
		if (problems.size() > PROBLEMS_SHOWN) {
			lines.add("... and " + (problems.size() - PROBLEMS_SHOWN) + " more.");
		}
		return String.join("\n", lines);
	}
}
