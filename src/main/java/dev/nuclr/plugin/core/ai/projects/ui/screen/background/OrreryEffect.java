package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

/**
 * A cinematic orrery: a perspective camera hangs over the ecliptic while the planets
 * run their Keplerian orbits, a comet swings through on a true eccentric ellipse
 * and the asteroid belt shears as its inner rocks outrun the outer ones.
 *
 * <p>Everything is depth-sorted around the sun, so bodies really pass behind it,
 * and every planet is shaded from the sun's actual 3D direction - a crescent when
 * it is on the far side, full when it is between the sun and the camera.
 *
 * <p>Glows are pre-rendered sprites blitted through an affine transform with
 * subpixel positions, not gradients rebuilt per frame, and the sky is baked.
 */
final class OrreryEffect implements DesktopBackgroundEffect {

	private static final double TAU = Math.PI * 2;
	/** Camera distance, in orbit-radius units, for the perspective divide. */
	private static final double FOCAL = 2.9;
	private static final int ORBIT_SEGMENTS = 120;
	private static final int ASTEROIDS = 720;
	private static final int TWINKLERS = 12;
	private static final int ALPHA_LEVELS = 128;
	/** Seconds of effect time per real second: a stately clock, not a demo strobe. */
	private static final double TIME_SCALE = 0.25;
	/** Earth's orbit takes this long in effect time; the rest follow Kepler's third law. */
	private static final double EARTH_PERIOD = 60;
	private static final double EARTH_ORBIT = 0.27;
	private static final double KEPLER = TAU / EARTH_PERIOD * Math.pow(EARTH_ORBIT, 1.5);

	private static final int SPRITE_SIZE = 128;
	private static final BufferedImage WHITE_GLOW = glowSprite(new Color(255, 255, 255), 2.4);
	private static final BufferedImage CORONA = glowSprite(new Color(255, 128, 36), 1.6);
	private static final BufferedImage SUN_HALO = glowSprite(new Color(255, 222, 150), 2.8);
	private static final BufferedImage STREAK = glowSprite(new Color(150, 200, 255), 2.0);
	private static final BufferedImage ION_TAIL = glowSprite(new Color(110, 190, 255), 1.8);
	private static final BufferedImage DUST_TAIL = glowSprite(new Color(255, 214, 160), 1.8);

	private static final Planet[] PLANETS = {
			new Planet("mercury", 0.135, 0.0055, 4.1, new Color(168, 158, 150), new Color(120, 110, 104), 0, 0, 0),
			new Planet("venus", 0.195, 0.0100, 1.3, new Color(236, 204, 150), new Color(214, 170, 110), 0, 0, 0),
			new Planet("earth", EARTH_ORBIT, 0.0108, 0.2, new Color(60, 130, 230), new Color(236, 246, 255), 1, 0, 0),
			new Planet("mars", 0.345, 0.0075, 2.6, new Color(210, 96, 52), new Color(150, 60, 36), 0, 0, 0),
			new Planet("jupiter", 0.575, 0.0290, 5.2, new Color(214, 176, 132), new Color(160, 104, 70), 4, 0, 0),
			new Planet("saturn", 0.715, 0.0235, 0.8, new Color(230, 204, 146), new Color(186, 150, 96), 0, 1, 0.47),
			new Planet("uranus", 0.835, 0.0160, 3.3, new Color(150, 222, 226), new Color(116, 196, 206), 0, 2, 1.45),
			new Planet("neptune", 0.945, 0.0155, 2.0, new Color(70, 110, 236), new Color(46, 80, 196), 1, 0, 0),
	};

	private static final Scanlines SCANLINES = new Scanlines(new Color(0, 0, 0, 34), 3);
	private static final BasicStroke HAIRLINE = new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final AlphaComposite[] COMPOSITES = composites();
	private static final double[] ORBIT_COS = orbitCoordinates(true);
	private static final double[] ORBIT_SIN = orbitCoordinates(false);

	private final CachedLayer skyLayer = new CachedLayer(true, 1);
	private final CachedLayer finishLayer = new CachedLayer(false, 1);
	private final AffineTransform sprite = new AffineTransform();
	private final Ellipse2D.Double circle = new Ellipse2D.Double();
	private final Line2D.Double line = new Line2D.Double();
	private final Rectangle clipRect = new Rectangle();

	private final double[] asteroidRadius = new double[ASTEROIDS];
	private final double[] asteroidPhase = new double[ASTEROIDS];
	private final double[] asteroidVelocity = new double[ASTEROIDS];
	private final double[] asteroidLift = new double[ASTEROIDS];
	private final float[] asteroidSize = new float[ASTEROIDS];
	private final int[] asteroidTone = new int[ASTEROIDS];
	private final double[] asteroidX = new double[ASTEROIDS];
	private final double[] asteroidY = new double[ASTEROIDS];
	private final double[] asteroidScale = new double[ASTEROIDS];
	private final double[] asteroidDepth = new double[ASTEROIDS];
	private final double[] twinkleX = new double[TWINKLERS];
	private final double[] twinkleY = new double[TWINKLERS];
	private final double[] twinklePhase = new double[TWINKLERS];
	private final double[] twinkleSize = new double[TWINKLERS];

	private final double[] bodyDepth = new double[PLANETS.length + 1];
	private final int[] bodyOrder = new int[PLANETS.length + 1];
	private final double[][] orbitX = new double[PLANETS.length][ORBIT_SEGMENTS + 1];
	private final double[][] orbitY = new double[PLANETS.length][ORBIT_SEGMENTS + 1];
	private final double[][] orbitDepth = new double[PLANETS.length][ORBIT_SEGMENTS + 1];
	private final double[] planetPosition = new double[PLANETS.length];

	// Scene state for the frame being drawn.
	private double centerX;
	private double centerY;
	private double scale;
	private double unit;
	private double tiltSin;
	private double tiltCos;
	private double time;
	private int width;
	private int height;

	// The last projection's output, reused rather than returned as an object.
	private double projX;
	private double projY;
	private double projScale;
	private double projDepth;

	OrreryEffect() {
		var random = new Random(0x0B17_1543L);
		for (var i = 0; i < ASTEROIDS; i++) {
			// Denser in the middle of the belt, with a few stragglers at the edges.
			var spread = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3 - 0.5;
			asteroidRadius[i] = 0.448 + spread * 0.13;
			asteroidPhase[i] = random.nextDouble() * TAU;
			asteroidVelocity[i] = KEPLER / Math.pow(asteroidRadius[i], 1.5);
			asteroidLift[i] = random.nextGaussian() * 0.006;
			asteroidSize[i] = (float) (1.1 + Math.pow(random.nextDouble(), 4) * 2.2);
			asteroidTone[i] = random.nextInt(3);
		}
	}

	@Override
	public String id() {
		return "orrery";
	}

	@Override
	public String displayName() {
		return "Orrery";
	}

	@Override
	public String description() {
		return "A cinematic 3D solar system with Keplerian orbits, a comet, an asteroid belt and a blazing sun.";
	}

	@Override
	public boolean renderOffEdt() {
		return true;
	}

	@Override
	public void reset() {
		skyLayer.discard();
		finishLayer.discard();
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		var g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			setUpScene(width, height, elapsedMillis);
			skyLayer.paint(g, width, height, this::paintSky);
			drawTwinklers(g);

			// Back half of the plane, then the bodies behind the sun, the sun, and
			// everything in front of it - the painter's algorithm, done around the star.
			drawOrbits(g, false);
			drawBelt(g, false);
			sortBodies();
			var index = 0;
			for (; index < bodyOrder.length && bodyDepth[bodyOrder[index]] < 0; index++) {
				drawBody(g, bodyOrder[index]);
			}
			drawSun(g);
			drawOrbits(g, true);
			drawBelt(g, true);
			for (; index < bodyOrder.length; index++) {
				drawBody(g, bodyOrder[index]);
			}
			finishLayer.paint(g, width, height, this::paintFinish);
		} finally {
			g.dispose();
		}
	}

	private void setUpScene(int width, int height, long elapsedMillis) {
		this.width = width;
		this.height = height;
		time = elapsedMillis / 1_000.0 * TIME_SCALE;
		unit = Math.min(width, height);
		centerX = width * 0.5;
		centerY = height * 0.5;
		// The camera breathes: a slow sway of its tilt over the ecliptic.
		var tilt = 0.43 + Math.sin(time * 0.05) * 0.07;
		tiltSin = Math.sin(tilt);
		tiltCos = Math.cos(tilt);
		// Fill the width, but never let the tilted system spill off the top and bottom.
		scale = Math.min(width * 0.47, height * 0.46 / (tiltSin * 1.25));
		layoutOrbits();
		layoutBelt();
	}

	/**
	 * Project a point in the orbital frame - radius units, y up - onto the screen,
	 * leaving the result in the {@code proj*} fields.
	 */
	private void project(double x, double y, double z) {
		var depth = z * tiltCos + y * tiltSin;
		var perspective = FOCAL / (FOCAL - depth);
		projX = centerX + x * scale * perspective;
		projY = centerY + (z * tiltSin - y * tiltCos) * scale * perspective;
		projScale = perspective;
		projDepth = depth;
	}

	// ---------------------------------------------------------------- sky

	private void paintSky(Graphics2D g, int width, int height) {
		g.setPaint(new GradientPaint(0, 0, new Color(2, 3, 12), width, height, new Color(8, 3, 20)));
		g.fillRect(0, 0, width, height);
		var random = new Random(0x50_1A_12L);
		var diagonal = Math.hypot(width, height);

		// Nebula: a few hundred soft, faint blobs clustered along a sweeping band.
		Color[] nebula = { new Color(110, 40, 160), new Color(30, 110, 150), new Color(170, 50, 110),
				new Color(40, 60, 150) };
		for (var blob = 0; blob < 90; blob++) {
			var along = random.nextDouble();
			var x = along * width + random.nextGaussian() * width * 0.08;
			var y = height * (0.15 + along * 0.7) + random.nextGaussian() * height * 0.13;
			var radius = (float) (diagonal * (0.04 + random.nextDouble() * 0.12));
			var tone = nebula[random.nextInt(nebula.length)];
			g.setPaint(new RadialGradientPaint((float) x, (float) y, radius, new float[] { 0f, 1f },
					new Color[] { withAlpha(tone, 9 + random.nextInt(12)), withAlpha(tone, 0) }));
			g.fillRect((int) (x - radius), (int) (y - radius), (int) (radius * 2), (int) (radius * 2));
		}

		// Stars: a sparse field everywhere, a dense Milky Way along the same band.
		var stars = width * height / 700;
		for (var star = 0; star < stars; star++) {
			double x;
			double y;
			if (star % 3 == 0) {
				var along = random.nextDouble();
				x = along * width;
				y = height * (0.15 + along * 0.7) + random.nextGaussian() * height * 0.07;
			} else {
				x = random.nextDouble() * width;
				y = random.nextDouble() * height;
			}
			var bright = Math.pow(random.nextDouble(), 3);
			var temperature = random.nextDouble();
			var color = temperature < 0.15 ? new Color(255, 200, 160)
					: temperature < 0.35 ? new Color(170, 200, 255) : new Color(235, 240, 255);
			g.setColor(withAlpha(color, (int) (30 + bright * 225)));
			var size = bright > 0.82 ? 2 : 1;
			g.fillRect((int) x, (int) y, size, size);
		}

		for (var i = 0; i < TWINKLERS; i++) {
			twinkleX[i] = random.nextDouble() * width;
			twinkleY[i] = random.nextDouble() * height;
			twinklePhase[i] = random.nextDouble() * TAU;
			twinkleSize[i] = Math.min(width, height) * (0.003 + random.nextDouble() * 0.005);
		}

		// The sun never leaves the centre of the frame, so its broad corona is part of
		// the sky: two screen-sized sprite blits a frame were most of the frame's cost.
		var sunX = width * 0.5;
		var sunY = height * 0.5;
		var sunRadius = Math.min(width, height) * 0.042;
		drawSprite(g, CORONA, sunX, sunY, sunRadius * 11, sunRadius * 11, 0.42);
		drawSprite(g, CORONA, sunX, sunY, sunRadius * 5.5, sunRadius * 5.5, 0.55);
	}

	/** A handful of bright stars with a slow scintillating cross flare. */
	private void drawTwinklers(Graphics2D g) {
		for (var i = 0; i < TWINKLERS; i++) {
			var shimmer = 0.5 + 0.5 * Math.sin(time * 1.3 + twinklePhase[i]) * Math.sin(time * 0.71 + i);
			var size = twinkleSize[i] * (0.7 + shimmer * 0.6);
			var alpha = 0.25 + shimmer * 0.55;
			drawSprite(g, WHITE_GLOW, twinkleX[i], twinkleY[i], size * 2.2, size * 2.2, alpha * 0.35);
			drawSprite(g, STREAK, twinkleX[i], twinkleY[i], size * 5, size * 0.28, alpha * 0.8);
			drawSprite(g, STREAK, twinkleX[i], twinkleY[i], size * 0.28, size * 5, alpha * 0.8);
		}
	}

	// ---------------------------------------------------------------- the sun

	private void drawSun(Graphics2D g) {
		project(0, 0, 0);
		var x = projX;
		var y = projY;
		var radius = unit * 0.042;
		var pulse = 1 + Math.sin(time * 0.9) * 0.04 + Math.sin(time * 2.3) * 0.02;
		// The wide corona is baked into the sky; only the inner, breathing glow is live.
		drawSprite(g, CORONA, x, y, radius * 3.4 * pulse, radius * 3.4 * pulse, 0.45);

		// God rays: two counter-rotating fans of long, thin glow sprites.
		for (var fan = 0; fan < 2; fan++) {
			var count = fan == 0 ? 8 : 13;
			var spin = time * (fan == 0 ? 0.035 : -0.022) + fan * 0.4;
			for (var ray = 0; ray < count; ray++) {
				var angle = spin + ray * TAU / count;
				var length = radius * (fan == 0 ? 8.5 : 5.5) * (0.8 + 0.2 * Math.sin(time * 0.6 + ray * 1.7));
				drawRotatedSprite(g, SUN_HALO, x, y, length, radius * 0.22, angle,
						fan == 0 ? 0.20 : 0.13);
			}
		}

		drawSprite(g, SUN_HALO, x, y, radius * 2.6, radius * 2.6, 0.95);
		circle.setFrame(x - radius, y - radius, radius * 2, radius * 2);
		g.setPaint(new RadialGradientPaint((float) (x - radius * 0.2), (float) (y - radius * 0.2), (float) radius * 1.2f,
				new float[] { 0f, 0.55f, 0.85f, 1f },
				new Color[] { new Color(255, 255, 246), new Color(255, 236, 170), new Color(255, 176, 70),
						new Color(255, 120, 40) }));
		g.fill(circle);
		// The anamorphic streak every modern space shot has.
		drawSprite(g, STREAK, x, y, radius * 16, radius * 0.32, 0.55);
		drawSprite(g, WHITE_GLOW, x, y, radius * 5, radius * 0.12, 0.7);
	}

	// ---------------------------------------------------------------- orbits and belt

	private void drawOrbits(Graphics2D g, boolean front) {
		g.setStroke(HAIRLINE);
		for (var planetIndex = 0; planetIndex < PLANETS.length; planetIndex++) {
			var planet = PLANETS[planetIndex];
			var position = planetPosition[planetIndex];
			var xs = orbitX[planetIndex];
			var ys = orbitY[planetIndex];
			var depths = orbitDepth[planetIndex];
			for (var segment = 0; segment <= ORBIT_SEGMENTS; segment++) {
				var angle = segment * TAU / ORBIT_SEGMENTS;
				if (segment > 0 && (depths[segment] + depths[segment - 1] >= 0) == front) {
					// A comet-like trail: bright just behind the planet, fading around the orbit.
					var behind = Math.floorMod((long) Math.round((position - angle) / TAU * 10_000), 10_000L) / 10_000.0;
					var trail = Math.pow(1 - behind, 7);
					var nearness = (depths[segment] + 1) * 0.5;
					var alpha = 0.05 + nearness * 0.05 + trail * 0.5;
					line.setLine(xs[segment - 1], ys[segment - 1], xs[segment], ys[segment]);
					g.setColor(planet.orbitTint(alpha));
					g.draw(line);
				}
			}
		}
	}

	private void layoutOrbits() {
		for (var planetIndex = 0; planetIndex < PLANETS.length; planetIndex++) {
			var planet = PLANETS[planetIndex];
			planetPosition[planetIndex] = orbitAngle(planet);
			for (var segment = 0; segment <= ORBIT_SEGMENTS; segment++) {
				project(ORBIT_COS[segment] * planet.orbit, 0, ORBIT_SIN[segment] * planet.orbit);
				orbitX[planetIndex][segment] = projX;
				orbitY[planetIndex][segment] = projY;
				orbitDepth[planetIndex][segment] = projDepth;
			}
		}
	}

	private void drawBelt(Graphics2D g, boolean front) {
		for (var i = 0; i < ASTEROIDS; i++) {
			if (asteroidDepth[i] >= 0 != front) continue;
			var light = 0.25 + (asteroidDepth[i] + 1) * 0.3;
			g.setColor(ASTEROID_TONES[asteroidTone[i]][(int) Math.clamp(light * 15, 0, 15)]);
			var size = asteroidSize[i] * asteroidScale[i];
			circle.setFrame(asteroidX[i] - size * 0.5, asteroidY[i] - size * 0.5, size, size);
			g.fill(circle);
		}
	}

	private void layoutBelt() {
		for (var i = 0; i < ASTEROIDS; i++) {
			var radius = asteroidRadius[i];
			var angle = asteroidPhase[i] + asteroidVelocity[i] * time;
			project(Math.cos(angle) * radius, asteroidLift[i], Math.sin(angle) * radius);
			asteroidX[i] = projX;
			asteroidY[i] = projY;
			asteroidScale[i] = projScale;
			asteroidDepth[i] = projDepth;
		}
	}

	private static final Color[][] ASTEROID_TONES = asteroidTones();

	private static Color[][] asteroidTones() {
		Color[] tones = { new Color(200, 180, 160), new Color(160, 150, 150), new Color(220, 170, 120) };
		var table = new Color[tones.length][16];
		for (var tone = 0; tone < tones.length; tone++) {
			for (var level = 0; level < 16; level++) {
				table[tone][level] = withAlpha(tones[tone], 60 + level * 13);
			}
		}
		return table;
	}

	// ---------------------------------------------------------------- bodies

	/** Planets are bodies 0..n-1; the comet is body n. Sorted far to near. */
	private void sortBodies() {
		for (var i = 0; i < PLANETS.length; i++) {
			var angle = orbitAngle(PLANETS[i]);
			project(Math.cos(angle) * PLANETS[i].orbit, 0, Math.sin(angle) * PLANETS[i].orbit);
			bodyDepth[i] = projDepth;
		}
		cometPosition();
		project(cometX, cometY, cometZ);
		bodyDepth[PLANETS.length] = projDepth;
		for (var i = 0; i < bodyOrder.length; i++) bodyOrder[i] = i;
		for (var i = 1; i < bodyOrder.length; i++) {
			var current = bodyOrder[i];
			var j = i - 1;
			while (j >= 0 && bodyDepth[bodyOrder[j]] > bodyDepth[current]) {
				bodyOrder[j + 1] = bodyOrder[j];
				j--;
			}
			bodyOrder[j + 1] = current;
		}
	}

	private void drawBody(Graphics2D g, int body) {
		if (body == PLANETS.length) {
			drawComet(g);
		} else {
			drawPlanet(g, PLANETS[body]);
		}
	}

	private double orbitAngle(Planet planet) {
		return planet.phase + KEPLER / Math.pow(planet.orbit, 1.5) * time;
	}

	private void drawPlanet(Graphics2D g, Planet planet) {
		var angle = orbitAngle(planet);
		var worldX = Math.cos(angle) * planet.orbit;
		var worldZ = Math.sin(angle) * planet.orbit;
		project(worldX, 0, worldZ);
		var x = projX;
		var y = projY;
		var perspective = projScale;
		var depth = projDepth;
		var radius = planet.size * unit * perspective;

		// The sun's direction from the planet, in camera space.
		var length = Math.hypot(worldX, worldZ);
		var towardX = -worldX / length;
		var towardZ = -worldZ / length;
		var lightScreenX = towardX;
		var lightScreenY = towardZ * tiltSin;
		var lightFacing = towardZ * tiltCos; // > 0: the lit side faces the camera

		if (planet.moons > 0) drawMoons(g, planet, worldX, worldZ, radius, depth, false);
		if (planet.ring > 0) drawRing(g, planet, x, y, radius, false);

		drawSprite(g, WHITE_GLOW, x, y, radius * 2.6, radius * 2.6, 0.05);
		drawSprite(g, glowFor(planet), x, y, radius * 2.1, radius * 2.1, 0.30);

		circle.setFrame(x - radius, y - radius, radius * 2, radius * 2);
		var pg = (Graphics2D) g.create();
		try {
			pg.clip(circle);
			pg.setColor(planet.base);
			pg.fill(circle);
			drawSurface(pg, planet, x, y, radius);

			// The terminator: a gradient centred towards the sun, wide when the day side
			// faces us, tight when we are looking at the night side.
			var reach = radius * (1.6 + 0.9 * lightFacing);
			var focusX = x + lightScreenX * radius * 0.55;
			var focusY = y + lightScreenY * radius * 0.55;
			pg.setPaint(new RadialGradientPaint((float) focusX, (float) focusY, (float) Math.max(radius * 0.9, reach),
					new float[] { 0f, 0.40f, 0.75f, 1f },
					new Color[] { new Color(255, 250, 235, 80), new Color(0, 0, 0, 0), new Color(0, 0, 8, 150),
							new Color(0, 0, 8, 215) }));
			pg.fill(circle);
		} finally {
			pg.dispose();
		}
		// Rim light: a thin atmospheric edge on the sunward limb.
		drawSprite(g, glowFor(planet), x + lightScreenX * radius * 0.55, y + lightScreenY * radius * 0.55,
				radius * 1.1, radius * 1.1, 0.22 + Math.max(0, -lightFacing) * 0.35);

		if (planet.ring > 0) drawRing(g, planet, x, y, radius, true);
		if (planet.moons > 0) drawMoons(g, planet, worldX, worldZ, radius, depth, true);
	}

	/** Cloud bands and a few surface details, drawn inside the planet's clip. */
	private void drawSurface(Graphics2D g, Planet planet, double x, double y, double radius) {
		var bands = switch (planet.name) {
			case "jupiter" -> 9;
			case "saturn" -> 6;
			case "venus", "uranus", "neptune" -> 3;
			default -> 0;
		};
		for (var band = 0; band < bands; band++) {
			var offset = -1 + (band + 0.5) * 2.0 / bands;
			var wobble = Math.sin(time * 0.3 + band * 1.9 + planet.phase) * 0.05;
			var thickness = radius * (0.10 + 0.07 * Math.abs(Math.sin(band * 2.3)));
			g.setColor(withAlpha(band % 2 == 0 ? planet.accent : planet.base.brighter(), band % 2 == 0 ? 120 : 60));
			g.fill(new java.awt.geom.Rectangle2D.Double(x - radius, y + (offset + wobble) * radius - thickness / 2,
					radius * 2, thickness));
		}
		switch (planet.name) {
			case "jupiter" -> {
				// The Great Red Spot, drifting across the disc as the planet turns.
				var drift = ((time * 0.02 + 0.3) % 1.0) * 2.6 - 1.3;
				g.setColor(new Color(190, 90, 60, 170));
				g.fill(new Ellipse2D.Double(x + drift * radius - radius * 0.18, y + radius * 0.22,
						radius * 0.36, radius * 0.2));
			}
			case "earth" -> {
				var drift = (time * 0.05) % 1.0;
				g.setColor(new Color(70, 150, 90, 190));
				for (var land = 0; land < 3; land++) {
					var lx = ((drift + land * 0.37) % 1.0) * 2.8 - 1.4;
					g.fill(new Ellipse2D.Double(x + lx * radius - radius * 0.3, y + (land - 1) * radius * 0.45 - radius * 0.2,
							radius * 0.6, radius * 0.4));
				}
				g.setColor(new Color(250, 252, 255, 110));
				g.fill(new Ellipse2D.Double(x - radius * 0.9, y - radius * 1.05, radius * 1.8, radius * 0.35));
			}
			case "mars" -> {
				g.setColor(new Color(245, 235, 230, 170));
				g.fill(new Ellipse2D.Double(x - radius * 0.35, y - radius * 1.02, radius * 0.7, radius * 0.25));
			}
			default -> {
			}
		}
	}

	/** Saturn's and Uranus's rings: the back half before the planet, the front half after it. */
	private void drawRing(Graphics2D g, Planet planet, double x, double y, double radius, boolean front) {
		var rg = (Graphics2D) g.create();
		try {
			rg.translate(x, y);
			rg.rotate(planet.ring == 2 ? planet.ringTilt : 0);
			var flatten = planet.ring == 2 ? 0.95 : tiltSin * 0.9;
			var span = (int) Math.ceil(radius * 3);
			clipRect.setBounds(-span, front ? 0 : -span, span * 2, span);
			rg.clip(clipRect);
			if (planet.ring == 1) rg.rotate(-planet.ringTilt * 0.3);
			var layers = planet.ring == 1 ? 7 : 2;
			for (var layer = 0; layer < layers; layer++) {
				var outer = radius * (planet.ring == 1 ? 1.45 + layer * 0.16 : 1.55 + layer * 0.12);
				if (planet.ring == 1 && layer == 4) continue; // the Cassini division
				var width = (float) Math.max(0.6, radius * (planet.ring == 1 ? 0.12 : 0.04));
				rg.setStroke(new BasicStroke(width));
				var tone = planet.ring == 1
						? (layer % 2 == 0 ? new Color(236, 214, 170) : new Color(190, 160, 120))
						: new Color(180, 230, 236);
				rg.setColor(withAlpha(tone, planet.ring == 1 ? 110 - layer * 8 : 70));
				circle.setFrame(-outer, -outer * flatten, outer * 2, outer * 2 * flatten);
				rg.draw(circle);
			}
		} finally {
			rg.dispose();
		}
	}

	private void drawMoons(Graphics2D g, Planet planet, double worldX, double worldZ, double planetRadius,
			double planetDepth, boolean front) {
		var worldPerPixel = 1.0 / (scale * projScaleAt(worldX, worldZ));
		for (var moon = 0; moon < planet.moons; moon++) {
			var distance = planetRadius * (2.2 + moon * 0.85) * worldPerPixel;
			var angle = time * (1.1 - moon * 0.22) * (planet.moons == 1 ? 0.7 : 1) + moon * 2.1 + planet.phase;
			project(worldX + Math.cos(angle) * distance, 0, worldZ + Math.sin(angle) * distance);
			if (projDepth >= planetDepth != front) continue;
			var size = Math.max(1.0, planetRadius * (planet.moons == 1 ? 0.27 : 0.12));
			drawSprite(g, WHITE_GLOW, projX, projY, size * 2.5, size * 2.5, 0.12);
			circle.setFrame(projX - size, projY - size, size * 2, size * 2);
			g.setColor(new Color(214, 210, 204));
			g.fill(circle);
			g.setColor(new Color(0, 0, 10, 130));
			var shade = Math.cos(angle) > 0 ? -size * 0.5 : size * 0.5;
			circle.setFrame(projX - size + shade, projY - size, size * 2, size * 2);
			g.fill(circle);
		}
	}

	private double projScaleAt(double worldX, double worldZ) {
		return FOCAL / (FOCAL - worldZ * tiltCos);
	}

	// ---------------------------------------------------------------- comet

	private double cometX;
	private double cometY;
	private double cometZ;
	private double cometDistance;

	/** A true Kepler orbit: solve for the eccentric anomaly, then place the comet on its ellipse. */
	private void cometPosition() {
		var semiMajor = 0.78;
		var eccentricity = 0.86;
		var period = 150.0;
		var mean = TAU * (time / period + 0.62);
		var eccentric = mean;
		for (var i = 0; i < 8; i++) {
			eccentric = mean + eccentricity * Math.sin(eccentric);
		}
		var px = semiMajor * (Math.cos(eccentric) - eccentricity);
		var pz = semiMajor * Math.sqrt(1 - eccentricity * eccentricity) * Math.sin(eccentric);
		var argument = 2.35;
		var cos = Math.cos(argument);
		var sin = Math.sin(argument);
		cometX = px * cos - pz * sin;
		cometZ = px * sin + pz * cos;
		cometY = pz * 0.18;
		cometDistance = Math.hypot(cometX, Math.hypot(cometY, cometZ));
	}

	private void drawComet(Graphics2D g) {
		project(cometX, cometY, cometZ);
		var headX = projX;
		var headY = projY;
		var perspective = projScale;
		// Tails point straight away from the sun and grow as the comet heats up near it.
		var heat = Math.clamp(0.22 / Math.max(0.08, cometDistance), 0.25, 2.4);
		var awayX = cometX / cometDistance;
		var awayY = cometY / cometDistance;
		var awayZ = cometZ / cometDistance;
		var tailLength = 0.20 * heat;
		project(cometX + awayX * tailLength, cometY + awayY * tailLength, cometZ + awayZ * tailLength);
		var tipX = projX;
		var tipY = projY;
		var size = unit * 0.0045 * perspective;

		var steps = 28;
		for (var step = steps; step >= 1; step--) {
			var t = step / (double) steps;
			var fade = Math.pow(1 - t, 1.6);
			var ionX = headX + (tipX - headX) * t;
			var ionY = headY + (tipY - headY) * t;
			drawSprite(g, ION_TAIL, ionX, ionY, size * (2 + t * 7), size * (2 + t * 7), 0.16 * fade * heat);
			// The dust tail lags and curves away behind the orbit.
			var curl = t * t * 0.35;
			var dustX = headX + (tipX - headX) * t * 0.8 + (tipY - headY) * curl;
			var dustY = headY + (tipY - headY) * t * 0.8 - (tipX - headX) * curl;
			drawSprite(g, DUST_TAIL, dustX, dustY, size * (2 + t * 9), size * (2 + t * 9), 0.10 * fade * heat);
		}
		drawSprite(g, ION_TAIL, headX, headY, size * 7, size * 7, 0.35);
		drawSprite(g, WHITE_GLOW, headX, headY, size * 2.4, size * 2.4, 0.95);
	}

	// ---------------------------------------------------------------- finish and helpers

	private void paintFinish(Graphics2D g, int width, int height) {
		var radius = Math.max(width, height) * 0.78f;
		g.setPaint(new RadialGradientPaint(width / 2f, height / 2f, radius,
				new float[] { 0.40f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 200) }));
		g.fillRect(0, 0, width, height);
		SCANLINES.paint(g, width, height);
	}

	/** Blit a glow sprite centred on a subpixel position, stretched to the given half-extents. */
	private void drawSprite(Graphics2D g, BufferedImage image, double x, double y, double halfWidth,
			double halfHeight, double alpha) {
		if (alpha <= 0.004 || halfWidth <= 0.05 || halfHeight <= 0.05) return;
		var previous = g.getComposite();
		g.setComposite(composite(alpha));
		sprite.setTransform(halfWidth * 2 / SPRITE_SIZE, 0, 0, halfHeight * 2 / SPRITE_SIZE,
				x - halfWidth, y - halfHeight);
		g.drawImage(image, sprite, null);
		g.setComposite(previous);
	}

	private void drawRotatedSprite(Graphics2D g, BufferedImage image, double x, double y, double halfWidth,
			double halfHeight, double angle, double alpha) {
		if (alpha <= 0.004 || halfWidth <= 0.05 || halfHeight <= 0.05) return;
		var previous = g.getComposite();
		g.setComposite(composite(alpha));
		sprite.setToTranslation(x, y);
		sprite.rotate(angle);
		sprite.scale(halfWidth * 2 / SPRITE_SIZE, halfHeight * 2 / SPRITE_SIZE);
		sprite.translate(-SPRITE_SIZE / 2.0, -SPRITE_SIZE / 2.0);
		g.drawImage(image, sprite, null);
		g.setComposite(previous);
	}

	private static BufferedImage glowFor(Planet planet) {
		return planet.glow;
	}

	/** A premultiplied radial glow: full colour at the centre, falling to nothing at the edge. */
	private static BufferedImage glowSprite(Color color, double falloff) {
		var image = new BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB_PRE);
		var data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		var half = SPRITE_SIZE / 2.0;
		for (var y = 0; y < SPRITE_SIZE; y++) {
			for (var x = 0; x < SPRITE_SIZE; x++) {
				var distance = Math.hypot(x + 0.5 - half, y + 0.5 - half) / half;
				var strength = distance >= 1 ? 0 : Math.pow(1 - distance, falloff);
				var a = (int) Math.round(strength * 255);
				data[y * SPRITE_SIZE + x] = a << 24
						| (int) (color.getRed() * strength) << 16
						| (int) (color.getGreen() * strength) << 8
						| (int) (color.getBlue() * strength);
			}
		}
		return image;
	}

	private static AlphaComposite composite(double alpha) {
		var level = (int) Math.round(Math.clamp(alpha, 0, 1) * (ALPHA_LEVELS - 1));
		return COMPOSITES[level];
	}

	private static AlphaComposite[] composites() {
		var composites = new AlphaComposite[ALPHA_LEVELS];
		for (var level = 0; level < composites.length; level++) {
			composites[level] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
					level / (float) (ALPHA_LEVELS - 1));
		}
		return composites;
	}

	private static double[] orbitCoordinates(boolean cosine) {
		var coordinates = new double[ORBIT_SEGMENTS + 1];
		for (var segment = 0; segment <= ORBIT_SEGMENTS; segment++) {
			var angle = segment * TAU / ORBIT_SEGMENTS;
			coordinates[segment] = cosine ? Math.cos(angle) : Math.sin(angle);
		}
		return coordinates;
	}

	private static Color withAlpha(Color color, int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.clamp(alpha, 0, 255));
	}

	private static final class Planet {

		private final String name;
		private final double orbit;
		private final double size;
		private final double phase;
		private final Color base;
		private final Color accent;
		private final int moons;
		/** 0 none, 1 Saturn's broad rings, 2 Uranus's thin tilted rings. */
		private final int ring;
		private final double ringTilt;
		private final BufferedImage glow;
		private final Color[] orbitTints = new Color[256];

		private Planet(String name, double orbit, double size, double phase, Color base, Color accent, int moons,
				int ring, double ringTilt) {
			this.name = name;
			this.orbit = orbit;
			this.size = size;
			this.phase = phase;
			this.base = base;
			this.accent = accent;
			this.moons = moons;
			this.ring = ring;
			this.ringTilt = ringTilt;
			this.glow = glowSprite(base, 2.0);
			for (var alpha = 0; alpha < orbitTints.length; alpha++) {
				orbitTints[alpha] = withAlpha(base, alpha);
			}
		}

		private Color orbitTint(double alpha) {
			return orbitTints[(int) Math.clamp(alpha * 255, 0, 255)];
		}
	}
}
