package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;
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
		backgroundCanvas.repaint();
		updateAnimationState();
	}

	@Override
	public void addNotify() {
		super.addNotify();
		updateAnimationState();
	}

	@Override
	public void removeNotify() {
		animationTimer.stop();
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
	 * <p>Normally only the background layer is repainted, which is the whole point
	 * of having it as its own component. A translucent frame is the exception: the
	 * moving background shows through it, so it has to be composited again or it
	 * freezes over a backdrop that is still moving. Repainting a non-opaque child
	 * pulls the layer beneath it along, so this stays limited to the frames that
	 * actually need it.
	 */
	private void animate() {
		backgroundCanvas.repaint();
		for (var frame : getAllFrames()) {
			if (!frame.isOpaque() && frame.isVisible()) {
				frame.repaint();
			}
		}
	}

	private void updateAnimationState() {
		if (!DesktopBackgroundEffects.NONE.equals(effectId) && isShowing()) {
			animationTimer.start();
		} else {
			animationTimer.stop();
		}
	}

	/** Stop animation when the owning project closes. */
	public void disposeEffect() {
		animationTimer.stop();
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
