package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;

import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.ui.screen.background.DesktopBackgroundEffects;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.EffectDesktopPane;

/**
 * Dragging a frame on the project desktop.
 *
 * <p>Outline dragging is not just plainer than live dragging here, it is invisible.
 * {@code DefaultDesktopManager} XOR-draws the outline straight onto the desktop's
 * {@code Graphics}, outside the paint cycle, and the animated background repaints
 * over it twenty-five times a second. A user dragging a window saw nothing move.
 */
class FrameDraggingTest {

	private static EffectDesktopPane pane() throws InterruptedException, InvocationTargetException {
		var built = new EffectDesktopPane[1];
		SwingUtilities.invokeAndWait(() ->
				built[0] = new EffectDesktopPane(DesktopBackgroundEffects.builtIn(), "neon-network"));
		return built[0];
	}

	@Test
	void framesDragWithTheirContentsShowing() throws Exception {
		assertEquals(JDesktopPane.LIVE_DRAG_MODE, pane().getDragMode(),
				"outline dragging is erased by the background and shows the user nothing");
	}

	@Test
	void draggingActuallyMovesTheFrameRatherThanOnlyDrawingOverIt() throws Exception {

		// Live dragging moves the frame through setBoundsForFrame, which is a normal
		// repaint; outline dragging left the bounds alone until the mouse was released
		// and drew over the top meanwhile. The manager's dragFrame itself cannot be
		// called here - it reaches for the desktop's Graphics, which is null without a
		// realised window and this suite runs headless - so this covers the step it
		// delegates to.
		var desktop = pane();
		var frame = new JInternalFrame("agent", true, true, true, true);
		SwingUtilities.invokeAndWait(() -> {
			desktop.setSize(800, 600);
			frame.setBounds(20, 20, 300, 200);
			desktop.add(frame);
			frame.setVisible(true);
			desktop.getDesktopManager().setBoundsForFrame(frame, 140, 90, 300, 200);
		});

		assertEquals(140, frame.getX());
		assertEquals(90, frame.getY());
	}

	@Test
	void theBackgroundHoldsStillWhileAFrameIsBeingPushedAround() throws Exception {

		var desktop = pane();
		var frame = new JInternalFrame("agent", true, true, true, true);
		var duringDrag = new boolean[1];
		var duringResize = new boolean[1];

		SwingUtilities.invokeAndWait(() -> {
			desktop.setSize(800, 600);
			frame.setBounds(20, 20, 300, 200);
			desktop.add(frame);
			frame.setVisible(true);

			var manager = desktop.getDesktopManager();
			manager.beginDraggingFrame(frame);
			duringDrag[0] = desktop.isAnimating();
			manager.endDraggingFrame(frame);

			manager.beginResizingFrame(frame, java.awt.event.MouseEvent.MOUSE_DRAGGED);
			duringResize[0] = desktop.isAnimating();
			manager.endResizingFrame(frame);
		});

		// A layered pane cannot repaint its background alone, so an animation tick and a
		// drag compete for the same repaints; the animation is the half nobody is
		// watching while a window is under the cursor.
		assertFalse(duringDrag[0], "the background should hold still while a frame is dragged");
		assertFalse(duringResize[0], "the background should hold still while a frame is resized");
	}

	@Test
	void anUnshownDesktopIsNotAnimatingAnyway() throws Exception {
		// The guard the pause rides on: it must not start the timer on a pane nobody
		// is looking at, which is also what keeps this suite from leaving one running.
		assertFalse(pane().isAnimating());
	}

	@Test
	void aDesktopWithNoEffectStaysStillWhateverHappens() throws Exception {

		var built = new EffectDesktopPane[1];
		SwingUtilities.invokeAndWait(() -> built[0] =
				new EffectDesktopPane(DesktopBackgroundEffects.builtIn(), DesktopBackgroundEffects.NONE));

		assertEquals(DesktopBackgroundEffects.NONE, built[0].effectId());
		assertFalse(built[0].isAnimating());
		assertTrue(built[0].getDragMode() == JDesktopPane.LIVE_DRAG_MODE);
	}
}
