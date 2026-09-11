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
		return JOptionPane.showConfirmDialog(parent, message, title,
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
		return JOptionPane.showConfirmDialog(parent, message, title,
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
		return JOptionPane.showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION,
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
		JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
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
		JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
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
		var answer = JOptionPane.showInputDialog(parent, message, initial);
		if (answer == null) {
			return null;
		}
		var trimmed = answer.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
