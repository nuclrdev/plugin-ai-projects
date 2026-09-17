package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * Where one secret comes from, and the secret itself when it is typed.
 *
 * <p>A stored secret is never read back: once saved it shows as a row of dots
 * with Replace and Clear. A newly typed one is held by the {@link SecretSession}
 * until the profile is saved, so a cancelled edit leaves nothing in the OS
 * credential store. When there is no store, only an environment variable is
 * offered.
 */
final class SecretField extends JPanel {

	private static final long serialVersionUID = 1L;

	private final SecretSession session;
	private final JRadioButton stored = new JRadioButton("Stored secret");
	private final JRadioButton environment = new JRadioButton("Environment variable");
	private final CardLayout cards = new CardLayout();
	private final JPanel card = new JPanel(cards);
	private final JPasswordField password = new JPasswordField(28);
	private final JLabel savedLabel = new JLabel();
	private final JTextField variable = new JTextField(28);
	private final WrappingNote note = new WrappingNote();

	private String key;
	private boolean replacing;

	/**
	 * Build the field.
	 *
	 * @param session the editing session's secrets
	 * @param initial the reference as it is, or {@code null} for a new secret
	 */
	SecretField(SecretSession session, McpSecret initial) {

		super(new BorderLayout(0, 4));
		this.session = session;
		var start = initial == null ? McpSecret.stored(null) : initial.copy();
		key = start.getKey();

		var group = new ButtonGroup();
		group.add(stored);
		group.add(environment);
		var sources = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		sources.add(stored);
		sources.add(Box.createHorizontalStrut(12));
		sources.add(environment);

		RecordEditorDialog.placeholder(password, "Paste or type the secret");
		RecordEditorDialog.placeholder(variable, "e.g. GITHUB_TOKEN");
		variable.setText(start.getVariable() == null ? "" : start.getVariable());

		var replace = new JButton("Replace...");
		replace.addActionListener(event -> {
			replacing = true;
			update();
			password.requestFocusInWindow();
		});
		var clear = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Clear");
		clear.setToolTipText("Forget this secret; it is removed from the credential store when the profile is saved");
		clear.addActionListener(event -> {
			// Only this field forgets the key. The session is left alone: the dialog may still
			// be cancelled, and the server as it was still refers to that secret.
			key = null;
			replacing = false;
			password.setText("");
			update();
		});
		var savedRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		savedRow.add(savedLabel);
		savedRow.add(replace);
		savedRow.add(clear);

		var cancelReplace = new JButton("Keep the saved one");
		cancelReplace.addActionListener(event -> {
			replacing = false;
			password.setText("");
			update();
		});
		var entryRow = new JPanel(new BorderLayout(6, 0));
		entryRow.add(password, BorderLayout.CENTER);
		entryRow.add(cancelReplace, BorderLayout.EAST);
		cancelReplace.setName("cancelReplace");

		card.add(savedRow, "saved");
		card.add(entryRow, "entry");
		card.add(variable, "environment");

		add(sources, BorderLayout.NORTH);
		add(card, BorderLayout.CENTER);
		add(note, BorderLayout.SOUTH);
		setBorder(BorderFactory.createEmptyBorder());

		stored.addActionListener(event -> update());
		environment.addActionListener(event -> update());
		if (!session.available()) {
			stored.setEnabled(false);
			environment.setSelected(true);
		} else {
			(start.fromEnvironment() ? environment : stored).setSelected(true);
		}
		update();
	}

	/**
	 * The reference as entered. A typed secret is handed to the session here, so
	 * call this once, when the dialog is confirmed.
	 *
	 * @return the reference
	 */
	McpSecret value() {
		if (environment.isSelected()) {
			return McpSecret.environment(variable.getText().strip());
		}
		var typed = password.getPassword();
		try {
			if (typed.length > 0) {
				// A new key every time; a secret staged earlier and no longer referred to is simply
				// never written, so nothing here has to be undone if an outer dialog is cancelled.
				key = session.stage(new String(typed));
				password.setText("");
				replacing = false;
			}
		} finally {
			Arrays.fill(typed, '\0');
		}
		return McpSecret.stored(key);
	}

	/** What {@link #value()} would describe, without handing anything to the session. */
	String describe() {
		if (environment.isSelected()) {
			return "env:" + variable.getText().strip();
		}
		return password.getPassword().length > 0 ? "typed:" + System.identityHashCode(password) : "stored:" + key;
	}

	/** A problem to show, or {@code null}: a stored secret with nothing entered, or no variable. */
	String problem() {
		if (environment.isSelected()) {
			var name = variable.getText().strip();
			return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? null : "Enter the environment variable's name.";
		}
		return key == null && password.getPassword().length == 0 ? "Enter the secret." : null;
	}

	private void update() {
		if (environment.isSelected()) {
			cards.show(card, "environment");
			note.setText(session.available() ? "Read from this environment variable when the agent starts."
					: "There is no credential store on this machine, so the secret is read from an environment "
							+ "variable when the agent starts.");
			return;
		}
		var hasKey = key != null;
		if (hasKey && !replacing) {
			cards.show(card, "saved");
			savedLabel.setText(session.isStaged(key) ? "••••••  entered, stored when the profile is saved"
					: "••••••  stored");
			note.setText("Kept in the OS credential store. Only a reference is written into the profile.");
		} else {
			cards.show(card, "entry");
			for (var component : ((JPanel) card.getComponent(1)).getComponents()) {
				if ("cancelReplace".equals(component.getName())) {
					component.setVisible(hasKey);
				}
			}
			note.setText("Stored in the OS credential store when the profile is saved; never written into the profile.");
		}
	}
}
