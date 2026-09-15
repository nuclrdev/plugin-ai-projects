package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Component;
import java.awt.GraphicsEnvironment;

import javax.swing.JOptionPane;

/**
 * The desktop's dialogs, in one place.
 *
 * <p>Every one of them checks for a headless environment first and answers as
 * though the user had accepted. There is no user in a headless JVM, so the
 * alternative is a {@link java.awt.HeadlessException} thrown from the middle of
 * a close or a save - a crash instead of a question. It also keeps the
 * confirmations themselves testable: the surrounding logic can be exercised
 * without a display, which is the only way most of these paths ever get covered.
 *
 * <p>Commander is a desktop application, so the headless branch never runs in
 * production. It is a test and safety affordance, not a supported mode.
 */
public final class Dialogs {

	private Dialogs() {
	}

	/**
	 * The platform's menu-shortcut modifier: Command on macOS, Control elsewhere.
	 *
	 * <p>{@link java.awt.Toolkit#getMenuShortcutKeyMaskEx()} throws in a headless
	 * JVM, so asking it directly from a component's constructor makes that
	 * component unbuildable without a display. Control is the right answer for
	 * every platform macOS is not, and is a harmless answer where there is nobody
	 * to press a key at all.
	 *
	 * @return the modifier mask for keyboard shortcuts
	 */
	public static int menuShortcutMask() {
		try {
			return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		} catch (java.awt.HeadlessException | java.awt.AWTError e) {
			return java.awt.event.InputEvent.CTRL_DOWN_MASK;
		}
	}

	/** Whether there is a display to put a dialog on. */
	public static boolean isHeadless() {
		return GraphicsEnvironment.isHeadless();
	}

	/**
	 * Ask a yes/no question.
	 *
	 * @param parent  component to centre on
	 * @param title   dialog title
	 * @param message the question
	 * @return whether the user said yes; {@code true} when headless
	 */
	public static boolean confirm(Component parent, String title, String message) {
		if (isHeadless()) {
			return true;
		}
		return showConfirmDialog(parent, message, title,
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
	}

	/**
	 * Ask a yes/no question about something ordinary rather than destructive.
	 *
	 * @param parent  component to centre on
	 * @param title   dialog title
	 * @param message the question
     * @return whether the user said yes; {@code true} when headless
	 */
	public static boolean ask(Component parent, String title, String message) {
		if (isHeadless()) {
			return true;
		}
		return showConfirmDialog(parent, message, title,
				JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
	}

	/**
	 * Offer a choice between two named outcomes, defaulting to the first.
	 *
	 * @param parent  component to centre on
	 * @param title   dialog title
	 * @param message the question
	 * @param first   the default choice
	 * @param second  the other choice
	 * @return {@code true} when the first was chosen; {@code true} when headless
	 */
	public static boolean choose(Component parent, String title, String message, String first, String second) {
		if (isHeadless()) {
			return true;
		}
		var options = new Object[] { first, second };
		return showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION,
				JOptionPane.WARNING_MESSAGE, null, options, options[0]) == 0;
	}

	/**
	 * Tell the user something.
	 *
	 * @param parent  component to centre on
	 * @param title   dialog title
	 * @param message what to say
	 */
	public static void message(Component parent, String title, String message) {
		if (isHeadless()) {
			return;
		}
		showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Tell the user something in a popup with a single Close button, over whatever window is
	 * active. Escape closes it as well: the binding is made here rather than trusted to the
	 * look and feel, because every popup in Commander has to close on Escape.
	 *
	 * @param parent  component to centre on, or {@code null} for the active window
	 * @param title   dialog title
	 * @param message what to say; wrapped, so it may be a long sentence
	 */
	public static void notice(Component parent, String title, String message) {
		if (isHeadless()) {
			return;
		}
		var close = "Close";
		var pane = new JOptionPane(wrapped(message), JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION,
				null, new Object[] { close }, close);
		var owner = parent != null ? parent
				: java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		show(pane, owner, title);
	}

	// ------------------------------------------------------------------------
	// JOptionPane, with Escape bound
	//
	// Every dialog in this plugin goes through these rather than JOptionPane's own
	// static methods. They take the same arguments and return the same values, and
	// behave the same without a display (they throw), but bind Escape on the dialog
	// itself instead of trusting the look and feel to: Escape must close every popup.
	// Escape answers exactly like the title-bar close button - CLOSED_OPTION, or null.
	// ------------------------------------------------------------------------

	/** {@link JOptionPane#showConfirmDialog(Component, Object, String, int, int)}, closable by Escape. */
	public static int showConfirmDialog(Component parent, Object message, String title, int optionType,
			int messageType) {
		var pane = new JOptionPane(message, messageType, optionType);
		show(pane, parent, title);
		return chosenIndex(pane, null);
	}

	/** {@link JOptionPane#showMessageDialog(Component, Object, String, int)}, closable by Escape. */
	public static void showMessageDialog(Component parent, Object message, String title, int messageType) {
		show(new JOptionPane(message, messageType), parent, title);
	}

	/**
	 * {@link JOptionPane#showOptionDialog(Component, Object, String, int, int, javax.swing.Icon, Object[], Object)},
	 * closable by Escape.
	 */
	public static int showOptionDialog(Component parent, Object message, String title, int optionType,
			int messageType, javax.swing.Icon icon, Object[] options, Object initialValue) {
		var pane = new JOptionPane(message, messageType, optionType, icon, options, initialValue);
		pane.setInitialValue(initialValue);
		show(pane, parent, title);
		return chosenIndex(pane, options);
	}

	/** {@link JOptionPane#showInputDialog(Component, Object, Object)}, closable by Escape. */
	public static String showInputDialog(Component parent, Object message, String initial) {
		var pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
		pane.setWantsInput(true);
		pane.setInitialSelectionValue(initial);
		show(pane, parent, javax.swing.UIManager.getString("OptionPane.inputDialogTitle"));
		var value = pane.getInputValue();
		return value == JOptionPane.UNINITIALIZED_VALUE || value == null ? null : String.valueOf(value);
	}

	/**
	 * A file chooser whose dialog Escape cancels, wherever the focus is in it - not only
	 * while the file list has it.
	 *
	 * @return a new chooser
	 */
	public static javax.swing.JFileChooser fileChooser() {
		return new javax.swing.JFileChooser() {
			private static final long serialVersionUID = 1L;

			@Override
			protected javax.swing.JDialog createDialog(Component parent) {
				var dialog = super.createDialog(parent);
				closeOnEscape(dialog, this::cancelSelection);
				return dialog;
			}
		};
	}

	/**
	 * Make Escape close a dialog, wherever the focus is inside it.
	 *
	 * @param dialog the dialog
	 */
	public static void closeOnEscape(javax.swing.JDialog dialog) {
		closeOnEscape(dialog, () -> dialog.setVisible(false));
	}

	/**
	 * Make Escape run {@code onEscape} in a dialog, wherever the focus is inside it.
	 *
	 * @param dialog   the dialog
	 * @param onEscape what Escape does; it must close the dialog
	 */
	public static void closeOnEscape(javax.swing.JDialog dialog, Runnable onEscape) {
		var root = dialog.getRootPane();
		root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "nuclr.dialog.close");
		root.getActionMap().put("nuclr.dialog.close", new javax.swing.AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				onEscape.run();
			}
		});
	}

	private static void show(JOptionPane pane, Component parent, String title) {
		var dialog = pane.createDialog(parent, title);
		pane.selectInitialValue();
		closeOnEscape(dialog);
		dialog.setVisible(true);
		dialog.dispose();
	}

	/** The answer as JOptionPane's static methods report it: an index, or CLOSED_OPTION. */
	private static int chosenIndex(JOptionPane pane, Object[] options) {
		var value = pane.getValue();
		if (value == null || value == JOptionPane.UNINITIALIZED_VALUE) {
			return JOptionPane.CLOSED_OPTION;
		}
		if (options == null) {
			return value instanceof Integer index ? index : JOptionPane.CLOSED_OPTION;
		}
		for (var index = 0; index < options.length; index++) {
			if (options[index].equals(value)) {
				return index;
			}
		}
		return JOptionPane.CLOSED_OPTION;
	}

	private static String wrapped(String message) {
		var text = message == null ? "" : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width: 360px'>" + text + "</body></html>";
	}

	/**
	 * Report a failure.
	 *
	 * @param parent  component to centre on
	 * @param title   dialog title
	 * @param message what went wrong
	 */
	public static void error(Component parent, String title, String message) {
		if (isHeadless()) {
			return;
		}
		showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Ask for a line of text.
	 *
	 * @param parent  component to centre on
	 * @param message the prompt
	 * @param initial the initial value
	 * @return the entered text, or {@code null} when cancelled, left blank, or headless
	 */
	public static String input(Component parent, String message, String initial) {
		if (isHeadless()) {
			return null;
		}
		var answer = showInputDialog(parent, message, initial);
		if (answer == null) {
			return null;
		}
		var trimmed = answer.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
