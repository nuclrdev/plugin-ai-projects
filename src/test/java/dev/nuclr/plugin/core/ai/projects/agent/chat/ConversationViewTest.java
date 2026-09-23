package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JLabel;
import javax.swing.JButton;
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

	@Test
	void eachUserPromptHasACopyIconThatCopiesItsFullText() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		var clipboard = clipboardFor(view);
		onEdt(() -> {
			view.accept(new AgentEvent.UserMessage("first line\nsecond line"), true);
			view.accept(new AgentEvent.UserMessage("another prompt"), true);
		});

		var first = (Container) blocks(view).getComponent(0);
		var actions = (Container) first.getComponent(1);
		var copy = (JButton) actions.getComponent(0);
		assertNotNull(copy.getIcon());
		assertEquals("Copy to clipboard", copy.getToolTipText());
		onEdt(copy::doClick);
		assertEquals("first line\nsecond line", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));

		var second = (Container) blocks(view).getComponent(2);
		var secondActions = (Container) second.getComponent(1);
		var secondCopy = (JButton) secondActions.getComponent(0);
		onEdt(secondCopy::doClick);
		assertEquals("another prompt", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	/** Lay the view out at a size, as a window would. */
	private static void layOut(java.awt.Container container) {
		container.doLayout();
		for (var child : container.getComponents()) {
			if (child instanceof java.awt.Container nested) {
				layOut(nested);
			}
		}
	}

	@Test
	void theLastPromptIsPinnedOnceTheAgentsOutputPushesItOutOfSight() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> {
			view.setSize(400, 200);
			view.accept(new AgentEvent.UserMessage("Fix the build"), true);
			layOut(view);
			view.updatePin();
		});
		onEdt(() -> assertFalse(view.promptPinned(), "pinned while the prompt is in view"));

		onEdt(() -> {
			for (var index = 0; index < 40; index++) {
				view.accept(new AgentEvent.Notice("Working on step " + index, false), true);
			}
			layOut(view);
			var viewport = ((JScrollPane) view.getComponent(0)).getViewport();
			viewport.setViewPosition(new java.awt.Point(0, viewport.getView().getHeight() - viewport.getHeight()));
			view.updatePin();
		});
		onEdt(() -> assertTrue(view.promptPinned(), "not pinned once the prompt scrolled away"));

		onEdt(() -> {
			view.scrollToPrompt();
			layOut(view);
			view.updatePin();
		});
		// Twice: going back must not queue a scroll that takes the page to the end again.
		onEdt(() -> assertFalse(view.promptPinned(), "still pinned after going back to the prompt"));
		onEdt(() -> assertFalse(view.promptPinned(), "scrolled away from the prompt again"));
	}

	@Test
	void thePinnedPromptPulsesOnlyWhileShownAndTheAgentIsWorking() throws Exception {

		var view = new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
		onEdt(() -> {
			view.setSize(400, 200);
			view.accept(new AgentEvent.UserMessage("Fix the build"), true);
			view.setWorking(true);
			layOut(view);
			view.updatePin();
		});
		onEdt(() -> assertFalse(view.promptPulsing(), "pulsing with no bar to show"));

		onEdt(() -> {
			for (var index = 0; index < 40; index++) {
				view.accept(new AgentEvent.Notice("Working on step " + index, false), true);
			}
			layOut(view);
			var viewport = ((JScrollPane) view.getComponent(0)).getViewport();
			viewport.setViewPosition(new java.awt.Point(0, viewport.getView().getHeight() - viewport.getHeight()));
			view.updatePin();
		});
		onEdt(() -> assertTrue(view.promptPulsing(), "a pinned prompt the agent is working on is still"));

		onEdt(() -> view.setWorking(false));
		onEdt(() -> assertFalse(view.promptPulsing(), "still pulsing after the turn ended"));
	}

	// ------------------------------------------------------------------ what was sent with a prompt

	@org.junit.jupiter.api.io.TempDir
	java.nio.file.Path runtime;

	private AgentEvent.Attachment sentPicture(int width, int height) throws java.io.IOException {
		return Attachments.image(runtime, new java.awt.image.BufferedImage(width, height,
				java.awt.image.BufferedImage.TYPE_INT_RGB), "Pasted image 1");
	}

	/** Every label under a component, in order. */
	private static java.util.List<JLabel> labels(Container container) {
		var found = new java.util.ArrayList<JLabel>();
		for (var child : container.getComponents()) {
			if (child instanceof JLabel label) {
				found.add(label);
			}
			if (child instanceof Container nested) {
				found.addAll(labels(nested));
			}
		}
		return found;
	}

	/** Every text area under a component, in order. */
	private static java.util.List<JTextArea> textAreas(Container container) {
		var found = new java.util.ArrayList<JTextArea>();
		for (var child : container.getComponents()) {
			if (child instanceof JTextArea area) {
				found.add(area);
			}
			if (child instanceof Container nested) {
				found.addAll(textAreas(nested));
			}
		}
		return found;
	}

	private static void leftClick(JLabel label) {
		var click = new java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
				System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 3, 3, 1, false,
				java.awt.event.MouseEvent.BUTTON1);
		for (var listener : label.getMouseListeners()) {
			listener.mouseClicked(click);
		}
	}

	@Test
	void aSentPictureIsAThumbnailThatFitsItsBox() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var picture = sentPicture(1200, 400);

		onEdt(() -> view.accept(new AgentEvent.UserMessage("look", java.util.List.of(picture)), true));

		var thumbnail = labels(blocks(view)).stream().filter(label -> label.getIcon() instanceof javax.swing.ImageIcon)
				.findFirst().orElseThrow(() -> new AssertionError("no thumbnail"));
		assertEquals(240, thumbnail.getIcon().getIconWidth());
		assertEquals(80, thumbnail.getIcon().getIconHeight());
		assertTrue(thumbnail.getToolTipText().contains("1200x400"), thumbnail.getToolTipText());
		var menu = thumbnail.getComponentPopupMenu();
		assertNotNull(menu, "no menu on the thumbnail");
		assertEquals("Copy to clipboard", ((javax.swing.JMenuItem) menu.getComponent(1)).getText());
	}

	@Test
	void aSentPictureWhoseFileHasGoneSaysSoInsteadOfVanishing() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var picture = sentPicture(20, 20);
		java.nio.file.Files.delete(picture.file());

		onEdt(() -> view.accept(new AgentEvent.UserMessage("look", java.util.List.of(picture)), true));

		assertTrue(labels(blocks(view)).stream().anyMatch(label -> "Pasted image 1 (the file is gone)".equals(label.getText())));
	}

	@Test
	void aSentPasteIsShownInPlaceWhenClickedAndHiddenAgain() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var paste = Attachments.text(runtime, "ERROR boom\nat Foo.java:1\n", "Pasted text 1");

		onEdt(() -> view.accept(new AgentEvent.UserMessage("why?", java.util.List.of(paste)), true));

		var chip = labels(blocks(view)).stream().filter(label -> label.getText().startsWith("Pasted text 1"))
				.findFirst().orElseThrow(() -> new AssertionError("no chip for the paste"));
		assertTrue(chip.getText().contains("2 lines"), chip.getText());
		assertEquals(1, textAreas(blocks(view)).size(), "only the words, until the paste is opened");

		onEdt(() -> leftClick(chip));
		var shown = textAreas(blocks(view));
		assertEquals(2, shown.size());
		assertEquals("ERROR boom\nat Foo.java:1", shown.get(1).getText());
		assertEquals(java.awt.Font.MONOSPACED, shown.get(1).getFont().getFamily());

		onEdt(() -> leftClick(chip));
		assertEquals(1, textAreas(blocks(view)).size(), "clicked again, the paste stayed open");
	}

	@Test
	void copyingAPromptCopiesItAsTheAgentGotIt() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var clipboard = clipboardFor(view);
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");

		var picture = sentPicture(10, 10);

		onEdt(() -> view.accept(new AgentEvent.UserMessage("why?", java.util.List.of(picture, paste)), true));

		var block = (Container) blocks(view).getComponent(0);
		var copy = (JButton) ((Container) block.getComponent(1)).getComponent(0);
		onEdt(copy::doClick);
		assertEquals("<pasted_text name=\"Pasted text 1\">\na log\n</pasted_text>\n\nwhy?",
				clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	@Test
	void aPromptOfPicturesAloneCopiesAsWhatItCarried() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var clipboard = clipboardFor(view);

		var picture = sentPicture(10, 10);

		onEdt(() -> view.accept(new AgentEvent.UserMessage("", java.util.List.of(picture)), true));

		var block = (Container) blocks(view).getComponent(0);
		var copy = (JButton) ((Container) block.getComponent(1)).getComponent(0);
		onEdt(copy::doClick);
		assertEquals("[1 image]", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
	}

	@Test
	void thePinnedBarNamesWhatAPromptWithoutWordsCarried() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var first = sentPicture(10, 10);
		var second = Attachments.image(runtime, new java.awt.image.BufferedImage(11, 10,
				java.awt.image.BufferedImage.TYPE_INT_RGB), "Pasted image 2");
		onEdt(() -> {
			view.setSize(400, 200);
			view.accept(new AgentEvent.UserMessage("", java.util.List.of(first, second)), true);
			for (var index = 0; index < 40; index++) {
				view.accept(new AgentEvent.Notice("Working on step " + index, false), true);
			}
			layOut(view);
			var viewport = ((JScrollPane) view.getComponent(0)).getViewport();
			viewport.setViewPosition(new java.awt.Point(0, viewport.getView().getHeight() - viewport.getHeight()));
			view.updatePin();
		});

		onEdt(() -> assertTrue(view.promptPinned()));
		var pin = (Container) ((JScrollPane) view.getComponent(0)).getColumnHeader().getView();
		assertTrue(labels(pin).stream().anyMatch(label -> "[2 images]".equals(label.getText())),
				labels(pin).stream().map(JLabel::getText).toList().toString());
	}

	@Test
	void theCaptionsOfWhatWasSentGrowWithTheZoom() throws Exception {
		var view = new ConversationView((requestId, option) -> {
		});
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		onEdt(() -> view.accept(new AgentEvent.UserMessage("why?", java.util.List.of(paste)), true));
		var chip = labels(blocks(view)).stream().filter(label -> label.getText().startsWith("Pasted text 1"))
				.findFirst().orElseThrow();
		var before = chip.getFont().getSize2D();

		onEdt(() -> view.zoom(3));

		assertTrue(chip.getFont().getSize2D() > before, before + " then " + chip.getFont().getSize2D());
	}
}
