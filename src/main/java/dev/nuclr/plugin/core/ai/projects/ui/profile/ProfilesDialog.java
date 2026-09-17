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

/** The profile manager window: the {@link ProfilesPanel} and a Close button. */
public final class ProfilesDialog {

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

		if (Dialogs.isHeadless()) {
			return;
		}
		var owner = parent != null ? SwingUtilities.getWindowAncestor(parent)
				: KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		var dialog = new JDialog(owner, "AI Profiles", Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

		var panel = new ProfilesPanel(store, new dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets(credentials));
		var close = new JButton("Close");
		close.addActionListener(event -> dialog.setVisible(false));
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		buttons.add(close);

		var root = new JPanel(new BorderLayout(0, 10));
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		root.add(panel, BorderLayout.CENTER);
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
