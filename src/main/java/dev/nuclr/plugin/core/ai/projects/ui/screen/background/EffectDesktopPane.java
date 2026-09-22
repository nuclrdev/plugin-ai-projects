package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowStateListener;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JDesktopPane;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** A JDesktopPane whose background animation is supplied by a replaceable effect. */
public final class EffectDesktopPane extends JDesktopPane {

	private static final long serialVersionUID = 1L;
	private static final int FRAME_DELAY_MILLIS = 40;

	private final Map<String, DesktopBackgroundEffect> effects = new LinkedHashMap<>();
	private final Timer animationTimer;
	private final BackgroundCanvas backgroundCanvas = new BackgroundCanvas();
	/** Used only by effects that opt into off-EDT rendering. */
	private ExecutorService renderWorker;
	/** The EDT displays frontFrame; the worker may only touch a detached spareFrame. */
	private BufferedImage frontFrame;
	private BufferedImage spareFrame;
	private boolean renderPending;
	private boolean resetAsyncEffect;
	private boolean disposed;
	private long effectGeneration;
	private DesktopBackgroundEffect activeEffect;
	private String effectId;
	private long startedAt = System.nanoTime();
	private boolean interacting;
	private boolean animateWhenInactive = true;
	/** Re-evaluates the animation whenever the application's active window changes. */
	private final PropertyChangeListener activeWindowListener = event -> updateAnimationState();
	/** Re-evaluates the animation whenever the owning window is minimised or restored. */
	private final WindowStateListener windowStateListener = event -> updateAnimationState();
	private Window observedWindow;

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
		effectGeneration++;
		frontFrame = null;
		spareFrame = null;
		resetAsyncEffect = selected.renderOffEdt();
		if (!resetAsyncEffect) {
			selected.reset();
		}
		animationTimer.setDelay(Math.max(1, selected.frameDelayMillis()));
		backgroundCanvas.repaint();
		updateAnimationState();
	}

	@Override
	public void addNotify() {
		super.addNotify();
		observeWindow(javax.swing.SwingUtilities.getWindowAncestor(this));
		var focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		focusManager.removePropertyChangeListener("activeWindow", activeWindowListener);
		focusManager.addPropertyChangeListener("activeWindow", activeWindowListener);
		updateAnimationState();
	}

	@Override
	public void removeNotify() {
		animationTimer.stop();
		observeWindow(null);
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
	 * One animation tick. Opted-in effects render on a worker and repaint only
	 * after a complete frame has been published.
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
		if (disposed) return;
		if (activeEffect.renderOffEdt()) {
			requestAsyncFrame();
		} else {
			backgroundCanvas.repaint();
		}
	}

	/** Submit at most one background render at a time; missed ticks are dropped. */
	private void requestAsyncFrame() {
		if (disposed || renderPending || activeEffect == null || !activeEffect.renderOffEdt()) return;
		int width = backgroundCanvas.getWidth();
		int height = backgroundCanvas.getHeight();
		if (width <= 0 || height <= 0) return;
		if (renderWorker == null) {
			renderWorker = Executors.newSingleThreadExecutor(task -> {
				var thread = new Thread(task, "ai-desktop-background");
				thread.setDaemon(true);
				return thread;
			});
		}
		var effect = activeEffect;
		var generation = effectGeneration;
		var frame = spareFrame != null && spareFrame.getWidth() == width && spareFrame.getHeight() == height
				? spareFrame : new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		spareFrame = null;
		var background = backgroundCanvas.getBackground();
		var elapsedMillis = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
		var reset = resetAsyncEffect;
		resetAsyncEffect = false;
		renderPending = true;
		renderWorker.execute(() -> {
			boolean rendered = false;
			try {
				if (reset) effect.reset();
				var graphics = frame.createGraphics();
				try {
					graphics.setColor(background);
					graphics.fillRect(0, 0, width, height);
					effect.paint(graphics, width, height, elapsedMillis);
					rendered = true;
				} finally {
					graphics.dispose();
				}
			} finally {
				boolean completed = rendered;
				SwingUtilities.invokeLater(() -> finishAsyncFrame(effect, generation, frame, completed));
			}
		});
	}

	/** Publish only complete frames for the currently selected effect, on the EDT. */
	private void finishAsyncFrame(DesktopBackgroundEffect effect, long generation,
			BufferedImage frame, boolean rendered) {
		renderPending = false;
		if (disposed) return;
		if (generation == effectGeneration && activeEffect == effect && rendered) {
			spareFrame = frontFrame;
			frontFrame = frame;
			backgroundCanvas.repaint();
		} else if (generation != effectGeneration && activeEffect.renderOffEdt()) {
			requestAsyncFrame();
		}
	}

	private void updateAnimationState() {
		if (!DesktopBackgroundEffects.NONE.equals(effectId) && isShowing() && !interacting && !isMinimised()
				&& (animateWhenInactive || isWorkspaceActive())) {
			animationTimer.start();
		} else {
			animationTimer.stop();
		}
	}

	/** Whether the background keeps moving while another application has focus. */
	public boolean isAnimateWhenInactive() {
		return animateWhenInactive;
	}

	/**
	 * Keep the background moving while the workspace is unfocused, or hold it still.
	 *
	 * <p>An unfocused window is still on screen, so some want the backdrop alive there;
	 * others would rather not pay a repaint every frame for a window they aren't using.
	 *
	 * @param animate whether to keep animating when the workspace is not active
	 */
	public void setAnimateWhenInactive(boolean animate) {
		if (animateWhenInactive != animate) {
			animateWhenInactive = animate;
			updateAnimationState();
		}
	}

	/**
	 * Whether the window holding this desktop is the one the user is working in.
	 *
	 * <p>A dialog opened from the workspace counts as the workspace being active, so
	 * the backdrop doesn't freeze behind every popup.
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

	/**
	 * Whether the window holding this desktop is minimised.
	 *
	 * <p>A desktop in a minimised window still reports itself showing, so without this
	 * the effect kept burning CPU for nobody, whatever the unfocused setting says.
	 */
	private boolean isMinimised() {
		return observedWindow instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0;
	}

	/** Move the minimise/restore listener to the given window, or detach it for null. */
	private void observeWindow(Window window) {
		if (observedWindow == window) {
			return;
		}
		if (observedWindow != null) {
			observedWindow.removeWindowStateListener(windowStateListener);
		}
		observedWindow = window;
		if (window != null) {
			window.addWindowStateListener(windowStateListener);
		}
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
		if (disposed) return;
		animationTimer.stop();
		disposed = true;
		effectGeneration++;
		observeWindow(null);
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.removePropertyChangeListener("activeWindow", activeWindowListener);
		if (activeEffect != null) {
			if (activeEffect.renderOffEdt() && renderWorker != null) {
				renderWorker.execute(activeEffect::reset);
			} else {
				activeEffect.reset();
			}
		}
		if (renderWorker != null) {
			renderWorker.shutdown();
		}
		frontFrame = null;
		spareFrame = null;
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
			if (activeEffect.renderOffEdt()) {
				if (frontFrame != null) {
					graphics.drawImage(frontFrame, 0, 0, getWidth(), getHeight(), null);
				}
				if (frontFrame == null || frontFrame.getWidth() != getWidth()
						|| frontFrame.getHeight() != getHeight()) {
					requestAsyncFrame();
				}
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
