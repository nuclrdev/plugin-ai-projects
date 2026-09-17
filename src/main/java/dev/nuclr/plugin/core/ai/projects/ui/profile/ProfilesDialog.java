package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * The profile manager window: the user's library, and when a project is open, the
 * project's own profiles beside it, each a {@link ProfilesPanel}, with a Close button.
 *
 * <p>Where a profile is kept is chosen by the tab it is created in, and can be changed
 * afterwards by copying it to the other. The tab last used is shown first next time.
 */
public final class ProfilesDialog {

	/** The tab last shown, so the place someone keeps choosing is where they start. */
	private static volatile int lastTab;

	private ProfilesDialog() {
	}

	/**
	 * Show the profile manager until the user closes it.
	 *
	 * @param parent component to centre on, or {@code null} for the active window
	 * @param store  where the profiles are kept
	 */
	public static void show(Component parent, ProfileStore store) {
		show(parent, store, null);
	}

	/**
	 * Show the profile manager, keeping secrets in the host's credential store.
	 *
	 * @param parent      component to centre on, or {@code null} for the active window
	 * @param store       where the profiles are kept
	 * @param credentials the host's credential store, or {@code null} when there is none
	 */
	public static void show(Component parent, ProfileStore store, dev.nuclr.platform.NuclrCredentialStore credentials) {
		show(parent, null, null, store, credentials);
	}

	/**
	 * Show the profile manager for an open project: its profiles and the user's library.
	 *
	 * @param parent      component to centre on, or {@code null} for the active window
	 * @param project     the project's profiles, or {@code null} when no project is open
	 * @param projectName the project's name, for its tab
	 * @param library     the user's library
	 * @param credentials the host's credential store, or {@code null} when there is none
	 */
	public static void show(Component parent, ProfileStore project, String projectName, ProfileStore library,
			dev.nuclr.platform.NuclrCredentialStore credentials) {

		if (Dialogs.isHeadless()) {
			return;
		}
		var owner = parent != null ? SwingUtilities.getWindowAncestor(parent)
				: KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		var dialog = new JDialog(owner, "AI Profiles", Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

		var secrets = new dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets(credentials);
		var libraryPanel = new ProfilesPanel(library, secrets);
		final ProfilesPanel panel;
		final java.awt.Component content;
		if (project == null) {
			panel = libraryPanel;
			content = libraryPanel;
		} else {
			var projectPanel = new ProfilesPanel(project, secrets);
			projectPanel.setCopyTarget("Copy to my library",
					"Copy the selected profiles to your library, to use them in other projects", library,
					libraryPanel::refresh);
			libraryPanel.setCopyTarget("Copy to project",
					"Copy the selected profiles into this project, so everyone who opens it has them", project,
					projectPanel::refresh);
			var tabs = new javax.swing.JTabbedPane();
			tabs.addTab("This project" + (projectName == null ? "" : " - " + projectName),
					dev.nuclr.plugin.core.ai.projects.ui.Glyphs.icon(dev.nuclr.plugin.core.ai.projects.ui.Glyphs.PROJECT),
					projectPanel);
			tabs.setToolTipTextAt(0, "Kept in the project, so everyone who opens it has them");
			tabs.addTab("My library", dev.nuclr.plugin.core.ai.projects.ui.Glyphs.icon(
					dev.nuclr.plugin.core.ai.projects.ui.Glyphs.PROFILE), libraryPanel);
			tabs.setToolTipTextAt(1, "Kept on this machine, and usable in every project");
			tabs.setSelectedIndex(Math.min(lastTab, 1));
			tabs.addChangeListener(event -> lastTab = tabs.getSelectedIndex());
			panel = tabs.getSelectedIndex() == 0 ? projectPanel : libraryPanel;
			content = tabs;
		}
		var close = new JButton("Close");
		close.addActionListener(event -> dialog.setVisible(false));
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		buttons.add(close);

		var root = new JPanel(new BorderLayout(0, 10));
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		root.add(content, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(root);

		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent event) {
				panel.focusDefault();
			}
		});
		Dialogs.closeOnEscape(dialog);
		TextContextMenu.installTree(root);
		dialog.setSize(new Dimension(860, 520));
		dialog.setMinimumSize(new Dimension(560, 360));
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
		dialog.dispose();
	}
}
