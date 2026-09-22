package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

/**
 * A demoscene-style genome sequencer: a perspective-projected double helix that
 * spins and streams its base pairs along the axis, a parallax ghost helix behind
 * it, copper bars, a scrolling synthwave floor, a read head that lights up the
 * pairs it passes and a rainbow sine scroller along the bottom.
 */
final class HelixSequencerEffect implements DesktopBackgroundEffect {

	private static final double TAU = Math.PI * 2;
	/** Radians of twist per base pair: about eleven pairs a turn, close to real B-DNA. */
	private static final double TWIST = 0.58;
	/** Backbone samples per base pair, so the strands curve instead of zig-zagging. */
	private static final int MAIN_SAMPLES = 4;
	/** The distant, translucent strand does not need the foreground strand's curve density. */
	private static final int GHOST_SAMPLES = 2;
	/** Fine enough that a slowly fading glow never visibly steps. */
	private static final int ALPHA_LEVELS = 128;

	private static final int BASE_A = 0;
	private static final int STRAND_ONE = 4;
	private static final int STRAND_TWO = 5;
	private static final int WHITE = 6;
	private static final int GRID = 7;
	private static final int BEAM = 8;

	/** A, T, G, C, strand one, strand two, white, grid, read beam, shadow. */
	private static final Color[] PALETTE = {
			new Color(40, 255, 180), new Color(255, 64, 128), new Color(255, 214, 64), new Color(128, 120, 255),
			new Color(64, 220, 255), new Color(236, 90, 255), new Color(240, 250, 255), new Color(255, 60, 200),
			new Color(120, 240, 255), new Color(0, 0, 0)
	};

	/**
	 * Every colour the effect draws, pre-built at a fixed ladder of opacities. A
	 * helix is several hundred strokes a frame, each of which would otherwise have
	 * allocated its own {@link Color}.
	 */
	private static final Color[][] TINTS = createTints();
	private static final Color[] HUES = createHues();
	private static final Color[] HUE_SOLIDS = createHueAlphas(44);
	private static final Color[] HUE_CLEARS = createHueAlphas(0);
	/** Round-capped strokes in quarter-pixel steps, for the same reason as the tints. */
	private static final BasicStroke[] STROKES = createStrokes();
	private static final BasicStroke HAIRLINE = new BasicStroke(1f);

	private static final byte[] SEQUENCE = createSequence();

	private static final Scanlines SCANLINES = new Scanlines(new Color(0, 0, 0, 46), 3);

	private final CachedLayer backdropLayer = new CachedLayer(true, 1);
	private final CachedLayer finishLayer = new CachedLayer(false, 1);
	private final Helix ghost = new Helix(GHOST_SAMPLES,
			0.30, 0.34, 0.075, 0.30, -1.15, 0.55, 0.42, 0.035, 0.42, 2.1);
	private final Helix main = new Helix(MAIN_SAMPLES,
			0.50, 0.53, 0.155, -0.16, 1.35, 1.65, 1.0, 0.055, 1.0, 0.0);
	private final Line2D.Double line = new Line2D.Double();
	private final Ellipse2D.Double oval = new Ellipse2D.Double();
	private final Rectangle2D.Double rect = new Rectangle2D.Double();


	@Override
	public String id() {
		return "helix-sequencer";
	}

	@Override
	public String displayName() {
		return "Helix Sequencer";
	}

	@Override
	public String description() {
		return "A spinning demoscene DNA double helix with copper bars and a read head.";
	}

	@Override
	public boolean renderOffEdt() {
		return true;
	}

	@Override
	public void reset() {
		backdropLayer.discard();
		finishLayer.discard();
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		var g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// Pure stroke control keeps subpixel positions, so slow motion glides instead
			// of snapping from pixel to pixel.
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			// The whole effect runs on a clock 27 times slower than real time.
			var time = elapsedMillis / 27_000.0;
			backdropLayer.paint(g, width, height, this::paintBackdrop);
			drawCopperBars(g, width, height, time);
			drawFloor(g, width, height, time);

			var headX = width * (0.5 + Math.sin(time * 0.31) * 0.36);
			ghost.layout(width, height, time, Double.NaN);
			drawHelix(g, ghost);
			main.layout(width, height, time, headX);
			drawReadBeam(g, height, headX, time);
			drawHelix(g, main);

			finishLayer.paint(g, width, height, this::paintFinish);
		} finally {
			g.dispose();
		}
	}

	// ---------------------------------------------------------------- layers

	private void paintBackdrop(Graphics2D g, int width, int height) {
		g.setPaint(new GradientPaint(0, 0, new Color(3, 2, 16), 0, height, new Color(14, 3, 30)));
		g.fillRect(0, 0, width, height);
		var horizon = horizon(height);
		var radius = Math.max(width, height) * 0.62f;
		g.setPaint(new RadialGradientPaint(width / 2f, (float) horizon, radius,
				new float[] { 0f, 0.25f, 1f },
				new Color[] { new Color(255, 60, 190, 92), new Color(120, 30, 160, 40), new Color(0, 0, 0, 0) }));
		g.fillRect(0, 0, width, height);
		g.setPaint(new RadialGradientPaint(width * 0.5f, height * 0.45f, radius * 0.8f,
				new float[] { 0f, 1f }, new Color[] { new Color(0, 150, 255, 38), new Color(0, 0, 0, 0) }));
		g.fillRect(0, 0, width, height);
		// A fine dust of fixed stars: baked, so it costs nothing per frame.
		var random = new Random(0xD0A_1953L);
		for (var star = 0; star < width * height / 2600; star++) {
			var bright = random.nextDouble();
			g.setColor(tint(bright > 0.93 ? STRAND_ONE : WHITE, 0.08 + bright * bright * 0.5));
			var size = bright > 0.97 ? 2 : 1;
			g.fillRect(random.nextInt(width), (int) (random.nextDouble() * horizon), size, size);
		}
	}

	private void paintFinish(Graphics2D g, int width, int height) {
		var radius = Math.max(width, height) * 0.75f;
		g.setPaint(new RadialGradientPaint(width / 2f, height / 2f, radius,
				new float[] { 0.42f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 190) }));
		g.fillRect(0, 0, width, height);
		SCANLINES.paint(g, width, height);
	}

	private static double horizon(int height) {
		return height * 0.70;
	}

	// ---------------------------------------------------------------- oldschool dressing

	/** Amiga copper bars, sliding on their own sines behind everything. */
	private void drawCopperBars(Graphics2D g, int width, int height, double time) {
		var barHeight = (float) Math.max(10, height * 0.028);
		for (var bar = 0; bar < 4; bar++) {
			var y = (float) (height * 0.42 + Math.sin(time * 0.9 + bar * 0.62) * height * 0.26);
			var hue = Math.floorMod((int) (bar * 40 + time * 18), HUES.length);
			var solid = HUE_SOLIDS[hue];
			var clear = HUE_CLEARS[hue];
			g.setPaint(new GradientPaint(0, y - barHeight, clear, 0, y, solid));
			rect.setRect(0, y - barHeight, width, barHeight);
			g.fill(rect);
			g.setPaint(new GradientPaint(0, y, solid, 0, y + barHeight, clear));
			rect.setRect(0, y, width, barHeight);
			g.fill(rect);
		}
	}

	/** The obligatory synthwave floor, rushing towards the viewer. */
	private void drawFloor(Graphics2D g, int width, int height, double time) {
		var horizon = horizon(height);
		var depth = height - horizon;
		var cx = width / 2.0;
		var travel = (time * 1.4) % 1.0;
		g.setStroke(HAIRLINE);
		for (var row = 0; row < 22; row++) {
			var z = row + 1 - travel;
			var y = horizon + depth * 0.9 / (z * 0.55 + 0.9 - 0.55 * 0.35);
			if (y > height || y < horizon) continue;
			var fade = Math.clamp((y - horizon) / depth, 0, 1);
			g.setColor(tint(GRID, 0.05 + fade * 0.30));
			line.setLine(0, y, width, y);
			g.draw(line);
		}
		for (var column = -18; column <= 18; column++) {
			var bottomX = cx + column * width * 0.11;
			var topX = cx + column * width * 0.011;
			g.setColor(tint(GRID, 0.16 - Math.abs(column) * 0.006));
			line.setLine(topX, horizon, bottomX, height);
			g.draw(line);
		}
		g.setColor(tint(GRID, 0.55));
		line.setLine(0, horizon, width, horizon);
		g.draw(line);
	}

	private void drawReadBeam(Graphics2D g, int height, double headX, double time) {
		var x = (float) headX;
		var halfWidth = 46f;
		var pulse = 0.75 + Math.sin(time * 7.0) * 0.25;
		var core = tint(BEAM, 0.16 * pulse);
		var clear = tint(BEAM, 0);
		g.setPaint(new GradientPaint(x - halfWidth, 0, clear, x, 0, core));
		rect.setRect(x - halfWidth, 0, halfWidth, height);
		g.fill(rect);
		g.setPaint(new GradientPaint(x, 0, core, x + halfWidth, 0, clear));
		rect.setRect(x, 0, halfWidth, height);
		g.fill(rect);
		g.setStroke(HAIRLINE);
		g.setColor(tint(WHITE, 0.30 * pulse));
		line.setLine(x, 0, x, height);
		g.draw(line);
	}

	// ---------------------------------------------------------------- helix

	/**
	 * Draws a laid-out helix in two depth passes. Everything behind the axis goes
	 * first, farthest parts first; then everything in front, so the strands really
	 * wrap around the rungs rather than being stacked in a fixed order.
	 */
	private void drawHelix(Graphics2D g, Helix h) {
		drawBackbone(g, h, false);
		drawBeads(g, h, false);
		drawRungs(g, h, false);
		drawRungs(g, h, true);
		drawBackbone(g, h, true);
		drawBeads(g, h, true);
	}

	private void drawBackbone(Graphics2D g, Helix h, boolean front) {
		for (var strand = 0; strand < 2; strand++) {
			var xs = strand == 0 ? h.x1 : h.x2;
			var ys = strand == 0 ? h.y1 : h.y2;
			var zs = strand == 0 ? h.z1 : h.z2;
			var ps = strand == 0 ? h.p1 : h.p2;
			var color = strand == 0 ? STRAND_ONE : STRAND_TWO;
			for (var k = 0; k + 1 < h.count; k++) {
				var z = (zs[k] + zs[k + 1]) * 0.5;
				if (z >= 0 != front) continue;
				var light = h.opacity * (0.22 + 0.78 * (z + 1) * 0.5);
				var scale = (ps[k] + ps[k + 1]) * 0.5;
				var flare = (h.flare[k] + h.flare[k + 1]) * 0.5;
				line.setLine(xs[k], ys[k], xs[k + 1], ys[k + 1]);
				g.setStroke(stroke(h.radius * 0.17 * scale));
				g.setColor(tint(color, light * (0.10 + flare * 0.12)));
				g.draw(line);
				g.setStroke(stroke(h.radius * 0.06 * scale));
				g.setColor(tint(color, light * 0.45));
				g.draw(line);
				g.setStroke(stroke(Math.max(0.8, h.radius * 0.016 * scale)));
				g.setColor(tint(front ? WHITE : color, light * (0.55 + flare * 0.45)));
				g.draw(line);
			}
		}
	}

	private void drawRungs(Graphics2D g, Helix h, boolean front) {
		for (var k = 0; k < h.count; k += h.samples) {
			var base = h.base[k];
			var z = h.z1[k];
			// Half of each rung belongs to either strand, so it lives on that strand's side.
			if (z >= 0 == front) {
				drawRungHalf(g, h, k, h.x1[k], h.y1[k], h.x2[k], h.y2[k], base, z, h.p1[k]);
			} else {
				drawRungHalf(g, h, k, h.x2[k], h.y2[k], h.x1[k], h.y1[k], base ^ 1, -z, h.p2[k]);
			}
		}
	}

	private void drawRungHalf(Graphics2D g, Helix h, int k, double fromX, double fromY,
			double otherX, double otherY, int base, double z, double scale) {
		// Stop just short of the middle: the gap is the hydrogen bond.
		var toX = fromX + (otherX - fromX) * 0.47;
		var toY = fromY + (otherY - fromY) * 0.47;
		var light = h.opacity * (0.28 + 0.72 * (z * 0.5 + 0.5));
		var flare = h.flare[k];
		line.setLine(fromX, fromY, toX, toY);
		g.setStroke(stroke(h.radius * 0.13 * scale));
		g.setColor(tint(base, light * (0.08 + flare * 0.22)));
		g.draw(line);
		g.setStroke(stroke(h.radius * 0.036 * scale));
		g.setColor(tint(base, light * (0.62 + flare * 0.38)));
		g.draw(line);
		if (flare > 0.05) {
			g.setStroke(stroke(Math.max(0.7, h.radius * 0.012 * scale)));
			g.setColor(tint(WHITE, light * flare));
			g.draw(line);
		}
	}

	private void drawBeads(Graphics2D g, Helix h, boolean front) {
		for (var k = 0; k < h.count; k += h.samples) {
			for (var strand = 0; strand < 2; strand++) {
				var z = strand == 0 ? h.z1[k] : h.z2[k];
				if (z >= 0 != front) continue;
				var x = strand == 0 ? h.x1[k] : h.x2[k];
				var y = strand == 0 ? h.y1[k] : h.y2[k];
				var scale = strand == 0 ? h.p1[k] : h.p2[k];
				var base = strand == 0 ? h.base[k] : h.base[k] ^ 1;
				var nearness = z * 0.5 + 0.5;
				var light = h.opacity * (0.25 + 0.75 * nearness);
				var flare = h.flare[k];
				var radius = h.radius * 0.062 * scale * (1 + flare * 0.5);

				fillCircle(g, x, y, radius * (3.0 + flare), tint(base, light * (0.10 + flare * 0.10)));
				fillCircle(g, x, y, radius * 1.6, tint(strand == 0 ? STRAND_ONE : STRAND_TWO, light * 0.35));
				fillCircle(g, x, y, radius, tint(base, light));
				fillCircle(g, x - radius * 0.25, y - radius * 0.25, radius * 0.45, tint(WHITE, light * nearness));
			}
		}
	}

	private void fillCircle(Graphics2D g, double x, double y, double radius, Color color) {
		if (color.getAlpha() == 0) return;
		g.setColor(color);
		oval.setFrame(x - radius, y - radius, radius * 2, radius * 2);
		g.fill(oval);
	}

	// ---------------------------------------------------------------- tables

	private static int baseAt(long pair) {
		return SEQUENCE[(int) Math.floorMod(pair, (long) SEQUENCE.length)];
	}

	private static Color tint(int palette, double opacity) {
		var level = (int) Math.round(Math.clamp(opacity, 0, 1) * (ALPHA_LEVELS - 1));
		return TINTS[palette][level];
	}

	private static BasicStroke stroke(double width) {
		return STROKES[Math.clamp((int) Math.round(width * 4), 1, STROKES.length - 1)];
	}

	private static Color withAlpha(Color color, int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}

	private static Color[][] createTints() {
		var tints = new Color[PALETTE.length][ALPHA_LEVELS];
		for (var palette = 0; palette < PALETTE.length; palette++) {
			for (var level = 0; level < ALPHA_LEVELS; level++) {
				tints[palette][level] = withAlpha(PALETTE[palette], level * 255 / (ALPHA_LEVELS - 1));
			}
		}
		return tints;
	}

	private static Color[] createHues() {
		var hues = new Color[256];
		for (var step = 0; step < hues.length; step++) {
			hues[step] = Color.getHSBColor(step / (float) hues.length, 0.78f, 1f);
		}
		return hues;
	}

	private static Color[] createHueAlphas(int alpha) {
		var colors = new Color[HUES.length];
		for (var index = 0; index < colors.length; index++) {
			colors[index] = withAlpha(HUES[index], alpha);
		}
		return colors;
	}

	private static BasicStroke[] createStrokes() {
		var strokes = new BasicStroke[161];
		for (var index = 0; index < strokes.length; index++) {
			strokes[index] = new BasicStroke(Math.max(0.25f, index / 4f), BasicStroke.CAP_ROUND,
					BasicStroke.JOIN_ROUND);
		}
		return strokes;
	}

	/** A random genome with a few repeats spliced in, because real ones stutter too. */
	private static byte[] createSequence() {
		var random = new Random(0x6E0_3E5EL);
		var sequence = new byte[4096];
		for (var i = 0; i < sequence.length; i++) {
			if (i > 12 && random.nextDouble() < 0.06) {
				var repeat = 3 + random.nextInt(6);
				for (var r = 0; r < repeat && i < sequence.length; r++, i++) {
					sequence[i] = sequence[i - 3];
				}
				i--;
			} else {
				sequence[i] = (byte) (random.nextInt(4) + BASE_A);
			}
		}
		return sequence;
	}

	// ---------------------------------------------------------------- geometry

	/**
	 * One helix's shape parameters and the per-frame projection of its samples,
	 * held in arrays that are reused from frame to frame.
	 */
	private static final class Helix {

		private final int samples;
		private final double centerX;
		private final double centerY;
		private final double radiusFraction;
		private final double axisAngle;
		private final double spin;
		private final double flow;
		private final double opacity;
		private final double bend;
		private final double pairSpacing;
		private final double phase;

		private double radius;
		private double spacing;
		private double originX;
		private double originY;
		private double axisX;
		private double axisY;
		private double normalX;
		private double normalY;
		private double scroll;
		private int count;
		private double[] x1 = new double[0];
		private double[] y1 = new double[0];
		private double[] z1 = new double[0];
		private double[] p1 = new double[0];
		private double[] x2 = new double[0];
		private double[] y2 = new double[0];
		private double[] z2 = new double[0];
		private double[] p2 = new double[0];
		private double[] flare = new double[0];
		private int[] base = new int[0];

		private Helix(int samples, double centerX, double centerY, double radiusFraction, double axisAngle, double spin,
				double flow, double opacity, double bend, double pairSpacing, double phase) {
			this.samples = samples;
			this.centerX = centerX;
			this.centerY = centerY;
			this.radiusFraction = radiusFraction;
			this.axisAngle = axisAngle;
			this.spin = spin;
			this.flow = flow;
			this.opacity = opacity;
			this.bend = bend;
			this.pairSpacing = pairSpacing;
			this.phase = phase;
		}

		/** Project every sample for this frame; {@code headX} is NaN when no read head applies. */
		private void layout(int width, int height, double time, double headX) {
			var unit = Math.min(width, height);
			radius = unit * radiusFraction;
			spacing = radius * 0.46 * pairSpacing;
			var focal = radius * 3.4;
			var angle = axisAngle + Math.sin(time * 0.21 + phase) * 0.05;
			axisX = Math.cos(angle);
			axisY = Math.sin(angle);
			normalX = -axisY;
			normalY = axisX;
			originX = width * centerX;
			originY = height * centerY;
			scroll = time * flow;
			var fraction = scroll - Math.floor(scroll);
			var whole = (long) Math.floor(scroll);

			var reach = (Math.hypot(width, height) * 0.5) / (spacing * 0.75) + 3;
			var first = -(int) Math.ceil(reach);
			var pairs = (int) Math.ceil(reach) * 2 + 1;
			count = (pairs - 1) * samples + 1;
			ensureCapacity(count);

			var spinAngle = time * spin;
			var firstSample = first - fraction;
			var sampleDistance = spacing / samples;

			// All three phases advance by a constant amount from one sample to the next.
			// Rotate their sine/cosine pairs instead of evaluating six transcendental
			// functions for every point on both helices.
			var swayOneAngle = firstSample * spacing / width * TAU * 0.65 + time * 0.47 + phase;
			var swayOneSin = Math.sin(swayOneAngle);
			var swayOneCos = Math.cos(swayOneAngle);
			var swayOneStep = sampleDistance / width * TAU * 0.65;
			var swayOneStepSin = Math.sin(swayOneStep);
			var swayOneStepCos = Math.cos(swayOneStep);

			var swayTwoAngle = firstSample * spacing / width * TAU * 1.7 - time * 0.33;
			var swayTwoSin = Math.sin(swayTwoAngle);
			var swayTwoCos = Math.cos(swayTwoAngle);
			var swayTwoStep = sampleDistance / width * TAU * 1.7;
			var swayTwoStepSin = Math.sin(swayTwoStep);
			var swayTwoStepCos = Math.cos(swayTwoStep);

			var theta = (firstSample + scroll) * TWIST + spinAngle;
			var thetaSin = Math.sin(theta);
			var thetaCos = Math.cos(theta);
			var thetaStep = TWIST / samples;
			var thetaStepSin = Math.sin(thetaStep);
			var thetaStepCos = Math.cos(thetaStep);

			for (var k = 0; k < count; k++) {
				var s = firstSample + k / (double) samples;
				var u = s * spacing;
				var sway = swayOneSin * height * bend + swayTwoSin * height * bend * 0.3;
				var lateral = thetaSin;
				var depth = thetaCos;

				var p = focal / (focal - depth * radius);
				x1[k] = originX + (axisX * u + normalX * (sway + lateral * radius)) * p;
				y1[k] = originY + (axisY * u + normalY * (sway + lateral * radius)) * p;
				z1[k] = depth;
				p1[k] = p;

				var q = focal / (focal + depth * radius);
				x2[k] = originX + (axisX * u + normalX * (sway - lateral * radius)) * q;
				y2[k] = originY + (axisY * u + normalY * (sway - lateral * radius)) * q;
				z2[k] = -depth;
				p2[k] = q;

				base[k] = k % samples == 0 ? baseAt(first + k / samples + whole) : 0;
				if (Double.isNaN(headX)) {
					flare[k] = 0;
				} else {
					var distance = ((x1[k] + x2[k]) * 0.5 - headX) / (spacing * 1.4);
					flare[k] = Math.exp(-distance * distance);
				}

				var nextSin = swayOneSin * swayOneStepCos + swayOneCos * swayOneStepSin;
				swayOneCos = swayOneCos * swayOneStepCos - swayOneSin * swayOneStepSin;
				swayOneSin = nextSin;
				nextSin = swayTwoSin * swayTwoStepCos + swayTwoCos * swayTwoStepSin;
				swayTwoCos = swayTwoCos * swayTwoStepCos - swayTwoSin * swayTwoStepSin;
				swayTwoSin = nextSin;
				nextSin = thetaSin * thetaStepCos + thetaCos * thetaStepSin;
				thetaCos = thetaCos * thetaStepCos - thetaSin * thetaStepSin;
				thetaSin = nextSin;
			}
		}

		private void ensureCapacity(int size) {
			if (x1.length >= size) return;
			x1 = new double[size];
			y1 = new double[size];
			z1 = new double[size];
			p1 = new double[size];
			x2 = new double[size];
			y2 = new double[size];
			z2 = new double[size];
			p2 = new double[size];
			flare = new double[size];
			base = new int[size];
		}
	}
}
