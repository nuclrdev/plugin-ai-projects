package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.connector.McpSupport;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ListEditor;

/**
 * Add or edit one MCP server: a local command, or a remote server with its
 * authentication and headers.
 *
 * <p>Only the fields for the chosen kind are shown. Secrets go through
 * {@link SecretField}, so nothing typed reaches the credential store unless both
 * this dialog and the profile are saved.
 */
final class McpServerDialog {

	private final JDialog dialog;
	private final SecretSession session;
	private final AgentProvider provider;
	private final McpServerSpec original;

	private final JTextField name = new JTextField(28);
	private final JCheckBox enabled = new JCheckBox("Switched on");
	private final JRadioButton local = new JRadioButton("Local command");
	private final JRadioButton http = new JRadioButton("Remote (HTTP)");
	private final JRadioButton sse = new JRadioButton("Remote (SSE)");
	private final CardLayout cards = new CardLayout();
	private final JPanel card = new JPanel(cards);

	private final JTextField command = new JTextField(36);
	private final ListEditor arguments;
	private final NamedValuesEditor environment;

	private final JTextField url = new JTextField(36);
	private final JRadioButton noAuth = new JRadioButton("None");
	private final JRadioButton bearer = new JRadioButton("Bearer token");
	private final JRadioButton oauth = new JRadioButton("OAuth");
	private final SecretField token;
	private final WrappingNote authNote = new WrappingNote();
	private final JPanel tokenRow = new JPanel(new BorderLayout());
	private final NamedValuesEditor headers;
	private final WrappingNote transportNote = new WrappingNote();
	private final JLabel error = new JLabel(" ");

	private final String initial;
	private McpServerSpec result;

	private McpServerDialog(Component parent, McpServerSpec original, SecretSession session, AgentProvider provider) {

		this.session = session;
		this.provider = provider;
		this.original = original;
		var server = original == null ? McpServerSpec.remote("", McpServerSpec.HTTP, "") : original.copy();

		var owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, original == null ? "Add MCP server" : "Edit MCP server",
				Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				cancel();
			}
		});

		name.setText(nullToEmpty(server.getName()));
		RecordEditorDialog.placeholder(name, "e.g. github");
		enabled.setSelected(server.isEnabled());

		command.setText(nullToEmpty(server.getCommand()));
		RecordEditorDialog.placeholder(command, "e.g. npx");
		arguments = new ListEditor("One argument per line.", 4, server.getArgs());
		environment = new NamedValuesEditor("environment variable", false, server.getEnv(), server.getSecretEnv(),
				session);

		url.setText(nullToEmpty(server.getUrl()));
		RecordEditorDialog.placeholder(url, "https://mcp.example.com/mcp");
		token = new SecretField(session, server.getBearerToken());
		headers = new NamedValuesEditor("header", true, server.getHeaders(), server.getSecretHeaders(), session);

		var transports = new ButtonGroup();
		for (var button : new JRadioButton[] { local, http, sse }) {
			transports.add(button);
			button.addActionListener(event -> update());
		}
		switch (server.transportOrDefault()) {
			case McpServerSpec.HTTP -> http.setSelected(true);
			case McpServerSpec.SSE -> sse.setSelected(true);
			default -> local.setSelected(true);
		}
		var auths = new ButtonGroup();
		for (var button : new JRadioButton[] { noAuth, bearer, oauth }) {
			auths.add(button);
			button.addActionListener(event -> update());
		}
		switch (server.authOrDefault()) {
			case McpServerSpec.AUTH_BEARER -> bearer.setSelected(true);
			case McpServerSpec.AUTH_OAUTH -> oauth.setSelected(true);
			default -> noAuth.setSelected(true);
		}

		card.add(localCard(), "local");
		card.add(remoteCard(), "remote");

		var form = new JPanel(new GridBagLayout());
		var constraints = constraints();
		row(form, constraints, "Name", name);
		row(form, constraints, "Kind", buttons(local, http, sse));
		row(form, constraints, "", transportNote);
		constraints.gridy++;
		constraints.gridx = 0;
		constraints.gridwidth = 2;
		constraints.weighty = 1;
		constraints.fill = GridBagConstraints.BOTH;
		form.add(card, constraints);

		error.setForeground(RecordEditorDialog.errorColor());
		var ok = new JButton(original == null ? "Add" : "OK");
		ok.addActionListener(event -> ok());
		var cancel = new JButton("Cancel");
		cancel.addActionListener(event -> cancel());
		dialog.getRootPane().setDefaultButton(ok);
		var buttonRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		buttonRow.add(ok);
		buttonRow.add(cancel);
		var footer = new JPanel(new BorderLayout(8, 0));
		footer.add(enabled, BorderLayout.WEST);
		footer.add(error, BorderLayout.CENTER);
		footer.add(buttonRow, BorderLayout.EAST);

		var root = new JPanel(new BorderLayout(0, 10));
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		root.add(form, BorderLayout.CENTER);
		root.add(footer, BorderLayout.SOUTH);
		dialog.setContentPane(root);
		TextContextMenu.installTree(root);
		Dialogs.closeOnEscape(dialog, this::cancel);

		update();
		initial = snapshot();
		dialog.pack();
		dialog.setMinimumSize(new java.awt.Dimension(640, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
	}

	/**
	 * Show the dialog.
	 *
	 * @param parent   component to centre on
	 * @param original the server to edit, or {@code null} to add one
	 * @param session  the editing session's secrets
	 * @param provider the profile's provider, for provider-specific notes; may be {@code null}
	 * @return the edited server, or {@code null} when cancelled
	 */
	static McpServerSpec edit(Component parent, McpServerSpec original, SecretSession session, AgentProvider provider) {
		if (Dialogs.isHeadless()) {
			return null;
		}
		var editor = new McpServerDialog(parent, original, session, provider);
		editor.dialog.setVisible(true);
		editor.dialog.dispose();
		return editor.result;
	}

	private JPanel localCard() {
		var panel = new JPanel(new GridBagLayout());
		var constraints = constraints();
		row(panel, constraints, "Command *", command);
		row(panel, constraints, "Arguments", arguments);
		row(panel, constraints, "Environment", environment);
		return panel;
	}

	private JPanel remoteCard() {
		tokenRow.add(token, BorderLayout.CENTER);
		var panel = new JPanel(new GridBagLayout());
		var constraints = constraints();
		row(panel, constraints, "URL *", url);
		row(panel, constraints, "Authentication", buttons(noAuth, bearer, oauth));
		row(panel, constraints, "", tokenRow);
		row(panel, constraints, "", authNote);
		row(panel, constraints, "Headers", headers);
		return panel;
	}

	private void update() {
		cards.show(card, local.isSelected() ? "local" : "remote");
		tokenRow.setVisible(bearer.isSelected());
		if (oauth.isSelected()) {
			authNote.setText("OAuth sign-in is handled by the CLI, which keeps its own tokens: in Claude Code use /mcp; "
					+ "for Codex run: codex mcp login " + (name.getText().isBlank() ? "<name>" : name.getText().strip()));
		} else if (bearer.isSelected()) {
			authNote.setText("Sent as \"Authorization: Bearer ...\".");
		} else {
			authNote.setText(null);
		}
		if (sse.isSelected() && provider == AgentProvider.CODEX) {
			transportNote.setText("Codex does not support SSE; use HTTP if the server offers it.");
		} else if (!local.isSelected() && provider == AgentProvider.PI) {
			transportNote.setText("Pi has no MCP support.");
		} else {
			transportNote.setText(null);
		}
		dialog.pack();
	}

	/** The server as entered. Secrets typed here are handed to the session. */
	private McpServerSpec build() {
		McpServerSpec server;
		if (local.isSelected()) {
			server = McpServerSpec.of(name.getText().strip(), command.getText().strip(), arguments.values());
			var env = environment.values();
			server.setEnv(env.isEmpty() ? new java.util.LinkedHashMap<>() : env);
			var secretEnv = environment.secrets();
			server.setSecretEnv(secretEnv.isEmpty() ? null : secretEnv);
		} else {
			server = McpServerSpec.remote(name.getText().strip(), http.isSelected() ? McpServerSpec.HTTP : McpServerSpec.SSE,
					url.getText().strip());
			server.setAuth(bearer.isSelected() ? McpServerSpec.AUTH_BEARER
					: oauth.isSelected() ? McpServerSpec.AUTH_OAUTH : null);
			if (bearer.isSelected()) {
				server.setBearerToken(token.value());
			}
			var fixed = headers.values();
			server.setHeaders(fixed.isEmpty() ? null : fixed);
			var secret = headers.secrets();
			server.setSecretHeaders(secret.isEmpty() ? null : secret);
		}
		server.setEnabled(enabled.isSelected());
		return server;
	}

	/** Everything entered, as text, without handing any secret to the session. */
	private String snapshot() {
		var parts = new ArrayList<String>();
		parts.add(name.getText());
		parts.add(String.valueOf(enabled.isSelected()));
		parts.add(local.isSelected() ? "local" : http.isSelected() ? "http" : "sse");
		parts.add(command.getText());
		parts.add(String.join("\n", arguments.values()));
		parts.add(Json.toJson(environment.values()) + Json.toJson(environment.secrets()));
		parts.add(url.getText());
		parts.add(noAuth.isSelected() ? "none" : bearer.isSelected() ? "bearer" : "oauth");
		parts.add(token.describe());
		parts.add(Json.toJson(headers.values()) + Json.toJson(headers.secrets()));
		return String.join(" ", parts);
	}

	private void ok() {
		var problems = new ArrayList<String>();
		if (name.getText().isBlank()) {
			problems.add("Enter a name.");
		}
		if (local.isSelected() && command.getText().isBlank()) {
			problems.add("Enter the command that starts the server.");
		}
		if (!local.isSelected() && !McpSupport.isHttpUrl(url.getText())) {
			problems.add("Enter an http:// or https:// URL.");
		}
		if (!local.isSelected() && bearer.isSelected() && token.problem() != null) {
			problems.add("Bearer token: " + token.problem());
		}
		if (!problems.isEmpty()) {
			error.setText(problems.getFirst());
			return;
		}
		result = build();
		// Checks shared with the connectors, now that the server exists.
		var shared = McpSupport.problems(result);
		if (!shared.isEmpty()) {
			error.setText(shared.getFirst().replaceFirst("^MCP servers: ", ""));
			result = null;
			return;
		}
		dialog.setVisible(false);
	}

	private void cancel() {
		if (!snapshot().equals(initial)
				&& !Dialogs.confirm(dialog, dialog.getTitle(), "Discard the changes to this server?")) {
			return;
		}
		result = null;
		dialog.setVisible(false);
	}

	private static JPanel buttons(JRadioButton... buttons) {
		var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		for (var index = 0; index < buttons.length; index++) {
			if (index > 0) {
				row.add(Box.createHorizontalStrut(12));
			}
			row.add(buttons[index]);
		}
		return row;
	}

	private static GridBagConstraints constraints() {
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.FIRST_LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.gridy = -1;
		return constraints;
	}

	private static void row(JPanel form, GridBagConstraints constraints, String label, JComponent field) {
		constraints.gridy++;
		constraints.gridx = 0;
		constraints.gridwidth = 1;
		constraints.weightx = 0;
		constraints.weighty = 0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		// A row without a label is a note or an optional part: adding no label lets the grid
		// bag give it no space at all while it is hidden.
		if (!label.isEmpty()) {
			var text = new JLabel(label);
			text.setPreferredSize(new java.awt.Dimension(
					text.getFontMetrics(text.getFont()).stringWidth("Authentication") + 12,
					text.getFontMetrics(text.getFont()).getHeight()));
			form.add(text, constraints);
		}
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(field, constraints);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
