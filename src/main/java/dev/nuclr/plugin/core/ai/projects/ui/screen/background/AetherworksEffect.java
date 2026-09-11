package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A restrained industrial-fantasy background built from slow parallax layers. */
final class AetherworksEffect implements DesktopBackgroundEffect {

	private static final double TAU = Math.PI * 2;
	private static final int MOTE_COUNT = 42;
	private static final Color CHARCOAL = new Color(6, 9, 12);
	private static final Color IRON = new Color(21, 24, 27);
	private static final Color BRASS_DARK = new Color(70, 45, 19);
	private static final Color BRASS = new Color(186, 132, 57);
	private static final Color BRASS_LIGHT = new Color(244, 213, 142);
	private static final Color COPPER = new Color(181, 78, 43);
	private static final Color VERDIGRIS = new Color(42, 156, 146);
	private static final Color AETHER = new Color(125, 235, 207);
	private static final Color STEAM = new Color(196, 214, 204);

	private static final BasicStroke HAIRLINE = new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke FINE = new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke PIPE_SHADOW = new BasicStroke(13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke PIPE_BODY = new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke PIPE_LIGHT = new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke STEAM_BODY = new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke STEAM_EDGE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke LOCAL_GEAR_EDGE = new BasicStroke(0.018f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke LOCAL_GEAR_INNER = new BasicStroke(0.010f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	private final Random random = new Random(0xAE7E_1987L);
	private final List<Mote> motes = new ArrayList<>();
	private final Path2D.Double gear = new Path2D.Double();
	private final Path2D.Double pipe = new Path2D.Double();
	private final Path2D.Double vapor = new Path2D.Double();
	private final Ellipse2D.Double oval = new Ellipse2D.Double();
	private final Arc2D.Double arc = new Arc2D.Double();
	private final Line2D.Double line = new Line2D.Double();

	private GradientPaint backdrop;
	private RadialGradientPaint warmAtmosphere;
	private RadialGradientPaint coolAtmosphere;
	private RadialGradientPaint vignette;
	private RadialGradientPaint coreGlow;
	private int cachedWidth;
	private int cachedHeight;
	private long lastElapsed = -1;

	@Override
	public String id() {
		return "steampunk-engine";
	}

	@Override
	public String displayName() {
		return "Aetherworks";
	}

	@Override
	public String description() {
		return "Cinematic brass machinery, an aether orrery, drifting vapor and warm industrial light.";
	}

	@Override
	public void reset() {
		motes.clear();
		lastElapsed = -1;
		backdrop = null;
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		var g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			ensurePaints(width, height);
			drawAtmosphere(g, width, height);
			drawArchitecture(g, width, height, elapsedMillis);
			drawEdgeMachinery(g, width, height, elapsedMillis);
			drawOrrery(g, width, height, elapsedMillis);
			drawVapor(g, width, height, elapsedMillis);
			advanceMotes(width, height, elapsedMillis);
			drawMotes(g);
			drawFinish(g, width, height);
		} finally {
			g.dispose();
		}
	}

	private void ensurePaints(int width, int height) {
		if (width == cachedWidth && height == cachedHeight && backdrop != null) return;
		cachedWidth = width;
		cachedHeight = height;
		backdrop = new GradientPaint(0, 0, CHARCOAL, width, height, new Color(25, 15, 14));
		var radius = Math.max(width, height) * 0.78f;
		warmAtmosphere = new RadialGradientPaint(width * 0.78f, height * 0.42f, radius,
				new float[] { 0f, 0.42f, 1f },
				new Color[] { new Color(132, 72, 27, 78), new Color(73, 36, 23, 30), new Color(0, 0, 0, 0) });
		coolAtmosphere = new RadialGradientPaint(width * 0.08f, height * 0.92f, radius * 0.72f,
				new float[] { 0f, 0.55f, 1f },
				new Color[] { new Color(13, 86, 91, 60), new Color(8, 48, 58, 22), new Color(0, 0, 0, 0) });
		vignette = new RadialGradientPaint(width * 0.53f, height * 0.48f, radius,
				new float[] { 0.30f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 205) });
		var coreRadius = Math.min(width, height) * 0.18f;
		coreGlow = new RadialGradientPaint(width * 0.72f, height * 0.48f, coreRadius,
				new float[] { 0f, 0.18f, 0.52f, 1f },
				new Color[] { new Color(245, 255, 226, 220), new Color(107, 229, 189, 140),
						new Color(25, 125, 116, 40), new Color(0, 0, 0, 0) });
	}

	private void drawAtmosphere(Graphics2D g, int width, int height) {
		g.setPaint(backdrop);
		g.fillRect(0, 0, width, height);
		g.setPaint(warmAtmosphere);
		g.fillRect(0, 0, width, height);
		g.setPaint(coolAtmosphere);
		g.fillRect(0, 0, width, height);
	}

	private void drawArchitecture(Graphics2D g, int width, int height, long elapsedMillis) {
		var floorY = height * 0.84;
		pipe.reset();
		pipe.moveTo(-30, floorY);
		pipe.lineTo(width * 0.30, floorY);
		pipe.curveTo(width * 0.36, floorY, width * 0.35, height * 0.76, width * 0.42, height * 0.76);
		pipe.lineTo(width + 30, height * 0.76);
		drawPipe(g, pipe, COPPER, 0.44f);

		pipe.reset();
		pipe.moveTo(width * 0.02, height * 0.18);
		pipe.lineTo(width * 0.20, height * 0.18);
		pipe.curveTo(width * 0.25, height * 0.18, width * 0.24, height * 0.28, width * 0.29, height * 0.28);
		pipe.lineTo(width * 0.43, height * 0.28);
		drawPipe(g, pipe, VERDIGRIS, 0.28f);

		g.setStroke(HAIRLINE);
		g.setColor(withAlpha(BRASS_LIGHT, 32));
		for (var x = 24; x < width; x += 48) {
			g.drawLine(x, height - 21, x + 10, height - 21);
		}
		var pulseX = (int) ((elapsedMillis * 0.035) % Math.max(1, width));
		g.setColor(withAlpha(AETHER, 28));
		g.drawLine(pulseX - 80, (int) floorY, pulseX, (int) floorY);
	}

	private void drawPipe(Graphics2D g, Path2D path, Color metal, float opacity) {
		g.setStroke(PIPE_SHADOW);
		g.setColor(withAlpha(Color.BLACK, 130));
		g.draw(path);
		g.setStroke(PIPE_BODY);
		g.setColor(alpha(metal, opacity));
		g.draw(path);
		g.setStroke(PIPE_LIGHT);
		g.setColor(alpha(BRASS_LIGHT, opacity * 0.65f));
		g.draw(path);
	}

	private void drawEdgeMachinery(Graphics2D g, int width, int height, long elapsedMillis) {
		var unit = Math.min(width, height);
		var time = elapsedMillis * 0.001;
		drawGear(g, width * 1.015, height * 0.22, unit * 0.33, 22, time * 0.055, BRASS, 0.18f);
		drawGear(g, width * -0.035, height * 0.80, unit * 0.27, 18, -time * 0.070, VERDIGRIS, 0.12f);
		drawGear(g, width * 0.91, height * 0.91, unit * 0.16, 14, -time * 0.095, COPPER, 0.14f);
	}

	private void drawGear(Graphics2D g, double x, double y, double radius, int teeth,
			double rotation, Color metal, float opacity) {
		buildGear(teeth);
		var gg = (Graphics2D) g.create();
		try {
			gg.translate(x, y);
			gg.rotate(rotation);
			gg.scale(radius, radius);
			gg.setColor(alpha(IRON, Math.min(0.94f, opacity + 0.56f)));
			gg.fill(gear);
			gg.setStroke(LOCAL_GEAR_EDGE);
			gg.setColor(alpha(metal, opacity));
			gg.draw(gear);
			gg.setStroke(LOCAL_GEAR_INNER);
			gg.setColor(alpha(BRASS_LIGHT, opacity * 0.66f));
			oval.setFrame(-0.69, -0.69, 1.38, 1.38);
			gg.draw(oval);
			oval.setFrame(-0.27, -0.27, 0.54, 0.54);
			gg.draw(oval);
			for (var spoke = 0; spoke < 8; spoke++) {
				var angle = spoke * TAU / 8;
				line.setLine(Math.cos(angle) * 0.29, Math.sin(angle) * 0.29,
						Math.cos(angle) * 0.68, Math.sin(angle) * 0.68);
				gg.draw(line);
			}
		} finally {
			gg.dispose();
		}
	}

	private void buildGear(int teeth) {
		gear.reset();
		var points = teeth * 4;
		for (var point = 0; point < points; point++) {
			var radius = point % 4 == 1 || point % 4 == 2 ? 1.0 : 0.84;
			var angle = point * TAU / points;
			var x = Math.cos(angle) * radius;
			var y = Math.sin(angle) * radius;
			if (point == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
		}
		gear.closePath();
	}

	private void drawOrrery(Graphics2D g, int width, int height, long elapsedMillis) {
		var unit = Math.min(width, height);
		var centerX = width * 0.72;
		var centerY = height * 0.48;
		var baseRadius = unit * 0.17;
		var time = elapsedMillis * 0.001;

		g.setPaint(coreGlow);
		g.fillOval((int) (centerX - baseRadius), (int) (centerY - baseRadius),
				(int) (baseRadius * 2), (int) (baseRadius * 2));

		for (var ring = 0; ring < 4; ring++) {
			var radius = baseRadius * (0.62 + ring * 0.34);
			var tilt = 0.30 + ring * 0.12;
			var orientation = -0.20 + ring * 0.17;
			var ringGraphics = (Graphics2D) g.create();
			try {
				ringGraphics.translate(centerX, centerY);
				ringGraphics.rotate(orientation);
				ringGraphics.scale(1, tilt);
				ringGraphics.setStroke(ring == 2 ? FINE : HAIRLINE);
				ringGraphics.setColor(alpha(ring % 2 == 0 ? BRASS : VERDIGRIS, 0.34f + ring * 0.08f));
				oval.setFrame(-radius, -radius, radius * 2, radius * 2);
				ringGraphics.draw(oval);
				arc.setArc(-radius, -radius, radius * 2, radius * 2,
						Math.toDegrees(time * (0.12 + ring * 0.035) + ring), 42, Arc2D.OPEN);
				ringGraphics.setColor(alpha(ring % 2 == 0 ? BRASS_LIGHT : AETHER, 0.72f));
				ringGraphics.draw(arc);
			} finally {
				ringGraphics.dispose();
			}

			var phase = time * (0.18 + ring * 0.055) * (ring % 2 == 0 ? 1 : -1) + ring * 1.7;
			var localX = Math.cos(phase) * radius;
			var localY = Math.sin(phase) * radius * tilt;
			var cos = Math.cos(orientation);
			var sin = Math.sin(orientation);
			var nodeX = centerX + localX * cos - localY * sin;
			var nodeY = centerY + localX * sin + localY * cos;
			var nodeSize = 2.2 + ring * 0.7;
			g.setColor(alpha(ring % 2 == 0 ? BRASS_LIGHT : AETHER, 0.20f));
			g.fillOval((int) (nodeX - nodeSize * 3), (int) (nodeY - nodeSize * 3),
					(int) (nodeSize * 6), (int) (nodeSize * 6));
			g.setColor(ring % 2 == 0 ? BRASS_LIGHT : AETHER);
			g.fillOval((int) (nodeX - nodeSize), (int) (nodeY - nodeSize),
					(int) (nodeSize * 2), (int) (nodeSize * 2));
		}

		g.setColor(new Color(8, 17, 18, 232));
		g.fillOval((int) (centerX - baseRadius * 0.25), (int) (centerY - baseRadius * 0.25),
				(int) (baseRadius * 0.50), (int) (baseRadius * 0.50));
		g.setColor(alpha(BRASS_LIGHT, 0.68f));
		g.setStroke(FINE);
		g.drawOval((int) (centerX - baseRadius * 0.22), (int) (centerY - baseRadius * 0.22),
				(int) (baseRadius * 0.44), (int) (baseRadius * 0.44));
		var pulse = 0.055 + Math.sin(time * 1.35) * 0.010;
		g.setColor(AETHER);
		g.fillOval((int) (centerX - baseRadius * pulse), (int) (centerY - baseRadius * pulse),
				(int) (baseRadius * pulse * 2), (int) (baseRadius * pulse * 2));
	}

	private void drawVapor(Graphics2D g, int width, int height, long elapsedMillis) {
		var time = elapsedMillis * 0.001;
		for (var plume = 0; plume < 5; plume++) {
			var startX = width * (0.34 + plume * 0.12);
			var startY = height * (0.78 + (plume % 2) * 0.06);
			var drift = Math.sin(time * 0.31 + plume * 1.9) * width * 0.018;
			vapor.reset();
			vapor.moveTo(startX, startY);
			vapor.curveTo(startX - width * 0.025 + drift, startY - height * 0.10,
					startX + width * 0.038 + drift, startY - height * 0.20,
					startX + drift, startY - height * 0.34);
			g.setStroke(STEAM_BODY);
			g.setColor(alpha(plume % 3 == 0 ? AETHER : STEAM, 0.028f));
			g.draw(vapor);
			g.setStroke(STEAM_EDGE);
			g.setColor(alpha(plume % 3 == 0 ? VERDIGRIS : STEAM, 0.085f));
			g.draw(vapor);
		}
	}

	private void advanceMotes(int width, int height, long elapsedMillis) {
		while (motes.size() < MOTE_COUNT) motes.add(newMote(width, height, true));
		var delta = lastElapsed < 0 ? 0.032 : Math.min(0.08, Math.max(0, (elapsedMillis - lastElapsed) / 1_000.0));
		lastElapsed = elapsedMillis;
		for (var mote : motes) {
			mote.life += delta;
			mote.x += mote.vx * delta;
			mote.y += mote.vy * delta;
			if (mote.life >= mote.maxLife || mote.y < -8 || mote.x < -8 || mote.x > width + 8) {
				resetMote(mote, width, height, false);
			}
		}
	}

	private Mote newMote(int width, int height, boolean initial) {
		var mote = new Mote();
		resetMote(mote, width, height, initial);
		return mote;
	}

	private void resetMote(Mote mote, int width, int height, boolean initial) {
		mote.x = random.nextDouble() * width;
		mote.y = initial ? random.nextDouble() * height : height + random.nextDouble() * 18;
		mote.vx = (random.nextDouble() - 0.5) * 7;
		mote.vy = -(5 + random.nextDouble() * 13);
		mote.life = 0;
		mote.maxLife = 7 + random.nextDouble() * 11;
		mote.size = 0.5 + random.nextDouble() * 1.2;
		mote.cool = random.nextDouble() < 0.22;
	}

	private void drawMotes(Graphics2D g) {
		for (var mote : motes) {
			var fade = (float) Math.clamp(1 - mote.life / mote.maxLife, 0, 1);
			g.setColor(alpha(mote.cool ? AETHER : BRASS_LIGHT, fade * 0.24f));
			g.fillOval((int) (mote.x - mote.size), (int) (mote.y - mote.size),
					Math.max(1, (int) (mote.size * 2)), Math.max(1, (int) (mote.size * 2)));
		}
	}

	private void drawFinish(Graphics2D g, int width, int height) {
		g.setPaint(vignette);
		g.fillRect(0, 0, width, height);
		g.setStroke(HAIRLINE);
		g.setColor(new Color(231, 203, 144, 7));
		for (var y = 1; y < height; y += 5) g.drawLine(0, y, width, y);
	}

	private static Color alpha(Color color, float opacity) {
		return withAlpha(color, Math.clamp((int) (opacity * 255), 0, 255));
	}

	private static Color withAlpha(Color color, int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}

	private static final class Mote {
		double x;
		double y;
		double vx;
		double vy;
		double life;
		double maxLife;
		double size;
		boolean cool;
	}
}
