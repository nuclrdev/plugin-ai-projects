package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	/** The HTML a reply was last rendered to, read back off its editor pane. */
	private static String renderedHtml(ConversationView view) {
		var pane = firstEditorPane(blocks(view));
		assertNotNull(pane, "the reply has no editor pane");
		try {
			return pane.getDocument().getText(0, pane.getDocument().getLength());
		} catch (javax.swing.text.BadLocationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static javax.swing.JEditorPane firstEditorPane(Container container) {
		for (var child : container.getComponents()) {
			if (child instanceof javax.swing.JEditorPane pane) {
				return pane;
			}
			if (child instanceof Container nested) {
				var found = firstEditorPane(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** A clipboard of this test's own, since a headless run has no system one. */
	private static java.awt.datatransfer.Clipboard clipboardFor(ConversationView view) {
		var clipboard = new java.awt.datatransfer.Clipboard("test");
		clipboard.setContents(new java.awt.datatransfer.StringSelection("something else"), null);
		view.clipboard(clipboard);
		return clipboard;
	}

	/** Click the link a code block's copy icon stands for, as the pane would report it. */
	private static void clickCopy(ConversationView view, int block) throws Exception {
		var pane = firstEditorPane(blocks(view));
		onEdt(() -> {
			for (var listener : pane.getHyperlinkListeners()) {
				listener.hyperlinkUpdate(new javax.swing.event.HyperlinkEvent(pane,
						javax.swing.event.HyperlinkEvent.EventType.ACTIVATED, null, "nuclr-copy:" + block));
			}
		});
	}

	@Test
	void aCodeBlockOffersToCopyItselfAndDoes() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var clipboard = clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.MessageChunk("Try:\n\n```java\nint x = 1;\n```\n"), true));

		clickCopy(view, 0);

		assertEquals("int x = 1;", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	@Test
	void theCodeBlockCopyIconIsNotUnderlinedLikeAWebLink() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> view.accept(new AgentEvent.MessageChunk("```java\nint x = 1;\n```\n"), true));

		var document = (javax.swing.text.html.HTMLDocument) firstEditorPane(blocks(view)).getDocument();
		var link = document.getIterator(javax.swing.text.html.HTML.Tag.A);
		assertTrue(link.isValid(), "the block has no copy link");
		assertEquals("none", String.valueOf(link.getAttributes()
				.getAttribute(javax.swing.text.html.CSS.Attribute.TEXT_DECORATION)));
	}

	@Test
	void eachCodeBlockCopiesItsOwnCodeAndNotTheOneBeforeIt() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var clipboard = clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.MessageChunk(
				"One:\n\n```java\nfirst();\n```\n\nTwo:\n\n```bash\nsecond\n```\n"), true));

		clickCopy(view, 1);

		assertEquals("second", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	@Test
	void theCopiedBlockSaysSoAndTheLanguageIsShown() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.MessageChunk("```java\nint x = 1;\n```\n"), true));

		assertTrue(renderedHtml(view).contains("java"), "the block does not say what language it is");
		assertFalse(renderedHtml(view).contains("copied"), renderedHtml(view));

		clickCopy(view, 0);

		assertTrue(renderedHtml(view).contains("copied"), "the copy was silent: " + renderedHtml(view));
	}

	@Test
	void aCopyLinkForABlockThatIsNoLongerThereIsHarmless() throws Exception {

		// The reply is rebuilt on every chunk, so a click can always arrive against a
		// numbering that has just changed.
		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.MessageChunk("```java\nint x = 1;\n```\n"), true));

		clickCopy(view, 7);

		assertFalse(renderedHtml(view).contains("copied"), "a click on nothing claimed to copy something");
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

	/** Fire one entry of a component's right-click menu, found by its label. */
	private static void chooseMenuItem(javax.swing.JComponent component, String label) throws Exception {
		var menu = component.getComponentPopupMenu();
		assertNotNull(menu, "no context menu on " + component.getClass().getSimpleName());
		onEdt(() -> {
			for (var listener : menu.getPopupMenuListeners()) {
				listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
			}
			for (var child : menu.getComponents()) {
				if (child instanceof javax.swing.JMenuItem entry && label.equals(entry.getText())) {
					entry.doClick();
					return;
				}
			}
			throw new AssertionError("no \"" + label + "\" in the menu");
		});
	}

	@Test
	void theLineThatClosesATurnSaysWhatTheAgentIsRunningAs() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var folder = java.nio.file.Path.of("work", "project").toAbsolutePath();
		onEdt(() -> {
			view.setLaunchFacts(folder, "claude-opus-5", "high");
			view.accept(new AgentEvent.TurnEnded(false, null, null, 2_400L), true);
		});

		var line = lastLabel(blocks(view));
		assertTrue(line.contains("Done"), line);
		assertTrue(line.contains("claude-opus-5"), line);
		assertTrue(line.contains("thinking high"), line);
		assertTrue(line.contains(folder.toString()), "the whole path, not part of it: " + line);
	}

	@Test
	void theModelTheSessionReportsBeatsTheOneTheProfileAsksFor() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> {
			view.setLaunchFacts(java.nio.file.Path.of("."), "an-alias", "high");
			view.accept(new AgentEvent.SessionStarted("s1", "openrouter/glm-5.1", "thinking medium"), true);
			view.accept(new AgentEvent.TurnEnded(false, null, null, null), true);
		});

		var line = lastLabel(blocks(view));
		assertTrue(line.contains("openrouter/glm-5.1"), line);
		assertTrue(line.contains("thinking medium"), line);
		assertFalse(line.contains("an-alias"), line);
	}

	/** The text of the last label in the column, which is the line closing a turn. */
	private static String lastLabel(Container container) {
		String found = null;
		for (var child : container.getComponents()) {
			if (child instanceof JLabel label) {
				found = label.getText();
			} else if (child instanceof Container nested) {
				var deeper = lastLabel(nested);
				if (deeper != null) {
					found = deeper;
				}
			}
		}
		return found;
	}

	@Test
	void aReplyCopiesItselfAsTheMarkdownItWasWrittenIn() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var clipboard = clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.MessageChunk("Some **bold** text."), true));

		chooseMenuItem(firstEditorPane(blocks(view)), "Copy as Markdown");

		assertEquals("Some **bold** text.", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	@Test
	void whatTheUserSentCanBeCopiedWholeFromItsMenu() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var clipboard = clipboardFor(view);
		onEdt(() -> view.accept(new AgentEvent.UserMessage("hello there"), true));

		chooseMenuItem(firstTextArea(blocks(view)), "Copy All");

		assertEquals("hello there", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}
}
