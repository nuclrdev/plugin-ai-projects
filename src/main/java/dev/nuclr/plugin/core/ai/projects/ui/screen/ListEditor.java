package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * A one-value-per-line text box, for the many list-shaped fields in a harness:
 * permissions, allowed roots, shared instructions, skills.
 *
 * <p>A table with add/remove buttons would be more ceremonious and no clearer.
 * These are short lists of short strings that people paste in from elsewhere,
 * and a text box is the fastest way to edit one.
 *
 * <p>The distinction the harness rests on - {@code null} means inherit, empty
 * means explicitly nothing - cannot be typed, so it is carried by
 * {@link #setInherited(boolean)} and the checkbox its owner draws.
 */
public final class ListEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JTextArea area = new JTextArea();

	/**
	 * Build an editor.
	 *
	 * @param hint  greyed-out line above the box explaining the format
	 * @param rows  visible height in text rows
	 * @param values the initial values, or {@code null} for none
	 */
	public ListEditor(String hint, int rows, List<String> values) {

		super(new BorderLayout(0, 2));
		area.setRows(rows);
		area.setText(values == null ? "" : String.join(System.lineSeparator(), values));
		area.setCaretPosition(0);

		if (hint != null && !hint.isBlank()) {
			var label = new JLabel(hint);
			label.setEnabled(false);
			label.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
			add(label, BorderLayout.NORTH);
		}
		add(new JScrollPane(area), BorderLayout.CENTER);
	}

	/** The non-blank lines, trimmed, in order. */
	public List<String> values() {
		var values = new ArrayList<String>();
		for (var line : area.getText().split("\\R")) {
			var trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				values.add(trimmed);
			}
		}
		return values;
	}

	/**
	 * Grey the box out when its field is inheriting, so an inherited value cannot
	 * be edited into an override by accident.
	 *
	 * @param inherited whether the field currently inherits
	 */
	public void setInherited(boolean inherited) {
		area.setEnabled(!inherited);
	}

	/**
	 * Replace the contents, used when a field switches from inheriting to
	 * overriding and should start from what it was inheriting.
	 *
	 * @param values the values to show
	 */
	public void setValues(List<String> values) {
		area.setText(values == null ? "" : String.join(System.lineSeparator(), values));
		area.setCaretPosition(0);
	}

	/**
	 * Add lines not already present, keeping what was typed.
	 *
	 * @param additions the values to add
	 */
	public void append(List<String> additions) {
		var values = new ArrayList<>(values());
		for (var addition : additions) {
			if (addition != null && !addition.isBlank() && !values.contains(addition.trim())) {
				values.add(addition.trim());
			}
		}
		setValues(values);
	}

	/**
	 * Parse {@code KEY=value} lines into an ordered map, for environment editing.
	 *
	 * <p>A line with no {@code =} is kept as a key with an empty value rather than
	 * dropped: silently discarding a line someone typed is worse than showing it
	 * back to them looking wrong.
	 *
	 * <p>A line that starts at the {@code =} is the exception. It names no
	 * variable, and keeping it would put a blank name in the environment handed to
	 * the agent process - which is not a variable the user can ever have meant.
	 *
	 * @return the parsed entries, in the order they were written
	 */
	public Map<String, String> asMap() {
		var entries = new LinkedHashMap<String, String>();
		for (var line : values()) {
			var separator = line.indexOf('=');
			var name = separator < 0 ? line : line.substring(0, separator).trim();
			if (name.isEmpty()) {
				continue;
			}
			entries.put(name, separator < 0 ? "" : line.substring(separator + 1).trim());
		}
		return entries;
	}

	/**
	 * Render a map as {@code KEY=value} lines.
	 *
	 * @param entries the entries, possibly {@code null}
	 * @return one line per entry
	 */
	public static List<String> fromMap(Map<String, String> entries) {
		if (entries == null) {
			return List.of();
		}
		return entries.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
	}
}
