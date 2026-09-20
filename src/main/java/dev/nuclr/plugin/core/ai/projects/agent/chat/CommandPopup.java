package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.event.KeyEvent;

/**
 * The list of commands that appears as a slash is typed in the composer.
 *
 * <p>A completion popup rather than a menu: the user keeps typing into the message box
 * the whole time, the list narrows as they do, and Enter takes the one highlighted. The
 * popup never takes the keyboard - it is drawn over the window and steered by keys bound
 * on the text area itself, which is what keeps typing and choosing the same gesture.
 *
 * <p>Those keys mean something else when no popup is showing. Up and Down move the caret
 * and Enter sends the message, so each binding falls through to whatever it replaced, and
 * Escape - which the screen around us uses - is bound only while the list is up.
 */
final class CommandPopup {

	/** Rows shown before the list scrolls. */
	private static final int VISIBLE_ROWS = 8;

	private static final String ESCAPE_ACTION = "nuclr.commands.hide";

	private final JTextArea input;
	private final Supplier<List<SlashCommand>> commands;
	private final Consumer<SlashCommand> onChosen;
	private final DefaultListModel<SlashCommand> model = new DefaultListModel<>();
	private final JList<SlashCommand> list = new JList<>(model);
	private final JScrollPane scroll = new JScrollPane(list);

	private Popup popup;

	/**
	 * Watch a composer for commands.
	 *
	 * @param input    the message box; its Enter binding must already be in place, so
	 *                 that Enter with no popup still sends
	 * @param commands the commands on offer now, asked again each time the list is shown
	 * @param onChosen given the command the user picked
	 */
	CommandPopup(JTextArea input, Supplier<List<SlashCommand>> commands, Consumer<SlashCommand> onChosen) {

		this.input = input;
		this.commands = commands;
		this.onChosen = onChosen;

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(VISIBLE_ROWS);
		list.setFocusable(false);
		list.setCellRenderer(renderer());
		scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") != null
				? UIManager.getColor("Component.borderColor")
				: java.awt.Color.GRAY));
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		input.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				refresh();
			}
		});

		whenShowing(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nuclr.commands.down", () -> move(1));
		whenShowing(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "nuclr.commands.up", () -> move(-1));
		whenShowing(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nuclr.commands.choose", this::choose);
		whenShowing(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "nuclr.commands.complete", this::complete);
	}

	/** Whether the list is up, and so whether the composer's keys belong to it. */
	boolean isShowing() {
		return popup != null;
	}

	/** Take the list down, if it is up. */
	void hide() {
		if (popup == null) {
			return;
		}
		popup.hide();
		popup = null;
		input.getInputMap(JComponent.WHEN_FOCUSED).remove(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
	}

	/** Show, re-fill or take down the list, after what is typed changed. */
	private void refresh() {
		var matches = SlashCommands.matching(commands.get(), input.getText());
		if (matches.isEmpty()) {
			hide();
			return;
		}
		var selected = list.getSelectedValue();
		model.clear();
		matches.forEach(model::addElement);
		list.setSelectedIndex(Math.max(0, matches.indexOf(selected)));
		show();
	}

	/**
	 * Put the list on screen, above the composer.
	 *
	 * <p>Above, because the composer sits at the foot of the window and a list below it
	 * would fall off the frame. Re-created on every change: a popup does not re-layout,
	 * and the list grows and shrinks as the name is typed.
	 */
	private void show() {
		if (!input.isShowing()) {
			return;
		}
		var size = scroll.getPreferredSize();
		var height = Math.min(size.height, rowHeight() * VISIBLE_ROWS + 8);
		scroll.setPreferredSize(new Dimension(Math.max(360, input.getWidth()), height));
		var where = input.getLocationOnScreen();
		var fresh = PopupFactory.getSharedInstance().getPopup(input, scroll, where.x, where.y - height - 2);
		// Swapped rather than hidden first, so the list does not flicker on every keystroke.
		var previous = popup;
		popup = fresh;
		fresh.show();
		if (previous != null) {
			previous.hide();
		} else {
			input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_ACTION);
			input.getActionMap().put(ESCAPE_ACTION, action(this::hide));
		}
	}

	private int rowHeight() {
		var height = list.getFixedCellHeight();
		return height > 0 ? height : Math.max(18, input.getFontMetrics(input.getFont()).getHeight() * 2 + 6);
	}

	private void move(int steps) {
		if (model.isEmpty()) {
			return;
		}
		var next = Math.floorMod(list.getSelectedIndex() + steps, model.size());
		list.setSelectedIndex(next);
		list.ensureIndexIsVisible(next);
	}

	/** Take the highlighted command: the box is cleared and the command runs. */
	private void choose() {
		var chosen = list.getSelectedValue();
		hide();
		if (chosen == null) {
			return;
		}
		input.setText("");
		onChosen.accept(chosen);
	}

	/** Finish the name being typed, leaving the user to write its argument. */
	private void complete() {
		var chosen = list.getSelectedValue();
		if (chosen == null) {
			return;
		}
		input.setText("/" + chosen.name() + (chosen.usage().isEmpty() ? "" : " "));
		input.setCaretPosition(input.getDocument().getLength());
		if (chosen.usage().isEmpty()) {
			hide();
		}
	}

	/**
	 * Bind a key to the list while it is showing, and to whatever it already did
	 * otherwise.
	 *
	 * @param stroke     the key
	 * @param id         a name for the binding
	 * @param whenShowing what the key does while the list is up
	 */
	private void whenShowing(KeyStroke stroke, String id, Runnable whenShowing) {
		var inputMap = input.getInputMap(JComponent.WHEN_FOCUSED);
		var previousId = inputMap.get(stroke);
		var previous = previousId == null ? null : input.getActionMap().get(previousId);
		inputMap.put(stroke, id);
		input.getActionMap().put(id, new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				if (isShowing()) {
					whenShowing.run();
					return;
				}
				if (previous != null) {
					previous.actionPerformed(event);
				}
			}
		});
	}

	private static Action action(Runnable work) {
		return new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				work.run();
			}
		};
	}

	/** One command per row: what to type, then what it does. */
	private DefaultListCellRenderer renderer() {
		return new DefaultListCellRenderer() {

			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				super.getListCellRendererComponent(list, value, index, selected, focused);
				if (value instanceof SlashCommand command) {
					setText("<html><b>" + command.display() + "</b>&nbsp;&nbsp;&nbsp;" + escape(command.summary())
							+ "</html>");
				}
				return this;
			}
		};
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
