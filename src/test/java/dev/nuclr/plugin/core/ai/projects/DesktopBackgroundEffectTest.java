package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.ui.screen.background.DesktopBackgroundEffect;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.DesktopBackgroundEffects;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.EffectDesktopPane;

/** Rendering and selection contracts for replaceable desktop backgrounds. */
class DesktopBackgroundEffectTest {

	@Test
	void everyBuiltInEffectCanRenderSeveralFrames() {

		var image = new BufferedImage(900, 560, BufferedImage.TYPE_INT_ARGB);
		for (var effect : DesktopBackgroundEffects.builtIn()) {
			 effect.reset();
			for (var frame = 0; frame < 4; frame++) {
				var graphics = image.createGraphics();
				try {
					effect.paint(graphics, image.getWidth(), image.getHeight(), frame * 33L);
				} finally {
					graphics.dispose();
				}
			}
		}
		assertTrue(image.getWidth() > 0);
	}

	@Test
	void thePaneKeepsEffectsIsolatedAndFallsBackForUnknownState() {

		var pane = new EffectDesktopPane(DesktopBackgroundEffects.builtIn(), "does-not-exist");
		try {
			assertEquals(DesktopBackgroundEffects.NONE, pane.effects().getFirst().id());
			assertNotNull(pane.effectId());
			pane.setEffect("starfield");
			assertEquals("starfield", pane.effectId());
			pane.setEffect("matrix-rain");
			assertEquals("matrix-rain", pane.effectId());
			pane.setEffect("steampunk-engine");
			assertEquals("steampunk-engine", pane.effectId());
			pane.setEffect("does-not-exist");
			assertEquals(DesktopBackgroundEffects.NONE, pane.effectId());
		} finally {
			pane.disposeEffect();
		}
	}

	@Test
	void theBuiltInCollectionHasDistinctStableIds() {

		var ids = DesktopBackgroundEffects.builtIn().stream().map(DesktopBackgroundEffect::id).toList();
		assertEquals(ids.stream().distinct().count(), ids.size());
		assertTrue(ids.contains("neon-network"));
		assertTrue(ids.contains("matrix-rain"));
		assertTrue(ids.contains("steampunk-engine"));
		assertTrue(ids.contains("helix-sequencer"));
		assertTrue(ids.contains("plasma"));
		assertTrue(ids.contains("orrery"));
		assertTrue(ids.contains("starfield"));
	}

	@Test
	void everyAnimatedBuiltInEffectRendersAwayFromTheEventDispatchThread() {
		for (var effect : DesktopBackgroundEffects.builtIn()) {
			if (!DesktopBackgroundEffects.NONE.equals(effect.id())) {
				assertTrue(effect.renderOffEdt(), effect.id());
			}
		}
	}

	@Test
	void optedInEffectPaintsOnAWorker() throws Exception {
		var painted = new CountDownLatch(1);
		var paintedOnEdt = new AtomicBoolean(true);
		DesktopBackgroundEffect effect = new DesktopBackgroundEffect() {
			@Override public String id() { return "async-test"; }
			@Override public String displayName() { return "Async test"; }
			@Override public String description() { return "Async test"; }
			@Override public boolean renderOffEdt() { return true; }
			@Override public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
				paintedOnEdt.set(SwingUtilities.isEventDispatchThread());
				painted.countDown();
			}
		};
		var pane = new EffectDesktopPane(List.of(effect), effect.id());
		try {
			SwingUtilities.invokeAndWait(() -> {
				pane.setSize(100, 100);
				pane.doLayout();
				var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
				var graphics = image.createGraphics();
				try {
					pane.paint(graphics);
				} finally {
					graphics.dispose();
				}
			});
			assertTrue(painted.await(5, TimeUnit.SECONDS));
			assertFalse(paintedOnEdt.get());
		} finally {
			SwingUtilities.invokeAndWait(pane::disposeEffect);
		}
	}
}
