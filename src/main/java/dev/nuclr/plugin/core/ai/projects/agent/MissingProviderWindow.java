package dev.nuclr.plugin.core.ai.projects.agent;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * The window shown when an agent names a window kind nothing provides.
 *
 * <p>It happens for good reasons - a project shared by a colleague who has a
 * provider you have not installed, or a kind that has been renamed - and the
 * only wrong answer is to drop the agent. Its definition is untouched, its
 * frame appears in the usual place, and the frame says what is missing.
 */
public final class MissingProviderWindow implements AgentWindow {

	private final JPanel root = new JPanel(new BorderLayout());

	/**
	 * Build the placeholder.
	 *
	 * @param agentName the agent's display name
	 * @param kind      the window kind that could not be resolved
	 */
	public MissingProviderWindow(String agentName, String kind) {
		this(agentName, kind, "No window provider is installed for this kind.");
	}

	/** Build a placeholder with a precise availability explanation. */
	public MissingProviderWindow(String agentName, String kind, String reason) {
		var message = new JLabel("<html><b>" + escape(agentName) + "</b><br><br>"
				+ escape(reason) + "<br>Kind: <code>" + escape(kind) + "</code>.<br>"
				+ "The agent's definition has been left as it is; change its kind, or install the "
				+ "provider, and reopen the project.</html>");
		message.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		message.setVerticalAlignment(JLabel.TOP);
		root.add(message, BorderLayout.CENTER);
	}

	@Override
	public JComponent component() {
		return root;
	}

	@Override
	public AgentStatus status() {
		return AgentStatus.STOPPED;
	}

	@Override
	public String sessionSummary() {
		return "No provider for this window kind.";
	}

	@Override
	public void start() {
		// Nothing to start.
	}

	@Override
	public void stop() {
		// Nothing to stop.
	}

	@Override
	public void close() {
		// Nothing to release.
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
