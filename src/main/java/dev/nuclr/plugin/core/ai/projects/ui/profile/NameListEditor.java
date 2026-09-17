package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * A titled list of names with an input that suggests known ones: the allowed and
 * blocked tools, the configured MCP servers to switch off.
 *
 * <p>What the names mean comes from a {@link Source}, asked again on every
 * {@link #refresh()}, so the same editor follows a provider change. Enter or Add
 * adds what was typed; Delete, the Remove button or the right-click menu removes
 * the selection. A name the source does not know is still accepted, and shown
 * greyed.
 */
final class NameListEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	/** What the names in one list mean, for the provider chosen now. */
	interface Source {

		/** Whether names can be added at all - false until a provider is chosen. */
		boolean enabled();

		/** Known names, offered in the input's dropdown. */
		List<String> suggestions();

		/** One or two sentences under the title. */
		String meaning();

		/**
		 * How a name reads in the list.
		 *
		 * @param name the entry
		 * @return the row text
		 */
		String describe(String name);

		/**
		 * Whether a name is one the source knows; unknown ones are shown greyed.
		 *
		 * @param name the entry
		 * @return whether it is known
		 */
		boolean known(String name);

		/**
		 * What the list will be passed as, shown under it.
		 *
		 * @param names the entries
		 * @return one line, or {@code null} for none
		 */
		String flags(List<String> names);
	}

	private final Source source;
	private final DefaultListModel<String> model = new DefaultListModel<>();
	private final JList<String> list = new JList<>(model);
	private final JComboBox<String> input = new JComboBox<>();
	private final JButton add;
	private final JButton remove;
	private final JLabel heading = new JLabel();
	private final WrappingNote meaning = new WrappingNote();
	private final WrappingNote flags = new WrappingNote();
	private final List<Runnable> listeners = new ArrayList<>();

	/**
	 * Build the editor.
	 *
	 * @param title     the heading
	 * @param addTip    the Add button's tooltip
	 * @param values    the initial names
	 * @param prototype a typical long name, sizing the input
	 * @param source    what the names mean
	 */
	NameListEditor(String title, String addTip, List<String> values, String prototype, Source source) {

		super(new BorderLayout(0, 6));
		this.source = source;
		values.forEach(model::addElement);

		heading.setText(title);
		heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));

		input.setEditable(true);
		input.setPrototypeDisplayValue(prototype);
		input.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> rows, Object value, int index, boolean selected,
					boolean focused) {
				return super.getListCellRendererComponent(rows, value == null ? null : source.describe((String) value),
						index, selected, focused);
			}
		});
		editor().getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "names.add");
		editor().getActionMap().put("names.add", keyAction(this::addTyped));

		add = Glyphs.decorate(new JButton(), Glyphs.NEW, "Add");
		add.setToolTipText(addTip + " (Enter)");
		add.addActionListener(event -> addTyped());
		remove = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Remove");
		remove.setToolTipText("Remove the selection (Delete)");
		remove.addActionListener(event -> removeSelected());

		var entry = new JPanel(new BorderLayout(6, 0));
		entry.add(input, BorderLayout.CENTER);
		entry.add(add, BorderLayout.EAST);

		list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		list.setCellRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> rows, Object value, int index, boolean selected,
					boolean focused) {
				var label = super.getListCellRendererComponent(rows, source.describe((String) value), index, selected,
						focused);
				if (source.enabled() && !source.known((String) value) && !selected) {
					label.setForeground(UIManager.getColor("Label.disabledForeground"));
				}
				return label;
			}
		});
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "names.remove");
		list.getActionMap().put("names.remove", keyAction(this::removeSelected));
		list.addListSelectionListener(event -> remove.setEnabled(list.getSelectedIndices().length > 0));
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent event) {
				popup(event);
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				popup(event);
			}

			private void popup(MouseEvent event) {
				if (!event.isPopupTrigger()) {
					return;
				}
				var row = list.locationToIndex(event.getPoint());
				if (row >= 0 && !list.isSelectedIndex(row)) {
					list.setSelectedIndex(row);
				}
				var menu = new JPopupMenu();
				var item = Glyphs.decorate(new JMenuItem(), Glyphs.DELETE, "Remove");
				item.setEnabled(list.getSelectedIndices().length > 0);
				item.addActionListener(chosen -> removeSelected());
				menu.add(item);
				menu.show(list, event.getX(), event.getY());
			}
		});
		model.addListDataListener(new javax.swing.event.ListDataListener() {
			@Override
			public void intervalAdded(javax.swing.event.ListDataEvent event) {
				changed();
			}

			@Override
			public void intervalRemoved(javax.swing.event.ListDataEvent event) {
				changed();
			}

			@Override
			public void contentsChanged(javax.swing.event.ListDataEvent event) {
				changed();
			}
		});

		var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		buttons.add(remove);

		var top = new JPanel(new BorderLayout(0, 4));
		top.add(heading, BorderLayout.NORTH);
		top.add(meaning, BorderLayout.CENTER);
		top.add(entry, BorderLayout.SOUTH);

		var bottom = new JPanel(new BorderLayout(0, 4));
		bottom.add(buttons, BorderLayout.NORTH);
		bottom.add(flags, BorderLayout.SOUTH);

		add(top, BorderLayout.NORTH);
		add(new JScrollPane(list), BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		remove.setEnabled(false);
	}

	/** The names, in order. */
	List<String> values() {
		var values = new ArrayList<String>();
		for (var index = 0; index < model.size(); index++) {
			values.add(model.get(index));
		}
		return values;
	}

	/** How many names there are. */
	int count() {
		return model.size();
	}

	/** Remove every name. */
	void clear() {
		model.clear();
	}

	/**
	 * Change the heading.
	 *
	 * @param title the new heading
	 */
	void setTitle(String title) {
		heading.setText(title);
	}

	/**
	 * Be told whenever the names change.
	 *
	 * @param listener run after every change
	 */
	void addChangeListener(Runnable listener) {
		listeners.add(listener);
	}

	/** Ask the source again: suggestions, meaning, flags and how rows read. */
	void refresh() {
		var typed = typed();
		input.removeAllItems();
		if (source.enabled()) {
			source.suggestions().forEach(input::addItem);
		}
		input.setSelectedItem(typed);
		input.setEnabled(source.enabled());
		add.setEnabled(source.enabled());
		meaning.setText(source.meaning());
		updateFlags();
		list.repaint();
	}

	/** The input's text field, for focusing. */
	JTextField editor() {
		return (JTextField) input.getEditor().getEditorComponent();
	}

	private void addTyped() {
		var name = typed();
		if (name.isEmpty() || !source.enabled()) {
			return;
		}
		if (!model.contains(name)) {
			model.addElement(name);
		}
		list.setSelectedValue(name, true);
		input.setSelectedItem("");
		editor().requestFocusInWindow();
	}

	private void removeSelected() {
		var selected = list.getSelectedIndices();
		for (var index = selected.length - 1; index >= 0; index--) {
			model.remove(selected[index]);
		}
		if (!model.isEmpty() && selected.length > 0) {
			list.setSelectedIndex(Math.min(selected[0], model.size() - 1));
		}
		list.requestFocusInWindow();
	}

	private void changed() {
		updateFlags();
		listeners.forEach(Runnable::run);
	}

	private void updateFlags() {
		flags.setText(source.enabled() ? source.flags(values()) : null);
	}

	private String typed() {
		var item = input.getEditor().getItem();
		return item == null ? "" : item.toString().strip();
	}

	private static AbstractAction keyAction(Runnable work) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				work.run();
			}
		};
	}
}
