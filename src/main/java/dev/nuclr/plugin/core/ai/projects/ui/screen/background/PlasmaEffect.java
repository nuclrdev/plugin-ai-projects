package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.stream.IntStream;

/**
 * An oldschool plasma done properly: five interfering sine fields - two planar,
 * one diagonal, two radial around drifting centres - sampled through a slowly
 * warping coordinate space, pushed through a cycling 1024-entry palette, and then
 * bump-lit from its own height field so the colour reads as a liquid surface
 * rather than a flat gradient.
 *
 * <p>The CPU does as little as possible and Java2D's GPU pipeline does the rest:
 * <ul>
 * <li>Frames are rendered small - a sixth of the desktop, at most 320 across - and
 * handed to {@code drawImage} with bilinear filtering, which the Direct3D and
 * OpenGL pipelines turn into one filtered textured quad. Scaling on the CPU meant
 * building and uploading a full-desktop image every frame.
 * <li>The plasma drifts slowly, so it is only rendered every {@link #KEYFRAME_MILLIS}
 * and the frames between are crossfades of the two latest keyframes. The images
 * are written through the raster rather than a stolen data array, so Java2D keeps
 * them managed: each keyframe is uploaded once and blended as a cached texture.
 * <li>The vignette and scanlines never change and sit in a cached overlay, which
 * the pipeline also keeps as a texture.
 * </ul>
 *
 * <p>Within a keyframe the planar fields are separable - the warp moves whole rows
 * sideways and whole columns vertically - so {@code sin(a + b) = sin a cos b + cos a sin b}
 * reduces each to two multiplies against per-row and per-column values, with the
 * cosines the lighting needs coming for free. Only the radial fields cost a square
 * root and a table read per pixel.
 */
final class PlasmaEffect implements DesktopBackgroundEffect {

	/** Framebuffer pixels per desktop pixel, per axis. The GPU's bilinear filter hides the rest. */
	private static final int DIVISOR = 6;
	private static final int MAX_FRAME_WIDTH = 320;
	/** Paint rate. Each paint also redraws the agent windows above, so fewer is better. */
	private static final int FRAME_DELAY_MILLIS = 80;
	/** How often a new plasma frame is computed; paints in between crossfade. */
	private static final int KEYFRAME_MILLIS = 240;
	/** Softens the radial fields' centres, which are otherwise a visible cusp. */
	private static final double RADIAL_SOFTNESS = 6.0;
	private static final int TABLE_BITS = 12;
	private static final int TABLE_SIZE = 1 << TABLE_BITS;
	private static final int TABLE_MASK = TABLE_SIZE - 1;
	private static final double TABLE_PER_RADIAN = TABLE_SIZE / (Math.PI * 2);
	/** Height of one sine field; five of them span the palette eight times over. */
	private static final double AMPLITUDE = 1024;
	private static final int PALETTE_SIZE = 1024;
	private static final int PALETTE_MASK = PALETTE_SIZE - 1;
	/**
	 * Turns a field's radians-per-framebuffer-pixel frequency times its cosine into
	 * slope, so the lighting is equally deep at any framebuffer size: shading is
	 * referenced to a 640-sample-wide frame and frequencies to the longer side.
	 */
	private static final double SLOPE_SCALE = -AMPLITUDE / 640.0;

	/**
	 * Unit sine and cosine with their forward differences, four doubles per entry so
	 * one radial sample is a single cache line: sin, next sin - sin, cos, next cos - cos.
	 */
	private static final double[] TRIG = createTrig();
	private static final int[] PALETTE = createPalette();

	private static final Scanlines SCANLINES = new Scanlines(new Color(0, 0, 0, 38), 3);

	private final CachedLayer finishLayer = new CachedLayer(false, 1);

	private int frameWidth;
	private int frameHeight;
	private int divisor;
	private int[] pixels;
	/** Per framebuffer column, 7 doubles: planar sin/cos of x, of warped field 2, of field 3, and the warp. */
	private double[] columns;
	/** The keyframe being faded out and the one being faded in. */
	private BufferedImage older;
	private BufferedImage newer;
	/** Which keyframe {@link #newer} holds, or {@code Long.MIN_VALUE} for none. */
	private long newerKey = Long.MIN_VALUE;

	// Software fallback only, allocated on first use.
	private BufferedImage softwareOutput;
	private int[] softwarePixels;
	/** The two keyframes crossfaded at framebuffer size. */
	private int[] blended;
	private int[] wideRows;
	/** Per output column: the source column to its left and the 8-bit blend towards the next. */
	private int[] sourceColumn;
	private int[] nextSourceColumn;
	private int[] columnBlend;
	/** Per output row: offsets of the two widened rows it blends, and the 8-bit blend. */
	private int[] sourceRow;
	private int[] nextSourceRow;
	private int[] rowBlend;

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
	public int frameDelayMillis() {
		return FRAME_DELAY_MILLIS;
	}

	@Override
	public void reset() {
		pixels = null;
		columns = null;
		older = null;
		newer = null;
		newerKey = Long.MIN_VALUE;
		softwareOutput = null;
		softwarePixels = null;
		blended = null;
		wideRows = null;
		finishLayer.discard();
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		ensureFrame(width, height);

		// Fade from keyframe n to n + 1 across slot n. The picture runs one keyframe
		// ahead of the clock, which nobody can see in something this slow.
		var key = Math.floorDiv(elapsedMillis, KEYFRAME_MILLIS);
		if (key != newerKey) {
			if (key == newerKey + 1) {
				var swap = older;
				older = newer;
				newer = swap;
			} else {
				renderInto(older, key);
			}
			renderInto(newer, key + 1);
			newerKey = key + 1;
		}
		var fade = (float) (elapsedMillis - key * KEYFRAME_MILLIS) / KEYFRAME_MILLIS;

		var g = (Graphics2D) graphics.create();
		try {
			if (!graphics.getDeviceConfiguration().getImageCapabilities().isAccelerated()) {
				// A software surface - remote desktop, a disabled pipeline, or an image.
				// There Java2D's bilinear scale and alpha blend cost 45ms a frame at
				// 2560x1400, so blend and scale here instead and hand over a plain blit.
				paintInSoftware(g, width, height, fade);
				return;
			}
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			// Scaled to a whole multiple so every source pixel covers the same area; the
			// overhang past the desktop is clipped.
			var scaledWidth = frameWidth * divisor;
			var scaledHeight = frameHeight * divisor;
			g.drawImage(older, 0, 0, scaledWidth, scaledHeight, null);
			if (fade > 0) {
				g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, fade)));
				g.drawImage(newer, 0, 0, scaledWidth, scaledHeight, null);
				g.setComposite(AlphaComposite.SrcOver);
			}
			finishLayer.paint(g, width, height, PlasmaEffect::paintFinish);
		} finally {
			g.dispose();
		}
	}

	/**
	 * The fallback path: crossfade the keyframes at framebuffer size, then a separable
	 * fixed-point bilinear upscale - widen each framebuffer row once, then give each
	 * desktop row one blend of two widened rows - into a full-size image for a 1:1 blit.
	 */
	private void paintInSoftware(Graphics2D g, int width, int height, float fade) {
		ensureSoftwareBuffers(width, height);
		var w = frameWidth;
		var h = frameHeight;
		// Copies, not the backing arrays, so the keyframes stay managed for the GPU path.
		var source = (int[]) older.getRaster().getDataElements(0, 0, w, h, blended);
		var next = (int[]) newer.getRaster().getDataElements(0, 0, w, h, pixels);
		blended = source;
		var blend = Math.round(Math.min(1f, fade) * 256);
		for (var i = 0; i < source.length; i++) {
			source[i] = mix(source[i], next[i], blend);
		}
		var wide = wideRows;
		var target = softwarePixels;
		IntStream.range(0, h).parallel().forEach(row -> {
			var start = row * w;
			var out = row * width;
			for (var x = 0; x < width; x++) {
				wide[out + x] = mix(source[start + sourceColumn[x]], source[start + nextSourceColumn[x]], columnBlend[x]);
			}
		});
		IntStream.range(0, height).parallel().forEach(y -> {
			var top = sourceRow[y];
			var bottom = nextSourceRow[y];
			var amount = rowBlend[y];
			var offset = y * width;
			for (var x = 0; x < width; x++) {
				target[offset + x] = mix(wide[top + x], wide[bottom + x], amount);
			}
		});
		g.drawImage(softwareOutput, 0, 0, null);
		finishLayer.paint(g, width, height, PlasmaEffect::paintFinish);
	}

	private void ensureSoftwareBuffers(int width, int height) {
		if (softwareOutput != null && softwareOutput.getWidth() == width && softwareOutput.getHeight() == height
				&& wideRows.length == frameHeight * width) return;
		var w = frameWidth;
		var h = frameHeight;
		softwareOutput = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		// Changes every frame anyway, so there is no acceleration to lose by taking the array.
		softwarePixels = ((DataBufferInt) softwareOutput.getRaster().getDataBuffer()).getData();
		wideRows = new int[h * width];
		sourceColumn = new int[width];
		nextSourceColumn = new int[width];
		columnBlend = new int[width];
		for (var x = 0; x < width; x++) {
			var position = sourcePosition(x, width, w);
			sourceColumn[x] = (int) position;
			nextSourceColumn[x] = Math.min((int) position + 1, w - 1);
			columnBlend[x] = (int) ((position - (int) position) * 256);
		}
		sourceRow = new int[height];
		nextSourceRow = new int[height];
		rowBlend = new int[height];
		for (var y = 0; y < height; y++) {
			var position = sourcePosition(y, height, h);
			sourceRow[y] = (int) position * width;
			nextSourceRow[y] = Math.min((int) position + 1, h - 1) * width;
			rowBlend[y] = (int) ((position - (int) position) * 256);
		}
	}

	/**
	 * Where an output pixel's centre falls in the framebuffer, clamped to its edges.
	 * Measured against the framebuffer scaled by the whole divisor, as the GPU path
	 * draws it, so both paths put the plasma in the same place.
	 */
	private double sourcePosition(int index, int outputSize, int sourceSize) {
		var position = (index + 0.5) / divisor - 0.5;
		return Math.clamp(position, 0, sourceSize - 1);
	}

	/** Blend two packed RGB colours, {@code amount} out of 256 towards the second. */
	private static int mix(int a, int b, int amount) {
		var keep = 256 - amount;
		var redBlue = (((a & 0xFF00FF) * keep + (b & 0xFF00FF) * amount) >>> 8) & 0xFF00FF;
		var green = (((a & 0x00FF00) * keep + (b & 0x00FF00) * amount) >>> 8) & 0x00FF00;
		return redBlue | green;
	}

	private void ensureFrame(int width, int height) {
		var d = Math.max(DIVISOR, (width + MAX_FRAME_WIDTH - 1) / MAX_FRAME_WIDTH);
		var w = Math.max(1, (width + d - 1) / d);
		var h = Math.max(1, (height + d - 1) / d);
		if (older != null && w == frameWidth && h == frameHeight && d == divisor) return;
		divisor = d;
		frameWidth = w;
		frameHeight = h;
		pixels = new int[w * h];
		blended = null;
		softwareOutput = null;
		columns = new double[w * 7];
		older = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		newer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		newerKey = Long.MIN_VALUE;
	}

	private void renderInto(BufferedImage image, long key) {
		// The plasma runs on a clock six times slower than real time: a slow drift
		// rather than a demo-party strobe, since it sits behind people's work.
		render(key * KEYFRAME_MILLIS / 6_000.0);
		// Through the raster, not DataBufferInt.getData(): taking the array would make
		// the image unmanaged and re-uploaded on every draw instead of once.
		image.getRaster().setDataElements(0, 0, frameWidth, frameHeight, pixels);
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
		var perPixel = Math.PI * 2 / span;

		// Field frequencies, in radians per framebuffer pixel.
		var f1 = perPixel * 2.3;
		var f2 = perPixel * 1.7;
		var f3 = perPixel * 1.3;
		// The radial fields go through the table, so theirs are in table steps.
		var f4 = perPixel * 3.1 * TABLE_PER_RADIAN;
		var f5 = perPixel * 2.2 * TABLE_PER_RADIAN;

		// Phases drift at mutually irrational rates so the pattern never visibly repeats.
		var p1 = time * 0.37;
		var p2 = -time * 0.29;
		var p3 = time * 0.21;
		// Wrapped into one table period, so a radial phase is never negative and a plain
		// int cast floors it - after a day of uptime these reach millions of steps.
		var p4 = wrap(-time * 0.53 * TABLE_PER_RADIAN);
		var p5 = wrap(time * 0.41 * TABLE_PER_RADIAN);

		// Slope weights. The per-pixel frequency times span is size-independent, which
		// is what keeps the bump depth constant across framebuffer sizes.
		var s1 = SLOPE_SCALE * f1 * span;
		var s2 = SLOPE_SCALE * f2 * span;
		// The diagonal field changes along both axes of the light direction.
		var s3 = SLOPE_SCALE * f3 * span * 2;
		var s4 = SLOPE_SCALE * perPixel * 3.1 * span;
		var s5 = SLOPE_SCALE * perPixel * 2.2 * span;

		// Two radial sources wandering on Lissajous paths.
		var c1x = w * (0.5 + 0.34 * Math.sin(time * 0.13));
		var c1y = h * (0.5 + 0.30 * Math.cos(time * 0.17));
		var c2x = w * (0.5 + 0.38 * Math.cos(time * 0.11 + 1.3));
		var c2y = h * (0.5 + 0.34 * Math.sin(time * 0.19 + 0.7));

		// Domain warp: every row slides sideways and every column slides vertically,
		// on their own slow sines. Cheap, and it turns stripes into liquid. Kept
		// fractional: whole-pixel warps make neighbouring columns jump in height, which
		// the bump lighting turns into vertical streaks.
		var warpAmplitude = span * 0.09;
		var warpFrequency = perPixel * 1.1;
		var rowPhase = time * 0.23;
		var columnPhase = -time * 0.19;

		// Column halves of the planar fields. With warped coordinates wx = x + rowWarp
		// and wy = y + columnWarp:
		//   field 1 = sin(x f1 + [rowWarp f1 + p1])
		//   field 2 = sin(y f2 + [columnWarp f2 + p2])
		//   field 3 = sin([(x + columnWarp) f3 + p3] + [(y + rowWarp) f3])
		// Brackets change once per row or column; each is split with the addition formula.
		var cols = columns;
		for (var x = 0; x < w; x++) {
			var warp = Math.sin(x * warpFrequency + columnPhase) * warpAmplitude;
			var i = x * 7;
			var a = x * f1;
			cols[i] = Math.sin(a);
			cols[i + 1] = Math.cos(a);
			a = warp * f2 + p2;
			cols[i + 2] = Math.sin(a);
			cols[i + 3] = Math.cos(a);
			a = (x + warp) * f3 + p3;
			cols[i + 4] = Math.sin(a);
			cols[i + 5] = Math.cos(a);
			cols[i + 6] = warp;
		}

		var paletteShift = (int) (time * 26);
		var soft = RADIAL_SOFTNESS * RADIAL_SOFTNESS;
		var target = pixels;
		// Rows are independent - each lights itself from its own field function, not
		// from the row above - so they render in parallel.
		IntStream.range(0, h).parallel().forEach(y -> {
			var rowWarp = Math.sin(y * warpFrequency + rowPhase) * warpAmplitude;
			var a = rowWarp * f1 + p1;
			var sinRow1 = Math.sin(a);
			var cosRow1 = Math.cos(a);
			a = y * f2;
			var sinRow2 = Math.sin(a);
			var cosRow2 = Math.cos(a);
			a = (y + rowWarp) * f3;
			var sinRow3 = Math.sin(a);
			var cosRow3 = Math.cos(a);
			var dy1 = y - c1y;
			var dy2 = y - c2y;
			var offset = y * w;
			for (var x = 0; x < w; x++) {
				var i = x * 7;
				var sinX = cols[i];
				var cosX = cols[i + 1];
				var sinC2 = cols[i + 2];
				var cosC2 = cols[i + 3];
				var sinC3 = cols[i + 4];
				var cosC3 = cols[i + 5];
				var warp = cols[i + 6];

				var dx1 = x + rowWarp - c1x;
				var dx2 = x + rowWarp - c2x;
				var ey1 = dy1 + warp;
				var ey2 = dy2 + warp;
				var r1 = Math.sqrt(dx1 * dx1 + ey1 * ey1 + soft);
				var r2 = Math.sqrt(dx2 * dx2 + ey2 * ey2 + soft);

				var t = r1 * f4 + p4;
				var n = (int) t;
				var fraction = t - n;
				var k = (n & TABLE_MASK) << 2;
				var sin4 = TRIG[k] + TRIG[k + 1] * fraction;
				var cos4 = TRIG[k + 2] + TRIG[k + 3] * fraction;
				t = r2 * f5 + p5;
				n = (int) t;
				fraction = t - n;
				k = (n & TABLE_MASK) << 2;
				var sin5 = TRIG[k] + TRIG[k + 1] * fraction;
				var cos5 = TRIG[k + 2] + TRIG[k + 3] * fraction;

				var height = (sinX * cosRow1 + cosX * sinRow1
						+ sinRow2 * cosC2 + cosRow2 * sinC2
						+ sinC3 * cosRow3 + cosC3 * sinRow3
						+ sin4 + sin5) * AMPLITUDE;

				// Bump lighting from the height field's analytic slope towards a light up
				// and to the left: each term's derivative along (-1, -1), from the cosines
				// the addition formula and the table hand over alongside the sines.
				var slope = (cosX * cosRow1 - sinX * sinRow1) * s1
						+ (cosRow2 * cosC2 - sinRow2 * sinC2) * s2
						+ (cosC3 * cosRow3 - sinC3 * sinRow3) * s3
						+ cos4 * s4 * ((dx1 + ey1) / r1)
						+ cos5 * s5 * ((dx2 + ey2) / r2);
				var diffuse = (int) (256 + slope * 3);
				if (diffuse < 120) diffuse = 120;
				else if (diffuse > 430) diffuse = 430;

				var color = PALETTE[(((int) height >> 3) + paletteShift) & PALETTE_MASK];
				var r = ((color >> 16) & 0xFF) * diffuse >> 8;
				var g = ((color >> 8) & 0xFF) * diffuse >> 8;
				var b = (color & 0xFF) * diffuse >> 8;
				target[offset + x] = (r > 255 ? 255 : r) << 16 | (g > 255 ? 255 : g) << 8 | (b > 255 ? 255 : b);
			}
		});
	}

	private static double wrap(double phase) {
		var wrapped = phase % TABLE_SIZE;
		return wrapped < 0 ? wrapped + TABLE_SIZE : wrapped;
	}

	private static void paintFinish(Graphics2D g, int width, int height) {
		var radius = Math.max(width, height) * 0.78f;
		g.setPaint(new RadialGradientPaint(width / 2f, height / 2f, radius,
				new float[] { 0.35f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 200) }));
		g.fillRect(0, 0, width, height);
		SCANLINES.paint(g, width, height);
	}

	private static double[] createTrig() {
		var table = new double[TABLE_SIZE * 4];
		for (var i = 0; i < TABLE_SIZE; i++) {
			var angle = i / TABLE_PER_RADIAN;
			var next = (i + 1) / TABLE_PER_RADIAN;
			table[i * 4] = Math.sin(angle);
			table[i * 4 + 1] = Math.sin(next) - Math.sin(angle);
			table[i * 4 + 2] = Math.cos(angle);
			table[i * 4 + 3] = Math.cos(next) - Math.cos(angle);
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
