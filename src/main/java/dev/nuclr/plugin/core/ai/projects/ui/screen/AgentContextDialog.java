package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * An agent's context, as its last launch recorded it: a tree on the left, what the
 * chosen part holds on the right.
 *
 * <p>Not modal, so it can stay open beside the agent while the conversation goes on.
 * The view is built off the event thread - it reads the briefing file and the profile -
 * and again on Refresh, which is what to press after a restart.
 */
final class AgentContextDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private final Supplier<AgentContextView> source;
	private final Consumer<String> openFile;
	private final JLabel banner = new JLabel();
	private final JTree tree = new JTree(new DefaultMutableTreeNode());
	private final JTextArea content = new JTextArea();
	private final JButton open;
	private String briefingFile;

	/**
	 * @param owner     the window it belongs to
	 * @param agentName the agent's name, for the title
	 * @param source    builds the view; called off the event thread
	 * @param openFile  opens a file outside Commander
	 */
	AgentContextDialog(Window owner, String agentName, Supplier<AgentContextView> source, Consumer<String> openFile) {
		super(owner, "Context - " + agentName, ModalityType.MODELESS);
		this.source = source;
		this.openFile = openFile;

		banner.setIcon(Glyphs.icon(Glyphs.MISSING));
		banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		banner.setVisible(false);

		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setCellRenderer(new Renderer());
		tree.addTreeSelectionListener(event -> show(event.getPath()));

		content.setEditable(false);
		content.setLineWrap(true);
		content.setWrapStyleWord(true);
		content.setFont(new Font(Font.MONOSPACED, Font.PLAIN, content.getFont().getSize()));
		content.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		TextContextMenu.install(content);

		var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(content));
		split.setDividerLocation(230);
		split.setResizeWeight(0.25);

		var copy = Glyphs.decorate(new JButton(), Glyphs.COPY, "Copy");
		copy.setToolTipText("Copy what is shown on the right");
		copy.addActionListener(event -> getToolkit().getSystemClipboard()
				.setContents(new StringSelection(content.getText()), null));
		open = Glyphs.decorate(new JButton(), Glyphs.LINK, "Open briefing file");
		open.setToolTipText("Open the briefing this agent was given, outside Commander");
		open.addActionListener(event -> {
			if (briefingFile != null) {
				openFile.accept(briefingFile);
			}
		});
		// Until the view is read; each view, Refresh's included, says which file is the briefing now.
		open.setEnabled(false);
		var refresh = Glyphs.decorate(new JButton(), Glyphs.REFRESH, "Refresh");
		refresh.setToolTipText("Read the agent's context again - after a restart, say");
		refresh.addActionListener(event -> reload());
		var close = new JButton("Close");
		close.addActionListener(event -> dispose());
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 6));
		buttons.add(copy);
		buttons.add(open);
		buttons.add(refresh);
		buttons.add(close);

		var root = new JPanel(new BorderLayout());
		root.add(banner, BorderLayout.NORTH);
		root.add(split, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		setContentPane(root);
		getRootPane().setDefaultButton(close);
		setPreferredSize(new Dimension(900, 600));
		pack();
		setLocationRelativeTo(owner);
		reload();
	}

	/** Build the view again, off the event thread, and show it. */
	private void reload() {
		content.setText("Reading the agent's context ...");
		Thread.ofVirtual().name("nuclr-agent-context").start(() -> {
			var view = source.get();
			SwingUtilities.invokeLater(() -> {
				if (isDisplayable()) {
					showView(view);
				}
			});
		});
	}

	/** Put a view in the tree, open it all, and show its briefing - the part most often looked for. */
	void showView(AgentContextView view) {
		briefingFile = view.briefingFile();
		open.setEnabled(briefingFile != null);
		banner.setText(view.banner());
		banner.setVisible(view.banner() != null);
		var root = new DefaultMutableTreeNode();
		for (var entry : view.entries()) {
			root.add(node(entry));
		}
		tree.setModel(new DefaultTreeModel(root));
		for (var row = 0; row < tree.getRowCount(); row++) {
			tree.expandRow(row);
		}
		var chosen = 0;
		for (var row = 0; row < tree.getRowCount(); row++) {
			if ("Briefing".equals(String.valueOf(tree.getPathForRow(row).getLastPathComponent()))) {
				chosen = row;
				break;
			}
		}
		tree.setSelectionRow(chosen);
	}

	private static DefaultMutableTreeNode node(AgentContextView.Entry entry) {
		var node = new DefaultMutableTreeNode(entry);
		entry.getChildren().forEach(child -> node.add(node(child)));
		return node;
	}

	private void show(TreePath path) {
		if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent())
				.getUserObject() instanceof AgentContextView.Entry entry) {
			content.setText(entry.getContent());
			content.setCaretPosition(0);
		}
	}

	/** Whether the briefing file can be opened, for tests. */
	boolean canOpenBriefing() {
		return open.isEnabled();
	}

	/** The text on the right, for tests. */
	String shownContent() {
		return content.getText();
	}

	/** Each part with a mark when it wants attention - something not applied, not found, not given. */
	private static final class Renderer extends DefaultTreeCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
				boolean leaf, int row, boolean focused) {
			super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
			if (((DefaultMutableTreeNode) value).getUserObject() instanceof AgentContextView.Entry entry) {
				setIcon(Glyphs.icon(entry.needsAttention() ? Glyphs.MISSING : Glyphs.CONTEXT));
			}
			return this;
		}
	}
}
