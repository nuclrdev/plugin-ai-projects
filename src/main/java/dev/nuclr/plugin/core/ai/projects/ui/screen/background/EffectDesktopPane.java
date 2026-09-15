package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JDesktopPane;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.Timer;

/** A JDesktopPane whose background animation is supplied by a replaceable effect. */
public final class EffectDesktopPane extends JDesktopPane {

	private static final long serialVersionUID = 1L;
	private static final int FRAME_DELAY_MILLIS = 40;

	private final Map<String, DesktopBackgroundEffect> effects = new LinkedHashMap<>();
	private final Timer animationTimer;
	private final BackgroundCanvas backgroundCanvas = new BackgroundCanvas();
	private DesktopBackgroundEffect activeEffect;
	private String effectId;
	private long startedAt = System.nanoTime();
	private boolean interacting;
	/** Re-evaluates the animation whenever the application's active window changes. */
	private final PropertyChangeListener activeWindowListener = event -> updateAnimationState();

	/** Build a desktop pane with the requested effect, falling back safely when needed. */
	public EffectDesktopPane(List<DesktopBackgroundEffect> availableEffects, String initialEffectId) {
		if (availableEffects != null) {
			for (var effect : availableEffects) {
				if (effect != null && effect.id() != null && !effect.id().isBlank()) {
					effects.put(effect.id(), effect);
				}
			}
		}
		if (effects.isEmpty()) {
			throw new IllegalArgumentException("At least one desktop background effect is required");
		}
		setOpaque(true);
		setBackground(new Color(5, 8, 30));
		backgroundCanvas.setBackground(getBackground());
		animationTimer = new Timer(FRAME_DELAY_MILLIS, event -> animate());
		animationTimer.setCoalesce(true);
		animationTimer.stop();

		// Frames drag and resize with their contents showing, rather than as an
		// outline. The outline mode is not merely uglier here, it is invisible: it is
		// XOR-drawn straight onto the desktop's Graphics outside the paint cycle, and
		// the background repainting twenty-five times a second wipes it as fast as it
		// is drawn. See InteractionAwareDesktopManager for what pays for the change.
		setDragMode(LIVE_DRAG_MODE);
		setDesktopManager(new InteractionAwareDesktopManager());

		add(backgroundCanvas, JLayeredPane.DEFAULT_LAYER, 0);
		addHierarchyListener(event -> {
			if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				updateAnimationState();
			}
		});
		setEffect(initialEffectId);
	}

	/** The selectable effects, in picker order. */
	public List<DesktopBackgroundEffect> effects() {
		return List.copyOf(effects.values());
	}

	/** The stable ID of the active effect. */
	public String effectId() {
		return effectId;
	}

	/** Select an effect, falling back to the first registered effect for bad state. */
	public void setEffect(String requestedId) {
		var selected = effects.get(requestedId);
		if (selected == null) {
			selected = effects.values().iterator().next();
		}
		if (activeEffect == selected) {
			return;
		}
		activeEffect = selected;
		effectId = selected.id();
		startedAt = System.nanoTime();
		selected.reset();
		animationTimer.setDelay(Math.max(1, selected.frameDelayMillis()));
		backgroundCanvas.repaint();
		updateAnimationState();
	}

	@Override
	public void addNotify() {
		super.addNotify();
		var focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		focusManager.removePropertyChangeListener("activeWindow", activeWindowListener);
		focusManager.addPropertyChangeListener("activeWindow", activeWindowListener);
		updateAnimationState();
	}

	@Override
	public void removeNotify() {
		animationTimer.stop();
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.removePropertyChangeListener("activeWindow", activeWindowListener);
		super.removeNotify();
	}

	@Override
	public void doLayout() {
		super.doLayout();
		backgroundCanvas.setBounds(0, 0, getWidth(), getHeight());
	}

	/**
	 * One animation tick.
	 *
	 * <p>Only the background layer is asked to repaint. It used to walk the frames as
	 * well, to recomposite translucent ones over a backdrop that had moved underneath
	 * them; windows are always solid now, so there is nothing to recomposite.
	 *
	 * <p>Repainting the layer still redraws the windows above it, because a layered
	 * pane reports {@code isOptimizedDrawingEnabled() == false} - its children may
	 * overlap, so Swing cannot repaint one of them alone. That is the cost of an
	 * animated background and it is what the cached windows below are for.
	 */
	private void animate() {
		backgroundCanvas.repaint();
	}

	private void updateAnimationState() {
		if (!DesktopBackgroundEffects.NONE.equals(effectId) && isShowing() && !interacting && isWorkspaceActive()) {
			animationTimer.start();
		} else {
			animationTimer.stop();
		}
	}

	/**
	 * Whether the window holding this desktop is the one the user is working in.
	 *
	 * <p>A desktop in a background or minimised window still reports itself showing,
	 * so without this the effect kept burning CPU for nobody. A dialog opened from the
	 * workspace counts as the workspace being active, so the backdrop doesn't freeze
	 * behind every popup.
	 */
	private boolean isWorkspaceActive() {
		var owner = javax.swing.SwingUtilities.getWindowAncestor(this);
		var active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		for (Window window = active; window != null; window = window.getOwner()) {
			if (window == owner) {
				return true;
			}
		}
		return false;
	}

	/** Whether the background is currently animating. */
	public boolean isAnimating() {
		return animationTimer.isRunning();
	}

	/**
	 * Hold the background still while a frame is being dragged or resized.
	 *
	 * <p>A layered pane cannot repaint one child alone - its children may overlap, so
	 * {@code isOptimizedDrawingEnabled()} is false and any repaint redraws the windows
	 * over it. Dragging therefore competes with the animation for the same frames, and
	 * the animation is the half nobody is looking at while a window is under the
	 * cursor. Stopping it for the duration is what makes a live drag affordable.
	 *
	 * @param busy whether an interaction is in progress
	 */
	private void setInteracting(boolean busy) {
		if (interacting != busy) {
			interacting = busy;
			updateAnimationState();
		}
	}

	/** A desktop manager that tells the pane when a frame is being pushed around. */
	private final class InteractionAwareDesktopManager extends javax.swing.DefaultDesktopManager {

		private static final long serialVersionUID = 1L;

		@Override
		public void beginDraggingFrame(javax.swing.JComponent frame) {
			setInteracting(true);
			super.beginDraggingFrame(frame);
		}

		@Override
		public void endDraggingFrame(javax.swing.JComponent frame) {
			super.endDraggingFrame(frame);
			setInteracting(false);
		}

		@Override
		public void beginResizingFrame(javax.swing.JComponent frame, int direction) {
			setInteracting(true);
			super.beginResizingFrame(frame, direction);
		}

		@Override
		public void endResizingFrame(javax.swing.JComponent frame) {
			super.endResizingFrame(frame);
			setInteracting(false);
		}
	}

	/** Stop animation when the owning project closes. */
	public void disposeEffect() {
		animationTimer.stop();
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.removePropertyChangeListener("activeWindow", activeWindowListener);
		if (activeEffect != null) {
			activeEffect.reset();
		}
	}

	/** Paints only the background layer, avoiding full agent-frame repaints. */
	private final class BackgroundCanvas extends JComponent {

		private static final long serialVersionUID = 1L;

		private BackgroundCanvas() {
			setOpaque(true);
			setBackground(EffectDesktopPane.this.getBackground());
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			if (activeEffect == null) {
				return;
			}
			var g = (Graphics2D) graphics.create();
			try {
				activeEffect.paint(g, getWidth(), getHeight(),
						Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
			} finally {
				g.dispose();
			}
		}
	}
}
