package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/** Every editable field gets the same right-click menu, and its undo really works. */
class TextContextMenuTest {

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	private static List<String> labels(JPopupMenu menu) {
		var labels = new ArrayList<String>();
		for (var component : menu.getComponents()) {
			labels.add(component instanceof JMenuItem item ? item.getText() : "-");
		}
		return labels;
	}

	@Test
	void theMenuHasTheAgreedItemsInTheAgreedOrder() throws Exception {
		var labels = new ArrayList<String>();
		onEdt(() -> {
			var field = new JTextField("value");
			TextContextMenu.install(field);
			labels.addAll(labels(field.getComponentPopupMenu()));
		});
		assertEquals(List.of("Undo", "Redo", "-", "Copy", "Cut", "Paste", "-", "Select All"), labels);
	}

	@Test
	void undoStopsAtTheTextTheFieldWasOpenedWith() throws Exception {
		var texts = new String[3];
		onEdt(() -> {
			var field = new JTextField("opened");
			TextContextMenu.install(field);
			var undo = TextContextMenu.undoManager(field);
			field.setText("typed");
			undo.undo();
			texts[0] = field.getText();
			while (undo.canUndo()) {
				undo.undo();
			}
			texts[1] = field.getText();
			undo.redo();
			undo.redo();
			texts[2] = field.getText();
		});
		assertEquals("", texts[0]); // setText is a remove then an insert; one undo reverts the insert
		assertEquals("opened", texts[1]);
		assertEquals("typed", texts[2]);
	}

	@Test
	void aWholeFormIsCoveredAndReadOnlyTextIsLeftAlone() throws Exception {
		var results = new Object[4];
		onEdt(() -> {
			var form = new JPanel();
			var nested = new JPanel();
			var field = new JTextField();
			var area = new JTextArea();
			var readOnly = new JTextArea("fixed");
			readOnly.setEditable(false);
			nested.add(new JScrollPane(area));
			nested.add(readOnly);
			form.add(new JLabel("Name"));
			form.add(field);
			form.add(nested);

			TextContextMenu.installTree(form);
			var menu = field.getComponentPopupMenu();
			TextContextMenu.installTree(form);

			results[0] = menu;
			results[1] = field.getComponentPopupMenu();
			results[2] = area.getComponentPopupMenu();
			results[3] = readOnly.getComponentPopupMenu();
		});
		assertNotNull(results[0]);
		assertSame(results[0], results[1], "installing twice keeps the first menu and history");
		assertNotNull(results[2]);
		assertNull(results[3]);
	}
}
