package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * An oldschool plasma done properly: five interfering sine fields - two planar,
 * one diagonal, two radial around drifting centres - sampled through a slowly
 * warping coordinate space, pushed through a cycling 1024-entry palette, and then
 * bump-lit from its own height field so the colour reads as a glossy liquid
 * surface rather than a flat gradient.
 *
 * <p>Everything is computed per pixel straight into an {@code int[]} framebuffer
 * at a fraction of the desktop resolution, with sine and palette lookups instead
 * of trigonometry and colour objects, and scaled up with bilinear filtering.
 */
final class PlasmaEffect implements DesktopBackgroundEffect {

	/** Framebuffer pixels per desktop pixel, per axis. The plasma is smooth, so a third loses nothing. */
	private static final int DIVISOR = 3;
	private static final int MAX_FRAME_WIDTH = 640;
	/** Softens the radial fields' centres, which are otherwise a visible cusp. */
	private static final double RADIAL_SOFTNESS = 6.0;
	private static final int TABLE_BITS = 12;
	private static final int TABLE_SIZE = 1 << TABLE_BITS;
	private static final int TABLE_MASK = TABLE_SIZE - 1;
	private static final double TABLE_PER_RADIAN = TABLE_SIZE / (Math.PI * 2);
	/** Sine amplitude in the lookup table. */
	private static final int AMPLITUDE = 1024;
	private static final int PALETTE_SIZE = 1024;
	private static final int PALETTE_MASK = PALETTE_SIZE - 1;
	/**
	 * Converts the analytic slope - table steps per pixel times a cosine - into height
	 * change per pixel, scaled to a 640-sample-wide reference so the shading is equally
	 * deep at any framebuffer size.
	 */
	private static final double SLOPE_SCALE = Math.PI * 2 / TABLE_SIZE / 640.0;

	private static final int[] SINE = createSine();
	private static final int[] PALETTE = createPalette();

	private static final Scanlines SCANLINES = new Scanlines(new Color(0, 0, 0, 38), 3);

	private final CachedLayer finishLayer = new CachedLayer(false, 1);

	private BufferedImage frame;
	private int[] pixels;
	private double[] columnWarp;
	/** The plasma at desktop size, filled by {@link #upscale} and blitted 1:1. */
	private BufferedImage output;
	private int[] outputPixels;
	private int outputWidth;
	private int outputHeight;
	/** Per output column: the source column to its left and the 8-bit blend towards the next. */
	private int[] sourceColumn;
	private int[] columnBlend;
	private int frameWidth;
	private int frameHeight;

	@Override
	public String id() {
		return "plasma";
	}

	@Override
	public String displayName() {
		return "Liquid Plasma";
	}

	@Override
	public String description() {
		return "A bump-lit, domain-warped demoscene plasma with a cycling synthwave palette.";
	}

	@Override
	public void reset() {
		frame = null;
		pixels = null;
		output = null;
		outputPixels = null;
		finishLayer.discard();
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		ensureFrame(width, height);
		// The plasma runs on a clock six times slower than real time: a slow drift
		// rather than a demo-party strobe, since it sits behind people's work.
		render(elapsedMillis / 6_000.0);
		upscale();
		var g = (Graphics2D) graphics.create();
		try {
			// A same-size blit is close to a copy. Letting Java2D do the bilinear scale
			// instead was measured at 21ms for a 2560x1400 desktop, most of the frame.
			g.drawImage(output, 0, 0, null);
			finishLayer.paint(g, width, height, this::paintFinish);
		} finally {
			g.dispose();
		}
	}

	private void ensureFrame(int width, int height) {
		// A third of the desktop, but never more than MAX_FRAME_WIDTH across: the plasma
		// is smooth, so a 4K desktop gains nothing but cost from more samples.
		var divisor = Math.max(DIVISOR, (width + MAX_FRAME_WIDTH - 1) / MAX_FRAME_WIDTH);
		var w = Math.max(1, (width + divisor - 1) / divisor);
		var h = Math.max(1, (height + divisor - 1) / divisor);
		if (frame != null && w == frameWidth && h == frameHeight
				&& width == outputWidth && height == outputHeight) return;
		frameWidth = w;
		frameHeight = h;
		frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
		columnWarp = new double[w];

		outputWidth = width;
		outputHeight = height;
		output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		outputPixels = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
		sourceColumn = new int[width];
		columnBlend = new int[width];
		for (var x = 0; x < width; x++) {
			var position = sourcePosition(x, width, w);
			sourceColumn[x] = (int) position;
			columnBlend[x] = (int) ((position - (int) position) * 256);
		}
	}

	/**
	 * Render one frame into the framebuffer.
	 *
	 * <p>Frequencies are expressed against the framebuffer's longer side, so the
	 * pattern has the same character on any desktop size.
	 */
	private void render(double time) {
		var w = frameWidth;
		var h = frameHeight;
		var span = (double) Math.max(w, h);
		var unit = TABLE_SIZE / span;

		// Field frequencies, in table steps per framebuffer pixel.
		var f1 = unit * 2.3;
		var f2 = unit * 1.7;
		var f3 = unit * 1.3;
		var f4 = unit * 3.1;
		var f5 = unit * 2.2;

		// Phases drift at mutually irrational rates so the pattern never visibly repeats.
		var p1 = time * 0.37 * TABLE_PER_RADIAN;
		var p2 = -time * 0.29 * TABLE_PER_RADIAN;
		var p3 = time * 0.21 * TABLE_PER_RADIAN;
		var p4 = -time * 0.53 * TABLE_PER_RADIAN;
		var p5 = time * 0.41 * TABLE_PER_RADIAN;

		// Two radial sources wandering on Lissajous paths.
		var c1x = w * (0.5 + 0.34 * Math.sin(time * 0.13));
		var c1y = h * (0.5 + 0.30 * Math.cos(time * 0.17));
		var c2x = w * (0.5 + 0.38 * Math.cos(time * 0.11 + 1.3));
		var c2y = h * (0.5 + 0.34 * Math.sin(time * 0.19 + 0.7));

		// Domain warp: every row slides sideways and every column slides vertically,
		// on their own slow sines. Cheap, and it turns stripes into liquid.
		var warpAmplitude = span * 0.09 / AMPLITUDE;
		var warpFrequency = unit * 1.1;
		var rowPhase = time * 0.23 * TABLE_PER_RADIAN;
		var columnPhase = -time * 0.19 * TABLE_PER_RADIAN;
		// Kept fractional: rounding the warp to whole pixels made neighbouring columns
		// jump in height, which the bump lighting turned into vertical streaks.
		for (var x = 0; x < w; x++) {
			columnWarp[x] = SINE[(int) (x * warpFrequency + columnPhase) & TABLE_MASK] * warpAmplitude;
		}

		var paletteShift = (int) (time * 26);
		var soft = RADIAL_SOFTNESS * RADIAL_SOFTNESS;
		// Rows are independent - each lights itself from its own field function, not
		// from the row above - so they render in parallel.
		java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
			var rowWarp = SINE[(int) (y * warpFrequency + rowPhase) & TABLE_MASK] * warpAmplitude;
			var offset = y * w;
			for (var x = 0; x < w; x++) {
				var wx = x + rowWarp;
				var wy = y + columnWarp[x];

				var dx1 = wx - c1x;
				var dy1 = wy - c1y;
				var dx2 = wx - c2x;
				var dy2 = wy - c2y;
				var r1 = Math.sqrt(dx1 * dx1 + dy1 * dy1 + soft);
				var r2 = Math.sqrt(dx2 * dx2 + dy2 * dy2 + soft);
				var height = field(wx * f1 + p1) + field(wy * f2 + p2) + field((wx + wy) * f3 + p3)
						+ field(r1 * f4 + p4) + field(r2 * f5 + p5);

				// Bump lighting from the height field's analytic slope towards a light up
				// and to the left: the derivative of each sine term along (-1, -1), taken
				// through a quarter-turn table offset, so it is smooth rather than a
				// difference of neighbouring, quantised samples.
				var slope = -(fieldSlope(wx * f1 + p1) * f1
						+ fieldSlope(wy * f2 + p2) * f2
						+ fieldSlope((wx + wy) * f3 + p3) * f3 * 2
						+ fieldSlope(r1 * f4 + p4) * f4 * ((dx1 + dy1) / r1)
						+ fieldSlope(r2 * f5 + p5) * f5 * ((dx2 + dy2) / r2)) * SLOPE_SCALE * span;
				var diffuse = (int) (256 + slope * 3);
				if (diffuse < 120) diffuse = 120;
				else if (diffuse > 430) diffuse = 430;
				var glint = slope - 30;
				var specular = glint > 0 ? (int) Math.min(90, glint * glint / 10) : 0;

				var color = PALETTE[(((int) height >> 3) + paletteShift) & PALETTE_MASK];
				var r = (((color >> 16) & 0xFF) * diffuse >> 8) + specular;
				var gr = (((color >> 8) & 0xFF) * diffuse >> 8) + specular;
				var b = ((color & 0xFF) * diffuse >> 8) + specular;
				pixels[offset + x] = (r > 255 ? 255 : r) << 16 | (gr > 255 ? 255 : gr) << 8 | (b > 255 ? 255 : b);
			}
		});
	}

	/**
	 * Bilinear upscale of the framebuffer to desktop size, in fixed point and in
	 * parallel rows. Java2D's own bilinear path interpolates every destination pixel
	 * in general-purpose software; this one knows the image is opaque RGB, that the
	 * column weights repeat on every row, and that there are cores to spare.
	 */
	private void upscale() {
		var sourceWidth = frameWidth;
		var sourceHeight = frameHeight;
		var source = pixels;
		var target = outputPixels;
		var width = outputWidth;
		var lastColumn = sourceWidth - 1;
		var lastRow = sourceHeight - 1;
		java.util.stream.IntStream.range(0, outputHeight).parallel().forEach(y -> {
			var position = sourcePosition(y, outputHeight, sourceHeight);
			var row = (int) position;
			var rowBlend = (int) ((position - row) * 256);
			var top = row * sourceWidth;
			var bottom = Math.min(row + 1, lastRow) * sourceWidth;
			var offset = y * width;
			for (var x = 0; x < width; x++) {
				var column = sourceColumn[x];
				var next = Math.min(column + 1, lastColumn);
				var blend = columnBlend[x];
				var upper = mix(source[top + column], source[top + next], blend);
				var lower = mix(source[bottom + column], source[bottom + next], blend);
				target[offset + x] = mix(upper, lower, rowBlend);
			}
		});
	}

	/** Where an output pixel's centre falls in the framebuffer, clamped to its edges. */
	private static double sourcePosition(int index, int outputSize, int sourceSize) {
		var position = (index + 0.5) * sourceSize / outputSize - 0.5;
		return Math.clamp(position, 0, sourceSize - 1);
	}

	/** Blend two packed RGB colours, {@code amount} out of 256 towards the second. */
	private static int mix(int a, int b, int amount) {
		var keep = 256 - amount;
		var r = (((a >> 16) & 0xFF) * keep + ((b >> 16) & 0xFF) * amount) >> 8;
		var g = (((a >> 8) & 0xFF) * keep + ((b >> 8) & 0xFF) * amount) >> 8;
		var bl = ((a & 0xFF) * keep + (b & 0xFF) * amount) >> 8;
		return r << 16 | g << 8 | bl;
	}

	/** Sine lookup with linear interpolation between entries, for a phase in table steps. */
	private static double field(double phase) {
		var floor = Math.floor(phase);
		var index = (int) floor & TABLE_MASK;
		var a = SINE[index];
		return a + (SINE[(index + 1) & TABLE_MASK] - a) * (phase - floor);
	}

	/** The matching cosine - the sine's derivative - read a quarter turn further on. */
	private static double fieldSlope(double phase) {
		return field(phase + TABLE_SIZE / 4.0);
	}

	private void paintFinish(Graphics2D g, int width, int height) {
		var radius = Math.max(width, height) * 0.78f;
		g.setPaint(new RadialGradientPaint(width / 2f, height / 2f, radius,
				new float[] { 0.35f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 200) }));
		g.fillRect(0, 0, width, height);
		SCANLINES.paint(g, width, height);
	}

	private static int[] createSine() {
		var table = new int[TABLE_SIZE];
		for (var i = 0; i < TABLE_SIZE; i++) {
			table[i] = (int) Math.round(Math.sin(i / TABLE_PER_RADIAN) * AMPLITUDE);
		}
		return table;
	}

	/**
	 * A seamless loop of smoothstepped colour stops. The near-black stops matter as
	 * much as the bright ones: they carve the plasma into bands with depth between them.
	 */
	private static int[] createPalette() {
		int[][] stops = {
				{ 4, 2, 18 }, { 38, 8, 92 }, { 186, 24, 150 }, { 255, 94, 72 }, { 255, 206, 120 },
				{ 120, 40, 110 }, { 6, 6, 30 }, { 10, 60, 120 }, { 30, 200, 240 }, { 190, 255, 250 },
				{ 60, 90, 200 }, { 20, 6, 60 }
		};
		var palette = new int[PALETTE_SIZE];
		for (var i = 0; i < PALETTE_SIZE; i++) {
			var position = i * stops.length / (double) PALETTE_SIZE;
			var index = (int) position;
			var from = stops[index];
			var to = stops[(index + 1) % stops.length];
			var t = position - index;
			t = t * t * (3 - 2 * t);
			var r = (int) Math.round(from[0] + (to[0] - from[0]) * t);
			var g = (int) Math.round(from[1] + (to[1] - from[1]) * t);
			var b = (int) Math.round(from[2] + (to[2] - from[2]) * t);
			palette[i] = r << 16 | g << 8 | b;
		}
		return palette;
	}
}
