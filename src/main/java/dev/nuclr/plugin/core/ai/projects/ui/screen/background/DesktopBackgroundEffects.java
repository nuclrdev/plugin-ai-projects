package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.List;

/** Built-in background effect collection. Add future effects to this list only. */
public final class DesktopBackgroundEffects {

	/** Identifier for the empty background. */
	public static final String NONE = "none";

	private DesktopBackgroundEffects() {
	}

	/** A fresh set of effect instances for one desktop. */
	public static List<DesktopBackgroundEffect> builtIn() {
		return List.of(new NoneEffect(), new NeonNetworkEffect(), new StarfieldEffect());
	}

	private static final class NoneEffect implements DesktopBackgroundEffect {

		@Override
		public String id() {
			return NONE;
		}

		@Override
		public String displayName() {
			return "None";
		}

		@Override
		public String description() {
			return "Use the normal solid desktop background.";
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			// The desktop pane's normal background is already painted underneath.
		}
	}

	/** A depth-projected neon data cloud with bloom, pulses and flowing filaments. */
	private static final class NeonNetworkEffect implements DesktopBackgroundEffect {

		private static final int NODE_COUNT = 150;
		private static final double LINK_DISTANCE = 0.42;
		private static final long LINK_REFRESH_MILLIS = 240;
		private static final BasicStroke RING_STROKE = new BasicStroke(1.0f);
		private static final BasicStroke RING_ACCENT_STROKE = new BasicStroke(1.7f);
		private static final BasicStroke FILAMENT_GLOW_STROKE = new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		private static final BasicStroke FILAMENT_CORE_STROKE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		private static final BasicStroke LINK_GLOW_STROKE = new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		private static final BasicStroke LINK_MID_STROKE = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		private static final BasicStroke LINK_CORE_STROKE = new BasicStroke(0.65f);

		private final java.util.Random random = new java.util.Random(0x80E0_1985L);
		private final List<Node> nodes = new java.util.ArrayList<>();
		private final List<Link> links = new java.util.ArrayList<>();
		private final Ellipse2D.Double shape = new Ellipse2D.Double();
		private final Line2D.Double line = new Line2D.Double();
		private final Path2D.Double filament = new Path2D.Double();
		private static final Color NODE_CORE_COLOR = new Color(222, 250, 255);
		private long lastElapsed = -1;
		private long lastLinkRefresh = -1;
		private GradientPaint backgroundPaint;
		private RadialGradientPaint atmospherePaint;
		private RadialGradientPaint vignettePaint;
		private int cachedWidth;
		private int cachedHeight;

		@Override
		public String id() {
			return "neon-network";
		}

		@Override
		public String displayName() {
			return "Neon Data Cloud";
		}

		@Override
		public String description() {
			return "A depth-projected cyan/magenta data cloud with pulses and bloom.";
		}

		@Override
		public void reset() {
			nodes.clear();
			links.clear();
			lastElapsed = -1;
			lastLinkRefresh = -1;
			backgroundPaint = null;
			atmospherePaint = null;
			vignettePaint = null;
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) {
				return;
			}
			var g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				drawBackdrop(g, width, height);
				ensureNodes();
				advance(elapsedMillis);
				var projected = project(width, height, elapsedMillis);
				if (lastLinkRefresh < 0 || elapsedMillis - lastLinkRefresh >= LINK_REFRESH_MILLIS) {
					rebuildLinks(projected, elapsedMillis);
				}
				drawOrbitRings(g, width, height, elapsedMillis);
				drawFilaments(g, width, height, elapsedMillis);
				drawLinks(g, projected, elapsedMillis);
				drawNodes(g, projected, elapsedMillis);
				drawVignette(g, width, height);
			} finally {
				g.dispose();
			}
		}

		private void ensureNodes() {
			if (nodes.isEmpty()) {
				for (var i = 0; i < NODE_COUNT; i++) {
					nodes.add(new Node(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1,
						0.18 + random.nextDouble() * 1.55, random.nextDouble() * 0.5 + 0.25,
						random.nextDouble() * Math.PI * 2));
				}
			}
		}

		private void advance(long elapsedMillis) {
			var delta = lastElapsed < 0 ? 0.032 : Math.min(0.08, Math.max(0, (elapsedMillis - lastElapsed) / 1_000.0));
			lastElapsed = elapsedMillis;
			for (var node : nodes) {
				node.z -= delta * 0.055 * node.speed;
				if (node.z < 0.16) {
					node.x = random.nextDouble() * 2 - 1;
					node.y = random.nextDouble() * 2 - 1;
					node.z = 1.65;
				}
			}
		}

		private List<Projection> project(int width, int height, long elapsedMillis) {
			var angle = elapsedMillis * 0.000035;
			var cos = Math.cos(angle);
			var sin = Math.sin(angle);
			var focal = Math.min(width, height) * 0.68;
			var cx = width / 2.0;
			var cy = height / 2.0;
			var projected = new java.util.ArrayList<Projection>(nodes.size());
			for (var index = 0; index < nodes.size(); index++) {
				var node = nodes.get(index);
				var x = node.x * cos - (node.z - 0.9) * sin * 0.36;
				var z = node.x * sin * 0.36 + (node.z - 0.9) * cos + 0.9;
				var y = node.y + Math.sin(elapsedMillis * 0.00045 + node.phase) * 0.055;
				var scale = focal / Math.max(0.14, z);
				var px = cx + x * scale;
				var py = cy + y * scale * 0.82;
				var size = Math.clamp(1.2 + (1.7 - z) * 2.7, 1.2, 6.5);
				var hue = (float) (0.52 + 0.34 * ((index / (double) nodes.size() + elapsedMillis / 28_000.0) % 1.0));
				projected.add(new Projection(node, px, py, z, size, Color.getHSBColor(hue, 0.83f, 1f)));
			}
			return projected;
		}

		private void drawBackdrop(Graphics2D g, int width, int height) {
			ensurePaints(width, height);
			g.setPaint(backgroundPaint);
			g.fillRect(0, 0, width, height);
			g.setPaint(atmospherePaint);
			g.fillRect(0, 0, width, height);
		}

		private void ensurePaints(int width, int height) {
			if (width == cachedWidth && height == cachedHeight && backgroundPaint != null) return;
			cachedWidth = width;
			cachedHeight = height;
			backgroundPaint = new GradientPaint(0, 0, new Color(3, 5, 25), width, height,
					new Color(18, 2, 36));
			var radius = Math.max(width, height) * 0.7f;
			atmospherePaint = new RadialGradientPaint(width * 0.52f, height * 0.46f, radius,
					new float[] { 0f, 0.38f, 1f },
					new Color[] { new Color(28, 16, 75, 135), new Color(13, 32, 69, 54), new Color(0, 0, 0, 0) });
			var vignetteRadius = Math.max(width, height) * 0.78f;
			vignettePaint = new RadialGradientPaint(width / 2f, height / 2f, vignetteRadius,
					new float[] { 0.45f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 155) });
		}

		private void drawOrbitRings(Graphics2D g, int width, int height, long elapsedMillis) {
			var cx = width / 2.0;
			var cy = height / 2.0;
			for (var ring = 0; ring < 5; ring++) {
				var phase = elapsedMillis * 0.00018 + ring * 0.82;
				var radius = Math.min(width, height) * (0.12 + ring * 0.07);
				var tilt = 0.18 + ring * 0.075;
				var hue = (float) (0.53 + ring * 0.065 + elapsedMillis / 34_000.0 % 0.15);
				g.setColor(alpha(Color.getHSBColor(hue, 0.82f, 1f), 0.13f));
				g.setStroke(ring == 2 ? RING_ACCENT_STROKE : RING_STROKE);
				shape.setFrame(cx - radius, cy - radius * tilt, radius * 2, radius * tilt * 2);
				g.draw(shape);
				g.setColor(alpha(Color.getHSBColor((float) (hue + 0.09), 0.9f, 1f), 0.20f));
				var dotX = cx + Math.cos(phase) * radius;
				var dotY = cy + Math.sin(phase) * radius * tilt;
				shape.setFrame(dotX - 2.5, dotY - 2.5, 5, 5);
				g.fill(shape);
			}
		}

		private void drawFilaments(Graphics2D g, int width, int height, long elapsedMillis) {
			var center = height * 0.51;
			for (var strand = 0; strand < 3; strand++) {
				filament.reset();
				for (var x = -40; x <= width + 40; x += 18) {
					var normalized = x / (double) width;
					var y = center + Math.sin(normalized * 8.0 + elapsedMillis * 0.00048 + strand * 1.8) * height * 0.075
							+ Math.sin(normalized * 23.0 - elapsedMillis * 0.00029 + strand) * height * 0.018;
					if (x == -40) filament.moveTo(x, y); else filament.lineTo(x, y);
				}
				var hue = (float) (0.54 + strand * 0.12 + elapsedMillis / 40_000.0 % 0.12);
				g.setColor(alpha(Color.getHSBColor(hue, 0.88f, 1f), 0.08f));
				g.setStroke(FILAMENT_GLOW_STROKE);
				g.draw(filament);
				g.setColor(alpha(Color.getHSBColor(hue, 0.72f, 1f), 0.24f));
				g.setStroke(FILAMENT_CORE_STROKE);
				g.draw(filament);
			}
		}

		private void rebuildLinks(List<Projection> projected, long elapsedMillis) {
			links.clear();
			for (var i = 0; i < projected.size(); i++) {
				var first = projected.get(i);
				var nearestIndices = new int[] { -1, -1, -1 };
				var nearestDistances = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
				for (var j = i + 1; j < projected.size(); j++) {
					var second = projected.get(j);
					var dx = first.node.x - second.node.x;
					var dy = first.node.y - second.node.y;
					var dz = first.depth - second.depth;
					var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (distance >= LINK_DISTANCE || Math.abs(dz) > 0.72) continue;
					for (var slot = 0; slot < nearestDistances.length; slot++) {
						if (distance >= nearestDistances[slot]) continue;
						for (var shift = nearestDistances.length - 1; shift > slot; shift--) {
							nearestDistances[shift] = nearestDistances[shift - 1];
							nearestIndices[shift] = nearestIndices[shift - 1];
						}
						nearestDistances[slot] = distance;
						nearestIndices[slot] = j;
						break;
					}
				}
				for (var index : nearestIndices) {
					if (index >= 0) links.add(new Link(i, index));
				}
			}
			lastLinkRefresh = elapsedMillis;
		}

		private void drawLinks(Graphics2D g, List<Projection> projected, long elapsedMillis) {
			for (var linkIndex = 0; linkIndex < links.size(); linkIndex++) {
				var link = links.get(linkIndex);
				var first = projected.get(link.first);
				var second = projected.get(link.second);
				var dx = first.node.x - second.node.x;
				var dy = first.node.y - second.node.y;
				var dz = first.depth - second.depth;
				var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				var strength = (float) Math.pow(1 - distance / LINK_DISTANCE, 1.7) * 0.62f;
				var pulse = (float) (0.72 + 0.28 * Math.sin(elapsedMillis * 0.0022 + linkIndex * 0.47));
				var color = Color.getHSBColor((float) (0.52 + (linkIndex % 9) * 0.035), 0.86f, 1f);
				drawGlowLine(g, first.x, first.y, second.x, second.y, color, strength * pulse);
			}
		}

		private void drawNodes(Graphics2D g, List<Projection> projected, long elapsedMillis) {
			for (var projection : projected) {
				var alpha = (float) Math.clamp(0.22 + (1.55 - projection.depth) * 0.52, 0.18, 0.9);
				var radius = projection.size * 3.8;
				g.setColor(alpha(projection.color, alpha * 0.10f));
				shape.setFrame(projection.x - radius, projection.y - radius, radius * 2, radius * 2);
				g.fill(shape);
				g.setColor(alpha(projection.color, alpha * 0.38f));
				radius = projection.size * 1.45;
				shape.setFrame(projection.x - radius, projection.y - radius, radius * 2, radius * 2);
				g.fill(shape);
				g.setColor(alpha(NODE_CORE_COLOR, alpha));
				radius = Math.max(1.1, projection.size * 0.38);
				shape.setFrame(projection.x - radius, projection.y - radius, radius * 2, radius * 2);
				g.fill(shape);
			}
		}

		private void drawVignette(Graphics2D g, int width, int height) {
			g.setPaint(vignettePaint);
			g.fillRect(0, 0, width, height);
			g.setColor(new Color(160, 225, 255, 12));
			for (var y = 0; y < height; y += 4) g.drawLine(0, y, width, y);
		}

		private void drawGlowLine(Graphics2D g, double x1, double y1, double x2, double y2,
				Color color, float strength) {
			line.setLine(x1, y1, x2, y2);
			g.setColor(alpha(color, strength * 0.055f));
			g.setStroke(LINK_GLOW_STROKE);
			g.draw(line);
			g.setColor(alpha(color, strength * 0.16f));
			g.setStroke(LINK_MID_STROKE);
			g.draw(line);
			g.setColor(alpha(color, strength * 0.72f));
			g.setStroke(LINK_CORE_STROKE);
			g.draw(line);
		}

		private static final class Node {
			private double x;
			private double y;
			private double z;
			private final double speed;
			private final double phase;

			private Node(double x, double y, double z, double speed, double phase) {
				this.x = x;
				this.y = y;
				this.z = z;
				this.speed = speed;
				this.phase = phase;
			}
		}

		private record Projection(Node node, double x, double y, double depth, double size, Color color) {}

		private record Link(int first, int second) {}
	}

	/** A deep-space field with coloured stars flying gently toward the viewer. */
	private static final class StarfieldEffect implements DesktopBackgroundEffect {

		private final java.util.Random random = new java.util.Random(0x5EED_1980L);
		private final List<Star> stars = new java.util.ArrayList<>();
		private long lastElapsed = -1;

		@Override
		public String id() {
			return "starfield";
		}

		@Override
		public String displayName() {
			return "Arcade Starfield";
		}

		@Override
		public String description() {
			return "A slow, colourful 80s arcade-style starfield.";
		}

		@Override
		public void reset() {
			stars.clear();
			lastElapsed = -1;
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) return;
			var g = (Graphics2D) graphics.create();
			try {
				g.setPaint(new GradientPaint(0, 0, new Color(3, 5, 24), width, height,
						new Color(22, 2, 42)));
				g.fillRect(0, 0, width, height);
				while (stars.size() < 125) stars.add(new Star(random.nextDouble() * 2 - 1,
						random.nextDouble() * 2 - 1, 0.05 + random.nextDouble() * 0.95,
						random.nextFloat()));
				var delta = lastElapsed < 0 ? 0.032 : Math.min(0.08, Math.max(0, (elapsedMillis - lastElapsed) / 1_000.0));
				lastElapsed = elapsedMillis;
				var cx = width / 2.0;
				var cy = height / 2.0;
				for (var star : stars) {
					star.z -= delta * 0.22;
					if (star.z <= 0.03) {
						star.x = random.nextDouble() * 2 - 1;
						star.y = random.nextDouble() * 2 - 1;
						star.z = 1;
					}
					var x = cx + star.x / star.z * width * 0.42;
					var y = cy + star.y / star.z * height * 0.42;
					if (x < -4 || x > width + 4 || y < -4 || y > height + 4) continue;
					var size = Math.max(1, (int) (3.5 * (1 - star.z)));
					var color = Color.getHSBColor((star.hue + elapsedMillis / 25_000f) % 1f, 0.72f, 1f);
					g.setColor(alpha(color, (float) Math.min(1, 0.2 + (1 - star.z) * 0.9)));
					g.fillOval((int) x - size, (int) y - size, size * 2 + 1, size * 2 + 1);
				}
			} finally {
				g.dispose();
			}
		}
	}

	private static final class Star {
		private double x;
		private double y;
		private double z;
		private final float hue;

		private Star(double x, double y, double z, float hue) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.hue = hue;
		}
	}

	private static Color alpha(Color color, float opacity) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(),
				Math.clamp((int) (opacity * 255), 0, 255));
	}
}
