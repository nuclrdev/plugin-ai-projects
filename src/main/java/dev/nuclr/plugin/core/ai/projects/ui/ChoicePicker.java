package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Pick one thing from a list that may be long: a model, a thinking level, anything a
 * command offers a choice of.
 *
 * <p>A list with a filter above it rather than a combo box, because a CLI can report
 * thirty models and each one is worth a line of description. What is in use now is
 * selected when the dialog opens, so the common answer - look at what is set, change
 * nothing - takes no reading.
 */
public final class ChoicePicker {

	private ChoicePicker() {
	}

	/**
	 * One option.
	 *
	 * @param id          what the caller acts on
	 * @param label       what the user reads
	 * @param description one line more, or {@code null}
	 */
	public record Choice(String id, String label, String description) {
	}

	/**
	 * Ask the user to choose.
	 *
	 * @param parent    component to centre on
	 * @param title     the dialog's title
	 * @param prompt    one line above the list
	 * @param choices   the options, in the order to show them
	 * @param currentId the option in use now, selected when the dialog opens, or {@code null}
	 * @return the chosen option, or empty when cancelled, empty-handed or headless
	 */
	public static Optional<Choice> pick(Component parent, String title, String prompt, List<Choice> choices,
			String currentId) {

		if (Dialogs.isHeadless() || choices.isEmpty()) {
			return Optional.empty();
		}
		var model = new DefaultListModel<Choice>();
		choices.forEach(model::addElement);
		var list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(Math.min(14, Math.max(6, choices.size())));
		list.setCellRenderer(renderer(currentId));
		select(list, model, currentId);

		var filter = new JTextField();
		filter.putClientProperty("JTextField.placeholderText", "Type to filter");
		filter.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				refilter();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				refilter();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				refilter();
			}

			private void refilter() {
				var wanted = filter.getText().strip().toLowerCase(Locale.ROOT);
				var chosen = list.getSelectedValue();
				model.clear();
				choices.stream().filter(choice -> matches(choice, wanted)).forEach(model::addElement);
				if (chosen != null && model.contains(chosen)) {
					list.setSelectedValue(chosen, true);
				} else if (!model.isEmpty()) {
					list.setSelectedIndex(0);
				}
			}
		});

		var panel = new JPanel(new BorderLayout(0, 6));
		panel.add(new JLabel(prompt), BorderLayout.NORTH);
		var middle = new JPanel(new BorderLayout(0, 4));
		middle.add(filter, BorderLayout.NORTH);
		var scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(460, 260));
		middle.add(scroll, BorderLayout.CENTER);
		panel.add(middle, BorderLayout.CENTER);
		TextContextMenu.install(filter);

		var answer = Dialogs.showOptionDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, null, null);
		if (answer != JOptionPane.OK_OPTION) {
			return Optional.empty();
		}
		return Optional.ofNullable(list.getSelectedValue());
	}

	private static boolean matches(Choice choice, String wanted) {
		if (wanted.isEmpty()) {
			return true;
		}
		return contains(choice.label(), wanted) || contains(choice.id(), wanted) || contains(choice.description(), wanted);
	}

	private static boolean contains(String text, String wanted) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(wanted);
	}

	private static void select(JList<Choice> list, DefaultListModel<Choice> model, String currentId) {
		for (var index = 0; index < model.size(); index++) {
			if (model.get(index).id().equalsIgnoreCase(currentId)) {
				list.setSelectedIndex(index);
				list.ensureIndexIsVisible(index);
				return;
			}
		}
		list.setSelectedIndex(0);
	}

	/** Each option on one line: what it is called, then what it is, then whether it is in use. */
	private static DefaultListCellRenderer renderer(String currentId) {
		return new DefaultListCellRenderer() {

			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				super.getListCellRendererComponent(list, value, index, selected, focused);
				if (value instanceof Choice choice) {
					var text = new StringBuilder("<html><b>").append(escape(choice.label())).append("</b>");
					if (choice.id().equalsIgnoreCase(currentId)) {
						text.append("&nbsp;&nbsp;(in use)");
					}
					if (choice.description() != null && !choice.description().isBlank()) {
						text.append("<br>").append(escape(choice.description()));
					}
					setText(text.append("</html>").toString());
				}
				return this;
			}
		};
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
