package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * The Commands tab: shell commands that run without asking, and ones that never
 * run, written as command prefixes - {@code git push} covers every
 * {@code git push ...}.
 *
 * <p>Unlike tool names, a command is the same whatever the provider, so the
 * lists are kept when the provider changes; the provider only decides whether
 * and how they are passed, which each list shows. A provider that cannot take
 * command rules says so above the lists, and the profile does not validate
 * until they are emptied.
 */
final class CommandListsPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Common prefixes, offered in the inputs. */
	private static final List<String> SUGGESTIONS = List.of("git status", "git diff", "git log", "git commit",
			"git push", "git reset --hard", "npm test", "npm install", "mvn test", "gradle test", "docker",
			"rm -rf", "curl", "ssh");

	private final NameListEditor allowed;
	private final NameListEditor blocked;
	private final WrappingNote notice = new WrappingNote();
	private final List<Runnable> listeners = new ArrayList<>();

	private AgentProvider provider;

	CommandListsPanel(List<String> allowedCommands, List<String> blockedCommands, AgentProvider provider) {

		super(new BorderLayout(0, 8));
		this.provider = provider;
		allowed = new NameListEditor("Run without asking", "Add a command", allowedCommands, "git reset --hard",
				new CommandSource(true));
		blocked = new NameListEditor("Blocked", "Add a blocked command", blockedCommands, "git reset --hard",
				new CommandSource(false));
		allowed.addChangeListener(this::changed);
		blocked.addChangeListener(this::changed);

		var intro = new WrappingNote();
		intro.setText("Shell commands, as a command and its first arguments: git push covers git push origin main, "
				+ "but not git pull. To stop commands altogether, block the shell tool on the Tools tab.");
		notice.setWarning();

		var header = new JPanel(new BorderLayout(0, 8));
		header.add(intro, BorderLayout.NORTH);
		header.add(notice, BorderLayout.SOUTH);

		var lists = new JPanel(new GridLayout(1, 0, 12, 0));
		lists.add(allowed);
		lists.add(blocked);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(header, BorderLayout.NORTH);
		add(lists, BorderLayout.CENTER);
		refresh();
	}

	/** The commands that run without asking, in order. */
	List<String> allowedCommands() {
		return allowed.values();
	}

	/** The commands that never run, in order. */
	List<String> blockedCommands() {
		return blocked.values();
	}

	/** How many entries there are, for the tab title. */
	int count() {
		return allowed.count() + blocked.count();
	}

	/**
	 * Be told whenever either list changes.
	 *
	 * @param listener run after every change
	 */
	void addChangeListener(Runnable listener) {
		listeners.add(listener);
	}

	/**
	 * The provider changed: say how the same lists are passed now.
	 *
	 * @param next the provider now chosen, or {@code null}
	 */
	void setProvider(AgentProvider next) {
		if (next != provider) {
			provider = next;
			refresh();
		}
	}

	private void changed() {
		updateNotice();
		listeners.forEach(Runnable::run);
	}

	private void refresh() {
		allowed.refresh();
		blocked.refresh();
		updateNotice();
	}

	private void updateNotice() {
		var connector = connector();
		notice.setText(connector != null && !connector.supportsCommandRules() && count() > 0
				? provider.displayName() + " cannot take these lists: remove them, or choose Claude Code."
				: null);
	}

	private AgentConnector connector() {
		return provider == null ? null : provider.connector();
	}

	/** The allowed or blocked commands, as the provider chosen now passes them. */
	private final class CommandSource implements NameListEditor.Source {

		private final boolean allowList;

		CommandSource(boolean allowList) {
			this.allowList = allowList;
		}

		@Override
		public boolean enabled() {
			return true;
		}

		@Override
		public List<String> suggestions() {
			return SUGGESTIONS;
		}

		@Override
		public String meaning() {
			var connector = connector();
			if (connector == null) {
				return "Choose a provider on Model / runtime to see how these are passed.";
			}
			return allowList ? connector.allowedCommandsMeaning() : connector.blockedCommandsMeaning();
		}

		@Override
		public String describe(String name) {
			return name;
		}

		@Override
		public boolean known(String name) {
			return true;
		}

		@Override
		public String flags(List<String> names) {
			var connector = connector();
			if (names.isEmpty()) {
				return allowList ? "Nothing is passed: commands ask as the access mode says."
						: "Nothing is passed: no command is blocked.";
			}
			if (connector == null) {
				return null;
			}
			if (!connector.supportsCommandRules()) {
				return "Not passed: " + provider.displayName() + " has no command rules.";
			}
			var problem = names.stream().map(AgentConnector::commandEntryProblem).flatMap(java.util.Optional::stream)
					.findFirst();
			if (problem.isPresent()) {
				return problem.get();
			}
			if (allowList) {
				return "Passed to " + provider.displayName() + " as "
						+ String.join(" ", connector.allowedCommandArguments(names));
			}
			return "Passed to " + provider.displayName() + " with the blocked tools, as "
					+ String.join(" ", connector.blockedToolArguments(connector.blockedCommandPatterns(names)));
		}
	}
}
