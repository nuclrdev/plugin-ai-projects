package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A part of a background that looks the same in every frame, rendered once and
 * blitted thereafter.
 *
 * <p>The effects are mostly animation, but each one begins and ends with layers
 * that are pure function of the desktop's size: a gradient backdrop underneath
 * and a vignette over the top. Those were being rasterised on every frame, and a
 * full-screen {@link java.awt.RadialGradientPaint} is among the most expensive
 * things Java2D does — three or five of them per frame, twenty-five times a
 * second, for a picture that never changed.
 *
 * <p>Layers are cached at full size, and {@code divisor} exists only to say so
 * explicitly. Caching a smooth gradient at half size and scaling it back up looks
 * identical and uses a quarter of the memory, but it was measured at roughly
 * twenty times the cost of a straight blit: upscaling interpolates every
 * destination pixel in software, where a same-size blit is close to a copy. At
 * 2560x1400 a half-size backdrop cost 21ms a frame against 1ms full size, which
 * is the whole frame budget spent on saving 8MB.
 */
final class CachedLayer {

	/** Draws the layer at the full target size; the layer scales it down if asked to. */
	interface Painter {

		/**
		 * Paint the layer.
		 *
		 * @param graphics where to paint, already scaled so that the full size applies
		 * @param width    the target width, in pixels
		 * @param height   the target height, in pixels
		 */
		void paint(Graphics2D graphics, int width, int height);
	}

	private final boolean opaque;
	private final int divisor;

	private BufferedImage image;
	private int width;
	private int height;

	/**
	 * Create a layer.
	 *
	 * @param opaque  whether the layer covers everything beneath it, which lets it
	 *                skip per-pixel blending on the way out
	 * @param divisor how much smaller than the target to render; 1 is full size
	 */
	CachedLayer(boolean opaque, int divisor) {
		this.opaque = opaque;
		this.divisor = Math.max(1, divisor);
	}

	/**
	 * Draw the layer, rendering it first if the size changed.
	 *
	 * @param target  where to draw
	 * @param width   the area's width
	 * @param height  the area's height
	 * @param painter draws the layer when it has to be built
	 */
	void paint(Graphics2D target, int width, int height, Painter painter) {

		if (width <= 0 || height <= 0) {
			return;
		}
		if (image == null || this.width != width || this.height != height) {
			rebuild(width, height, painter);
		}
		if (image == null) {
			// The image could not be created, so paint straight through rather than
			// dropping the layer: slow is better than missing.
			painter.paint(target, width, height);
			return;
		}
		if (divisor == 1) {
			target.drawImage(image, 0, 0, null);
		} else {
			var g = (Graphics2D) target.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(image, 0, 0, width, height, null);
			} finally {
				g.dispose();
			}
		}
	}

	/** Release the cached image, for a reset or a closing desktop. */
	void discard() {
		image = null;
		width = 0;
		height = 0;
	}

	private void rebuild(int targetWidth, int targetHeight, Painter painter) {

		var pixelWidth = Math.max(1, targetWidth / divisor);
		var pixelHeight = Math.max(1, targetHeight / divisor);
		BufferedImage built;
		try {
			built = new BufferedImage(pixelWidth, pixelHeight,
					opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB_PRE);
		} catch (OutOfMemoryError | IllegalArgumentException e) {
			image = null;
			return;
		}
		var g = built.createGraphics();
		try {
			// Quality is worth paying for exactly once, which is the point of caching.
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			// The painter works in target coordinates whatever size the image is, so the
			// gradients it builds can be expressed against the desktop it will cover.
			g.scale(1.0 / divisor, 1.0 / divisor);
			painter.paint(g, targetWidth, targetHeight);
		} finally {
			g.dispose();
		}
		image = built;
		width = targetWidth;
		height = targetHeight;
	}
}
