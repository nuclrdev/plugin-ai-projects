package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

/**
 * What the conversation does with a session that runs all day: how much of it is kept
 * on screen, and whether its text can be resized.
 */
class ConversationViewTest {

	/**
	 * The column the blocks are added to: the view holds a scroll pane, whose view is a
	 * holder, whose only child is the column.
	 */
	private static Container blocks(ConversationView view) {
		var scroll = (JScrollPane) view.getComponent(0);
		var holder = (Container) scroll.getViewport().getView();
		return (Container) holder.getComponent(0);
	}

	/** The first text area anywhere under a component, which is where a block's text is. */
	private static JTextArea firstTextArea(Container container) {
		for (var child : container.getComponents()) {
			if (child instanceof JTextArea area) {
				return area;
			}
			if (child instanceof Container nested) {
				var found = firstTextArea(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	@Test
	void aConversationThatRunsAllDayDoesNotGrowWithoutEnd() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});

		onEdt(() -> {
			for (var index = 0; index < 1_000; index++) {
				view.accept(new AgentEvent.UserMessage("message " + index), true);
			}
		});

		var column = blocks(view);
		// Blocks and the struts between them, plus the line saying the rest was dropped.
		assertTrue(column.getComponentCount() <= 600 * 2 + 2,
				"the column kept " + column.getComponentCount() + " components");
		assertTrue(column.getComponentCount() > 600,
				"the column dropped more than it should have: " + column.getComponentCount());
	}

	@Test
	void whatWasDroppedIsSaidRatherThanSilentlyMissing() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});

		onEdt(() -> {
			for (var index = 0; index < 1_000; index++) {
				view.accept(new AgentEvent.UserMessage("message " + index), true);
			}
		});

		var top = blocks(view).getComponent(0);
		assertTrue(top instanceof JLabel label && label.getText().contains("Earlier messages"),
				"a trimmed conversation should say so at the top, but began with " + top.getClass().getSimpleName());
	}

	@Test
	void theNewestMessageSurvivesTheTrimming() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});

		onEdt(() -> {
			for (var index = 0; index < 1_000; index++) {
				view.accept(new AgentEvent.UserMessage("message " + index), true);
			}
		});

		var column = blocks(view);
		var last = (Container) column.getComponent(column.getComponentCount() - 1);
		assertEquals("message 999", firstTextArea(last).getText());
	}

	@Test
	void theTextCanBeGrownAndShrunkAndPutBack() throws Exception {

		var base = UIManager.getFont("TextArea.font");
		assertNotNull(base, "the look and feel has no text font to scale");
		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> view.accept(new AgentEvent.UserMessage("hello"), true));
		var text = firstTextArea(blocks(view));
		var started = text.getFont().getSize();

		onEdt(() -> view.zoom(2));
		assertEquals(started + 2, text.getFont().getSize());

		onEdt(() -> view.zoom(-3));
		assertEquals(started - 1, text.getFont().getSize());

		onEdt(view::resetZoom);
		assertEquals(started, text.getFont().getSize());
	}

	@Test
	void zoomingStopsBeforeTheTextBecomesUnreadableOrAbsurd() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> view.accept(new AgentEvent.UserMessage("hello"), true));
		var text = firstTextArea(blocks(view));
		var started = text.getFont().getSize();

		onEdt(() -> view.zoom(100));
		assertEquals(started + 12, text.getFont().getSize(), "zooming in is capped");

		onEdt(() -> view.zoom(-100));
		assertEquals(Math.max(7, started - 4), text.getFont().getSize(), "zooming out is capped");
	}

	@Test
	void aZoomedConversationStaysZoomedWhenTheThemeChanges() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> view.accept(new AgentEvent.UserMessage("hello"), true));
		var text = firstTextArea(blocks(view));
		var started = text.getFont().getSize();

		onEdt(() -> view.zoom(3));
		onEdt(view::updateTheme);

		assertEquals(started + 3, text.getFont().getSize());
	}
}
