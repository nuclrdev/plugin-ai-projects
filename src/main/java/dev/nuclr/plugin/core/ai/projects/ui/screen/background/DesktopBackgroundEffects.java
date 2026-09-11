package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Built-in background effect collection. Add future effects to this list only. */
public final class DesktopBackgroundEffects {

	/** Identifier for the empty background. */
	public static final String NONE = "none";

	private DesktopBackgroundEffects() {
	}

	/** A fresh set of effect instances for one desktop. */
	public static List<DesktopBackgroundEffect> builtIn() {
		return List.of(new NoneEffect(), new NeonNetworkEffect(), new MatrixRainEffect(),
				new AetherworksEffect(), new StarfieldEffect());
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

	/** A restrained cyberpunk code rain with depth, CRT texture and glitch pulses. */
	private static final class MatrixRainEffect implements DesktopBackgroundEffect {

		private static final String GLYPHS = "0123456789ABCDEF<>[]{}/*+-=#$%&@" +
				"\u30A2\u30AB\u30B5\u30BF\u30CA\u30CF\u30DE\u30E4\u30E9\u30EF\u30F3";
		private static final int COLUMN_SPACING = 19;
		private static final int MAX_FONT_SIZE = 20;
		private static final int TRAIL_LEVELS = 8;
		private static final long GLYPH_REFRESH_MILLIS = 135;
		private static final BasicStroke BURST_STROKE = new BasicStroke(1.0f);
		private static final Color HEAD_COLOR = new Color(226, 255, 241);
		private static final Color[] SCANLINE_COLORS = {
				new Color(100, 255, 191, 9), new Color(72, 214, 255, 7), new Color(196, 255, 123, 6)
		};
		private static final Color[][] TRAIL_COLORS = createTrailColors();
		private static final Font[] FONTS = createFonts();

		private final java.util.Random random = new java.util.Random(0xC0DE_1984L);
		private final List<GlyphColumn> columns = new java.util.ArrayList<>();
		private int layoutWidth;
		private int layoutHeight;
		private long lastElapsed = -1;
		private GradientPaint backgroundPaint;
		private RadialGradientPaint atmospherePaint;
		private RadialGradientPaint vignettePaint;
		private int cachedWidth;
		private int cachedHeight;

		@Override
		public String id() {
			return "matrix-rain";
		}

		@Override
		public String displayName() {
			return "Emerald Cipher";
		}

		@Override
		public String description() {
			return "Layered emerald code rain with depth, CRT texture and glitch pulses.";
		}

		@Override
		public void reset() {
			columns.clear();
			layoutWidth = 0;
			layoutHeight = 0;
			lastElapsed = -1;
			backgroundPaint = null;
			atmospherePaint = null;
			vignettePaint = null;
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) return;
			var g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				drawBackdrop(g, width, height);
				ensureColumns(width, height);
				advance(elapsedMillis, height);
				drawDataBursts(g, width, height, elapsedMillis);
				drawColumns(g, height, elapsedMillis);
				drawFinish(g, width, height, elapsedMillis);
			} finally {
				g.dispose();
			}
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
			backgroundPaint = new GradientPaint(0, 0, new Color(1, 8, 8), width, height,
					new Color(2, 22, 24));
			var radius = Math.max(width, height) * 0.72f;
			atmospherePaint = new RadialGradientPaint(width * 0.48f, height * 0.43f, radius,
					new float[] { 0f, 0.42f, 1f },
					new Color[] { new Color(0, 70, 49, 94), new Color(0, 43, 40, 38), new Color(0, 0, 0, 0) });
			var vignetteRadius = Math.max(width, height) * 0.78f;
			vignettePaint = new RadialGradientPaint(width / 2f, height / 2f, vignetteRadius,
					new float[] { 0.38f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 174) });
		}

		private void ensureColumns(int width, int height) {
			if (width == layoutWidth && height == layoutHeight && !columns.isEmpty()) return;
			layoutWidth = width;
			layoutHeight = height;
			columns.clear();
			var count = Math.max(1, width / COLUMN_SPACING + 2);
			for (var index = 0; index < count; index++) {
				var depth = 0.42 + random.nextDouble() * 0.58;
				var fontSize = Math.clamp(11 + (int) (depth * 9), 11, MAX_FONT_SIZE);
				var glyphHeight = fontSize + 3;
				var length = 11 + random.nextInt(19) + (depth > 0.82 ? 5 : 0);
				var glyphs = new char[length];
				fillGlyphs(glyphs);
				var y = -random.nextDouble() * height * 0.75 + random.nextDouble() * height;
				columns.add(new GlyphColumn(index * COLUMN_SPACING + random.nextDouble() * 5 - 2,
						y, depth, fontSize, glyphHeight, length, 52 + random.nextDouble() * 105,
						depth > 0.84 && random.nextDouble() > 0.38, random.nextInt(TRAIL_COLORS.length), glyphs));
			}
		}

		private void advance(long elapsedMillis, int height) {
			var delta = lastElapsed < 0 ? 0.032 : Math.min(0.08,
					Math.max(0, (elapsedMillis - lastElapsed) / 1_000.0));
			lastElapsed = elapsedMillis;
			var glyphTick = elapsedMillis / GLYPH_REFRESH_MILLIS;
			for (var column : columns) {
				column.y += delta * column.speed;
				if (column.y - column.length * column.glyphHeight > height + 24) {
					column.y = -20 - random.nextDouble() * height * 0.45;
					column.speed = 52 + random.nextDouble() * 105;
				}
				if (column.lastGlyphTick != glyphTick) {
					column.lastGlyphTick = glyphTick;
					var changes = column.hero ? 3 : 1;
					for (var change = 0; change < changes; change++) {
						column.glyphs[random.nextInt(column.glyphs.length)] = nextGlyph();
					}
				}
			}
		}

		private void drawColumns(Graphics2D g, int height, long elapsedMillis) {
			for (var column : columns) {
				var palette = TRAIL_COLORS[column.palette];
				g.setFont(FONTS[column.fontSize]);
				var headY = (int) column.y;
				var left = (int) column.x;
				for (var row = column.length - 1; row >= 0; row--) {
					var baseline = headY - row * column.glyphHeight;
					if (baseline < -column.glyphHeight || baseline >  height + column.glyphHeight) continue;
					var intensity = (int) Math.clamp((1.0 - row / (double) column.length) * (TRAIL_LEVELS - 1), 0,
							TRAIL_LEVELS - 1);
					g.setColor(palette[intensity]);
					g.drawChars(column.glyphs, row, 1, left, baseline);
				}

				if (headY >= -column.glyphHeight && headY <= height + column.glyphHeight) {
					var glowAlpha = column.hero ? 42 : 24;
					g.setColor(new Color(48, 255, 176, glowAlpha));
					g.fillOval(left - 5, headY - column.fontSize, column.fontSize + 6, column.fontSize + 6);
					g.setColor(HEAD_COLOR);
					g.drawChars(column.glyphs, 0, 1, left, headY);
					if (column.hero) {
						g.setColor(new Color(176, 255, 220, 80));
						g.drawLine(left - 2, headY + 2, left + column.fontSize, headY + 2);
					}
				}
			}
		}

		private void drawDataBursts(Graphics2D g, int width, int height, long elapsedMillis) {
			g.setStroke(BURST_STROKE);
			for (var burst = 0; burst < 5; burst++) {
				var phase = elapsedMillis * (0.00021 + burst * 0.000025) + burst * 2.4;
				var x = (int) ((Math.sin(phase) * 0.5 + 0.5) * width);
				var y = (int) ((Math.cos(phase * 1.7) * 0.5 + 0.5) * height);
				var length = 18 + (int) ((Math.sin(phase * 2.2) * 0.5 + 0.5) * 125);
				g.setColor(new Color(58, 255, 183, burst == 2 ? 24 : 12));
				g.drawLine(Math.max(0, x - length), y, Math.min(width, x + length), y);
				if (burst == 2) {
					g.setColor(new Color(197, 255, 138, 26));
					g.fillRect(Math.max(0, x - 2), Math.max(0, y - 1), 5, 3);
				}
			}
		}

		private void drawFinish(Graphics2D g, int width, int height, long elapsedMillis) {
			g.setPaint(vignettePaint);
			g.fillRect(0, 0, width, height);
			g.setComposite(AlphaComposite.SrcOver);
			for (var y = 0; y < height; y += 4) {
				g.setColor(SCANLINE_COLORS[(int) ((y / 4 + elapsedMillis / 420) % SCANLINE_COLORS.length)]);
				g.drawLine(0, y, width, y);
			}
		}

		private void fillGlyphs(char[] glyphs) {
			for (var index = 0; index < glyphs.length; index++) glyphs[index] = nextGlyph();
		}

		private char nextGlyph() {
			return GLYPHS.charAt(random.nextInt(GLYPHS.length()));
		}

		private static Font[] createFonts() {
			var fonts = new Font[MAX_FONT_SIZE + 1];
			for (var size = 0; size < fonts.length; size++) {
				fonts[size] = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(1, size));
			}
			return fonts;
		}

		private static Color[][] createTrailColors() {
			var hues = new Color[] { new Color(36, 230, 143), new Color(40, 220, 194), new Color(173, 239, 106) };
			var palettes = new Color[hues.length][TRAIL_LEVELS];
			for (var palette = 0; palette < hues.length; palette++) {
				for (var level = 0; level < TRAIL_LEVELS; level++) {
					var strength = 0.16 + level * 0.11;
					palettes[palette][level] = new Color(
							(int) (hues[palette].getRed() * strength),
							(int) (hues[palette].getGreen() * strength),
							(int) (hues[palette].getBlue() * strength),
							Math.clamp(18 + level * 27, 0, 255));
				}
			}
			return palettes;
		}

		private static final class GlyphColumn {
			private double x;
			private double y;
			private final double depth;
			private final int fontSize;
			private final int glyphHeight;
			private final int length;
			private double speed;
			private final boolean hero;
			private final int palette;
			private final char[] glyphs;
			private long lastGlyphTick = Long.MIN_VALUE;

			private GlyphColumn(double x, double y, double depth, int fontSize, int glyphHeight,
					int length, double speed, boolean hero, int palette, char[] glyphs) {
				this.x = x;
				this.y = y;
				this.depth = depth;
				this.fontSize = fontSize;
				this.glyphHeight = glyphHeight;
				this.length = length;
				this.speed = speed * (0.72 + depth * 0.65);
				this.hero = hero;
				this.palette = palette;
				this.glyphs = glyphs;
			}
		}
	}

/**
	* A demoscene reactor: a white-hot plasma core wrapped in counter-rotating
	* brass turbine impellers, segmented energy rings, tapered struts and
	* orbiting embers. The frame is finished with a genuine downsampled bloom
	* pass, film grain, scanlines and a heavy vignette so the piece sits
	* quietly behind floating windows instead of shouting over them.
	*/
	private static final class SteampunkEngineEffect implements DesktopBackgroundEffect {

		private static final double TAU = Math.PI * 2;
		private static final long BEAT_PERIOD = 2200L;
		private static final int BLOOM_SCALE = 4;
		private static final int BLOOM_BLUR_RADIUS = 4;
		private static final int BLOOM_PASSES = 2;
		private static final int EMBER_COUNT = 54;
		private static final double[] CASING_STARTS = { -8, 91, 174, 266 };
		private static final double[] CASING_EXTENTS = { 62, 54, 61, 57 };

		private static final Color INK = new Color(7, 9, 15);
		private static final Color INK_WARM = new Color(30, 17, 14);
		private static final Color BRASS_DARK = new Color(56, 36, 14);
		private static final Color BRASS_MID = new Color(112, 76, 30);
		private static final Color BRASS = new Color(188, 138, 62);
		private static final Color BRASS_HI = new Color(252, 228, 162);
		private static final Color COPPER_DARK = new Color(78, 32, 16);
		private static final Color COPPER = new Color(198, 98, 56);
		private static final Color COPPER_HI = new Color(236, 146, 86);
		private static final Color TEAL_DARK = new Color(10, 48, 52);
		private static final Color TEAL = new Color(64, 208, 198);
		private static final Color TEAL_HI = new Color(172, 255, 242);
		private static final Color ENERGY = new Color(140, 255, 230);
		private static final Color WHITE_HOT = new Color(240, 255, 252);
		private static final Color WARM_WHITE = new Color(255, 240, 206);
		private static final Color SCANLINE = new Color(255, 240, 210, 6);

		private static final BasicStroke LOCAL_FINE =
				new BasicStroke(0.006f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		private static final BasicStroke LOCAL_EDGE =
				new BasicStroke(0.015f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

		private static final LinearGradientPaint BRASS_BLADE = bladeGradient(
				BRASS_DARK, BRASS, BRASS_HI, BRASS, BRASS_DARK);
		private static final LinearGradientPaint COPPER_BLADE = bladeGradient(
				COPPER_DARK, COPPER, COPPER_HI, COPPER, COPPER_DARK);
		private static final LinearGradientPaint STRUT_PAINT = bladeGradient(
				BRASS_DARK, BRASS_MID, BRASS_HI, BRASS_MID, BRASS_DARK);
		private static final TexturePaint GRAIN = grainTexture();

		private final Random random = new Random(0x5EED_B4A5L);
		private final Ctx ctx = new Ctx();
		private final AffineTransform work = new AffineTransform();
		private final Path2D.Double blade = new Path2D.Double();
		private final Path2D.Double bladeEdge = new Path2D.Double();
		private final Path2D.Double strut = new Path2D.Double();
		private final Path2D.Double strutEdge = new Path2D.Double();
		private final Path2D.Double plasma = new Path2D.Double();
		private final Ellipse2D.Double oval = new Ellipse2D.Double();
		private final Arc2D.Double arcShape = new Arc2D.Double();
		private final Line2D.Double line = new Line2D.Double();
		private final List<Ember> embers = new ArrayList<>();

		private GradientPaint backdropPaint;
		private RadialGradientPaint haloPaint;
		private RadialGradientPaint atmospherePaint;
		private RadialGradientPaint vignettePaint;
		private RadialGradientPaint plasmaPaint;
		private RadialGradientPaint glowCorePaint;

		private BasicStroke casingStroke;
		private BasicStroke casingMidStroke;
		private BasicStroke casingHiStroke;
		private BasicStroke ringAStroke;
		private BasicStroke ringBStroke;
		private BasicStroke ringCStroke;
		private BasicStroke hairlineStroke;
		private BasicStroke plasmaGlowStroke;
		private BasicStroke plasmaCoreStroke;
		private BasicStroke conduitGlowStroke;
		private BasicStroke ringGlowStroke;

		private BufferedImage background;
		private BufferedImage bloom;
		private int[] bloomPixels;
		private int[] bloomScratch;
		private int bloomWidth;
		private int bloomHeight;
		private double bloomScaleX = 1;
		private double bloomScaleY = 1;

		private long lastElapsed = -1;
		private int cachedWidth;
		private int cachedHeight;

		@Override
		public String id() {
			return "steampunk-engine";
		}

		@Override
		public String displayName() {
			return "Aether Engine";
		}

		@Override
		public String description() {
			return "A cinematic clockwork tunnel with rushing rings, vapor, sparks and bloom.";
		}

		@Override
		public void reset() {
			embers.clear();
			lastElapsed = -1;
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) {
				return;
			}
			var g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
				ensureGeometry(width, height);
				beginFrame(ctx, width, height, elapsedMillis);
				ensureEmbers();
				advanceEmbers(elapsedMillis);

				drawBackdrop(g, ctx);
				drawMachinedField(g, ctx);
				drawReactor(g, ctx);
				drawBloom(g, ctx);
				drawEmbers(g, ctx);
				drawFinish(g, ctx);
			} finally {
				g.dispose();
			}
		}

		// ------------------------------------------------------------------
		// Frame state
		// ------------------------------------------------------------------

		private void beginFrame(Ctx c, int width, int height, long ms) {
			c.w = width;
			c.h = height;
			c.ms = ms;
			c.t = ms / 1000.0;
			c.cx = width * 0.66;
			c.cy = height * 0.48;
			c.unit = Math.min(width, height);
			c.R = c.unit * 0.31;
			c.aspect = 0.69 + Math.sin(c.t * 0.17) * 0.018;
			c.beat = beat(ms);
			c.energy = 0.40 + 0.60 * c.beat;
			c.outerSpin = c.t * 0.35;
			c.midSpin = -c.t * 0.60;
			c.innerSpin = c.t * 1.05;
		}

		/** A heartbeat: a sharp thump with a softer echo a beat later. */
		private static double beat(long ms) {
			var x = (ms % BEAT_PERIOD) / (double) BEAT_PERIOD;
			var primary = Math.exp(-x * 5.0);
			var echoX = (x + 0.42) % 1.0;
			var echo = 0.72 * Math.exp(-echoX * 11.0);
			return Math.clamp(Math.max(primary, echo), 0.0, 1.0);
		}

		private void ensureGeometry(int width, int height) {
			if (width == cachedWidth && height == cachedHeight && background != null && bloom != null) {
				return;
			}
			cachedWidth = width;
			cachedHeight = height;
			var cx = width * 0.66;
			var cy = height * 0.48;
			var unit = Math.min(width, height);
			var r = unit * 0.31;

			backdropPaint = new GradientPaint(0, 0, INK, width, height, INK_WARM);
			haloPaint = new RadialGradientPaint((float) cx, (float) cy, (float) (r * 2.2),
					new float[] { 0f, 0.35f, 0.7f, 1f },
					new Color[] { new Color(150, 86, 32, 120), new Color(96, 52, 40, 58),
							new Color(30, 40, 60, 22), new Color(0, 0, 0, 0) });
			atmospherePaint = new RadialGradientPaint((float) (width * 0.18), (float) (height * 0.88),
					(float) (Math.max(width, height) * 0.95),
					new float[] { 0f, 0.5f, 1f },
					new Color[] { new Color(10, 74, 84, 78), new Color(8, 40, 60, 32), new Color(0, 0, 0, 0) });
			vignettePaint = new RadialGradientPaint((float) cx, (float) cy,
					(float) (Math.max(width, height) * 0.8),
					new float[] { 0.32f, 1f }, new Color[] { new Color(0, 0, 0, 0), new Color(0, 0, 0, 208) });
			plasmaPaint = new RadialGradientPaint((float) cx, (float) cy, (float) (r * 0.56),
					new float[] { 0f, 0.20f, 0.46f, 0.74f, 1f },
					new Color[] { new Color(255, 246, 226, 180), new Color(255, 190, 108, 120),
							new Color(206, 96, 36, 58), new Color(120, 40, 20, 20), new Color(0, 0, 0, 0) });
			glowCorePaint = new RadialGradientPaint((float) cx, (float) cy, (float) (r * 0.42),
					new float[] { 0f, 0.30f, 0.65f, 1f },
					new Color[] { new Color(255, 244, 216, 205), new Color(255, 190, 105, 118),
							new Color(214, 104, 40, 42), new Color(0, 0, 0, 0) });

			casingStroke = new BasicStroke((float) (r * 0.105), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			casingMidStroke = new BasicStroke((float) (r * 0.064), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			casingHiStroke = new BasicStroke((float) (r * 0.020), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			ringAStroke = new BasicStroke((float) (r * 0.058), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			ringBStroke = new BasicStroke((float) (r * 0.032), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			ringCStroke = new BasicStroke((float) (r * 0.024), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			hairlineStroke = new BasicStroke(Math.max(1f, (float) (r * 0.007)));
			plasmaGlowStroke = new BasicStroke((float) (r * 0.055), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			plasmaCoreStroke = new BasicStroke((float) (r * 0.015), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			conduitGlowStroke = new BasicStroke((float) (r * 0.10), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			ringGlowStroke = new BasicStroke((float) (r * 0.045), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

			background = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			var bg = background.createGraphics();
			bg.setPaint(backdropPaint);
			bg.fillRect(0, 0, width, height);
			bg.setPaint(haloPaint);
			bg.fillRect(0, 0, width, height);
			bg.setPaint(atmospherePaint);
			bg.fillRect(0, 0, width, height);
			bg.dispose();

			bloomWidth = Math.max(1, width / BLOOM_SCALE);
			bloomHeight = Math.max(1, height / BLOOM_SCALE);
			bloom = new BufferedImage(bloomWidth, bloomHeight, BufferedImage.TYPE_INT_ARGB_PRE);
			bloomPixels = ((DataBufferInt) bloom.getRaster().getDataBuffer()).getData();
			if (bloomScratch == null || bloomScratch.length < bloomPixels.length) {
				bloomScratch = new int[bloomPixels.length];
			}
			bloomScaleX = width / (double) bloomWidth;
			bloomScaleY = height / (double) bloomHeight;
		}

		// ------------------------------------------------------------------
		// Backdrop, machined field and post finish
		// ------------------------------------------------------------------

		private void drawBackdrop(Graphics2D g, Ctx c) {
			g.drawImage(background, 0, 0, null);
		}

		private void drawMachinedField(Graphics2D g, Ctx c) {
			g.setStroke(hairlineStroke);
			for (var i = 0; i < 9; i++) {
				var radius = c.R * (0.55 + i * 0.135);
				g.setColor(alpha(BRASS, 0.050f - i * 0.003f));
				circle(g, c, radius);
			}
			for (var i = 0; i < 48; i++) {
				var angle = i * TAU / 48 + c.t * 0.03;
				var inner = c.R * 1.24;
				var outer = c.R * (1.30 + 0.10 * Math.sin(i * 1.7 + c.t * 0.2));
				g.setColor(alpha(BRASS, 0.045f));
				line.setLine(c.cx + Math.cos(angle) * inner, c.cy + Math.sin(angle) * inner * c.aspect,
						c.cx + Math.cos(angle) * outer, c.cy + Math.sin(angle) * outer * c.aspect);
				g.draw(line);
			}
			var y = c.h - 26;
			g.setColor(alpha(TEAL, 0.10f));
			g.drawLine((int) (c.w * 0.06), y, (int) (c.w * 0.94), y);
			for (var x = (int) (c.w * 0.06); x < c.w * 0.94; x += 12) {
				g.drawLine(x, y, x, y - (x % 60 == 0 ? 7 : 3));
			}
		}

		private void drawFinish(Graphics2D g, Ctx c) {
			g.setPaint(vignettePaint);
			g.fillRect(0, 0, c.w, c.h);
			g.setColor(SCANLINE);
			for (var y = 0; y < c.h; y += 3) {
				g.drawLine(0, y, c.w, y);
			}
			g.setPaint(GRAIN);
			g.fillRect(0, 0, c.w, c.h);
		}

		// ------------------------------------------------------------------
		// Reactor
		// ------------------------------------------------------------------

		private void drawReactor(Graphics2D g, Ctx c) {
			drawTunnelRails(g, c);
			drawTunnelRings(g, c);
			drawTunnelVapor(g, c);
			drawTunnelCasing(g, c);
			drawTunnelAperture(g, c);
			drawTunnelSparks(g, c);
			world(g);
		}

		private void drawTunnelRails(Graphics2D g, Ctx c) {
			for (var rail = 0; rail < 14; rail++) {
				var angle = rail * TAU / 14.0 + Math.sin(c.t * 0.16) * 0.08;
				var twist = 0.34 + Math.sin(c.t * 0.21 + rail * 1.3) * 0.07;
				var inner = c.R * 0.12;
				var outer = c.R * 1.72;
				plasma.reset();
				plasma.moveTo(c.cx + Math.cos(angle + twist) * inner,
						c.cy + Math.sin(angle + twist) * inner * c.aspect);
				plasma.curveTo(c.cx + Math.cos(angle + twist * 0.75) * c.R * 0.48,
						c.cy + Math.sin(angle + twist * 0.75) * c.R * 0.48 * c.aspect,
						c.cx + Math.cos(angle + twist * 0.32) * c.R * 1.08,
						c.cy + Math.sin(angle + twist * 0.32) * c.R * 1.08 * c.aspect,
						c.cx + Math.cos(angle) * outer,
						c.cy + Math.sin(angle) * outer * c.aspect);
				g.setStroke(conduitGlowStroke);
				g.setColor(alpha(rail % 4 == 0 ? TEAL : COPPER, 0.025f));
				g.draw(plasma);
				g.setStroke(ringCStroke);
				g.setColor(alpha(rail % 4 == 0 ? TEAL : BRASS_MID, rail % 4 == 0 ? 0.30f : 0.19f));
				g.draw(plasma);
				g.setStroke(hairlineStroke);
				g.setColor(alpha(rail % 4 == 0 ? TEAL_HI : BRASS_HI, 0.44f));
				g.draw(plasma);
			}
		}

		private void drawTunnelRings(Graphics2D g, Ctx c) {
			var baseX = c.cx;
			var baseY = c.cy;
			for (var ring = 0; ring < 11; ring++) {
				var depth = (ring / 11.0 + c.t * 0.050) % 1.0;
				var eased = depth * depth;
				var radius = c.R * (0.13 + eased * 1.62);
				c.cx = baseX + Math.sin(c.t * 0.24 + depth * 5.4) * c.R * 0.075 * (1 - depth);
				c.cy = baseY + Math.cos(c.t * 0.19 + depth * 4.2) * c.R * 0.045 * (1 - depth);
				var opacity = (float) (0.13 + depth * 0.66);
				var rotation = Math.toDegrees(c.t * (ring % 2 == 0 ? 0.20 : -0.14) + ring * 0.47);

				g.setStroke(depth > 0.72 ? ringAStroke : ringBStroke);
				g.setColor(alpha(INK, opacity * 0.78f));
				circle(g, c, radius);
				g.setStroke(depth > 0.72 ? ringBStroke : ringCStroke);
				for (var segment = 0; segment < 16; segment++) {
					var start = rotation + segment * 22.5 + depth * 37;
					var sweep = segment % 4 == 0 ? 14.5 : 8.5;
					var color = segment % 7 == 0 ? TEAL : segment % 3 == 0 ? COPPER : BRASS;
					g.setColor(alpha(color, opacity * (segment % 7 == 0 ? 0.95f : 0.68f)));
					drawArc(g, c, radius, start, sweep);
				}

				g.setStroke(hairlineStroke);
				for (var tooth = 0; tooth < 32; tooth++) {
					var angle = Math.toRadians(rotation + tooth * 11.25);
					var inner = radius * 0.965;
					var outer = radius * (1.015 + depth * 0.018);
					g.setColor(alpha(tooth % 8 == 0 ? TEAL_HI : BRASS_HI, opacity * 0.48f));
					line.setLine(c.cx + Math.cos(angle) * inner, c.cy + Math.sin(angle) * inner * c.aspect,
							c.cx + Math.cos(angle) * outer, c.cy + Math.sin(angle) * outer * c.aspect);
					g.draw(line);
				}
			}
			c.cx = baseX;
			c.cy = baseY;
		}

		private void drawTunnelVapor(Graphics2D g, Ctx c) {
			for (var plume = 0; plume < 5; plume++) {
				var side = plume % 2 == 0 ? -1 : 1;
				var startX = c.cx + side * c.R * (1.35 + plume * 0.07);
				var startY = c.cy + c.R * c.aspect * (0.65 - plume * 0.22);
				var drift = Math.sin(c.t * 0.31 + plume * 1.7) * c.R * 0.16;
				plasma.reset();
				plasma.moveTo(startX, startY);
				plasma.curveTo(startX - side * c.R * 0.24 + drift, startY - c.R * 0.18,
						startX + side * c.R * 0.16 + drift, startY - c.R * 0.42,
						startX - side * c.R * 0.10 + drift, startY - c.R * 0.72);
				g.setStroke(plasmaGlowStroke);
				g.setColor(alpha(plume % 3 == 0 ? TEAL_HI : WARM_WHITE, 0.022f));
				g.draw(plasma);
				g.setStroke(plasmaCoreStroke);
				g.setColor(alpha(plume % 3 == 0 ? TEAL : BRASS_HI, 0.075f));
				g.draw(plasma);
			}
		}

		private void drawTunnelCasing(Graphics2D g, Ctx c) {
			var radius = c.R * 1.78;
			for (var segment = 0; segment < CASING_STARTS.length; segment++) {
				var start = CASING_STARTS[segment] + Math.sin(c.t * 0.11 + segment) * 2.2;
				g.setStroke(casingStroke);
				g.setColor(alpha(INK, 0.94f));
				drawArc(g, c, radius, start - 1.5, CASING_EXTENTS[segment] + 3);
				g.setStroke(casingMidStroke);
				g.setColor(alpha(segment % 2 == 0 ? BRASS_DARK : COPPER_DARK, 0.96f));
				drawArc(g, c, radius, start, CASING_EXTENTS[segment]);
				g.setStroke(casingHiStroke);
				g.setColor(alpha(segment % 2 == 0 ? BRASS_HI : COPPER_HI, 0.58f));
				drawArc(g, c, radius, start + 2, CASING_EXTENTS[segment] * 0.62);
			}

			for (var joint = 0; joint < 12; joint++) {
				var angle = Math.toRadians(joint * 30.0 + 7);
				var x = c.cx + Math.cos(angle) * radius;
				var y = c.cy + Math.sin(angle) * radius * c.aspect;
				var size = c.R * 0.035;
				g.setColor(alpha(INK, 0.92f));
				fillOval(g, x - size * 1.5, y - size * 1.5, size * 3, size * 3);
				g.setColor(alpha(joint % 3 == 0 ? TEAL_HI : BRASS_HI, 0.74f));
				fillOval(g, x - size * 0.58, y - size * 0.58, size * 1.16, size * 1.16);
			}
		}

		private void drawTunnelAperture(Graphics2D g, Ctx c) {
			var baseX = c.cx;
			var baseY = c.cy;
			var baseR = c.R;
			c.cx += Math.sin(c.t * 0.24) * c.R * 0.075;
			c.cy += Math.cos(c.t * 0.19) * c.R * 0.045;
			c.R = baseR * 0.24;

			g.setStroke(ringGlowStroke);
			g.setColor(alpha(ENERGY, 0.13f + 0.16f * (float) c.energy));
			circle(g, c, c.R * 1.20);
			g.setStroke(ringBStroke);
			g.setColor(alpha(BRASS_DARK, 0.96f));
			circle(g, c, c.R * 1.05);
			drawImpeller(g, c, 11, 0.20, 0.92, 0.095, 0.060, 0.45,
					-c.innerSpin * 1.7, -0.9, COPPER_BLADE, COPPER_HI);
			g.setColor(alpha(INK, 0.94f));
			fillOval(g, c.cx - c.R * 0.34, c.cy - c.R * 0.34 * c.aspect,
					c.R * 0.68, c.R * 0.68 * c.aspect);
			g.setColor(alpha(WARM_WHITE, 0.82f));
			fillOval(g, c.cx - c.R * 0.12, c.cy - c.R * 0.12 * c.aspect,
					c.R * 0.24, c.R * 0.24 * c.aspect);
			g.setColor(alpha(WHITE_HOT, 0.96f));
			fillOval(g, c.cx - c.R * 0.045, c.cy - c.R * 0.045 * c.aspect,
					c.R * 0.09, c.R * 0.09 * c.aspect);

			c.cx = baseX;
			c.cy = baseY;
			c.R = baseR;
			world(g);
		}

		private void drawTunnelSparks(Graphics2D g, Ctx c) {
			g.setStroke(hairlineStroke);
			for (var spark = 0; spark < 38; spark++) {
				var seed = fract(Math.sin((spark + 4.2) * 81.731) * 43758.5453);
				var depth = fract(seed + c.t * (0.075 + spark % 5 * 0.009));
				var angle = spark * 2.39996 + Math.sin(c.t * 0.3 + spark) * 0.11;
				var distance = c.R * (0.12 + depth * depth * 1.82);
				var x = c.cx + Math.cos(angle) * distance;
				var y = c.cy + Math.sin(angle) * distance * c.aspect;
				var trail = c.R * (0.018 + depth * 0.075);
				var color = spark % 8 == 0 ? TEAL_HI : BRASS_HI;
				g.setColor(alpha(color, (float) (0.18 + depth * 0.72)));
				line.setLine(x - Math.cos(angle) * trail, y - Math.sin(angle) * trail * c.aspect, x, y);
				g.draw(line);
				var size = 0.8 + depth * 2.2;
				fillOval(g, x - size, y - size, size * 2, size * 2);
			}
		}

		private static double fract(double value) {
			return value - Math.floor(value);
		}

		/** The volumetric plasma bloom and its filaments, sitting behind the turbine. */
		private void drawPlasma(Graphics2D g, Ctx c) {
			var radius = c.R * 0.62;
			g.setPaint(plasmaPaint);
			fillOval(g, c.cx - radius, c.cy - radius * c.aspect, radius * 2, radius * 2 * c.aspect);
			drawPlasmaArcs(g, c);
		}

		private void drawCasing(Graphics2D g, Ctx c) {
			var radius = c.R * 1.20;
			g.setStroke(casingStroke);
			g.setColor(alpha(INK, 0.95f));
			circle(g, c, radius + c.R * 0.02);
			g.setColor(alpha(BRASS_DARK, 0.98f));
			circle(g, c, radius);
			g.setStroke(casingMidStroke);
			g.setColor(alpha(BRASS_MID, 0.95f));
			circle(g, c, radius);
			g.setStroke(casingHiStroke);
			g.setColor(alpha(BRASS_HI, 0.85f));
			drawArc(g, c, radius, Math.toDegrees(c.outerSpin) - 70, 140);
			g.setColor(alpha(COPPER, 0.55f));
			drawArc(g, c, radius, Math.toDegrees(c.outerSpin) + 120, 90);

			g.setStroke(ringAStroke);
			g.setColor(alpha(BRASS_DARK, 0.92f));
			circle(g, c, c.R * 1.10);
			g.setStroke(ringCStroke);
			g.setColor(alpha(COPPER, 0.50f));
			circle(g, c, c.R * 1.08);

			var bolts = 28;
			for (var i = 0; i < bolts; i++) {
				var angle = i * TAU / bolts;
				var bx = c.cx + Math.cos(angle) * radius;
				var by = c.cy + Math.sin(angle) * radius * c.aspect;
				var br = c.R * 0.026;
				g.setColor(alpha(INK, 0.85f));
				fillOval(g, bx - br, by - br, br * 2, br * 2);
				g.setColor(alpha(BRASS_HI, 0.85f));
				fillOval(g, bx - br * 0.5, by - br * 0.5, br, br);
			}
			g.setStroke(hairlineStroke);
			g.setColor(alpha(BRASS_HI, 0.30f));
			for (var i = 0; i < 56; i++) {
				var angle = i * TAU / 56;
				var inner = radius + c.R * 0.035;
				var outer = radius + c.R * 0.075;
				line.setLine(c.cx + Math.cos(angle) * inner, c.cy + Math.sin(angle) * inner * c.aspect,
						c.cx + Math.cos(angle) * outer, c.cy + Math.sin(angle) * outer * c.aspect);
				g.draw(line);
			}
		}

		private void drawRings(Graphics2D g, Ctx c) {
			// Copper segments, one direction.
			var start = Math.toDegrees(c.midSpin);
			g.setStroke(ringAStroke);
			g.setColor(alpha(COPPER_DARK, 0.95f));
			for (var i = 0; i < 3; i++) {
				drawArc(g, c, c.R * 1.08, start + i * 120.0, 78);
			}
			g.setStroke(ringCStroke);
			g.setColor(alpha(COPPER, 0.85f));
			for (var i = 0; i < 3; i++) {
				drawArc(g, c, c.R * 1.08, start + 4 + i * 120.0, 70);
			}
			g.setStroke(hairlineStroke);
			g.setColor(alpha(COPPER_HI, 0.75f));
			for (var i = 0; i < 3; i++) {
				drawArc(g, c, c.R * 1.08, start + 8 + i * 120.0, 62);
			}

			// Teal energy dashes, the other direction.
			var dashStart = Math.toDegrees(c.innerSpin);
			g.setStroke(ringBStroke);
			for (var i = 0; i < 48; i++) {
				g.setColor(alpha(TEAL, 0.26f + 0.34f * (float) c.energy));
				drawArc(g, c, c.R * 1.00, dashStart + i * 7.5, 3.4);
			}
			g.setStroke(hairlineStroke);
			g.setColor(alpha(TEAL_HI, 0.45f + 0.30f * (float) c.energy));
			for (var i = 0; i < 48; i++) {
				drawArc(g, c, c.R * 1.005, dashStart + i * 7.5, 2.6);
			}

			// Fine brass gear ring.
			var tickStart = Math.toDegrees(c.outerSpin * 1.6);
			g.setStroke(ringCStroke);
			g.setColor(alpha(BRASS_MID, 0.60f));
			for (var i = 0; i < 72; i++) {
				drawArc(g, c, c.R * 0.94, tickStart + i * 5.0, 2.6);
			}

			// A bright sweep that rides the energy pulse.
			g.setStroke(ringBStroke);
			g.setColor(alpha(TEAL_HI, 0.35f + 0.45f * (float) c.energy));
			drawArc(g, c, c.R * 1.04, Math.toDegrees(-c.innerSpin * 1.5), 44);
		}

		private void drawTurbine(Graphics2D g, Ctx c) {
			drawImpeller(g, c, 22, 0.50, 0.92, 0.078, 0.052, 0.34,
					c.outerSpin, 0.35, BRASS_BLADE, BRASS_HI);
			drawImpeller(g, c, 16, 0.32, 0.56, 0.062, 0.046, -0.24,
					c.midSpin, -0.60, COPPER_BLADE, COPPER_HI);
		}

		private void drawImpeller(Graphics2D g, Ctx c, int count, double rIn, double rOut,
				double wIn, double wOut, double sweep, double angle, double omega, Paint paint, Color edge) {
			buildBlade(rIn, rOut, wIn, wOut, sweep);
			var smear = omega * 0.10;
			for (var i = 0; i < count; i++) {
				var a = angle + i * TAU / count;
				if (Math.abs(smear) > 1e-4) {
					local(g, c, a - smear);
					g.setColor(alpha(edge, 0.10f));
					g.fill(blade);
				}
				local(g, c, a);
				g.setPaint(paint);
				g.fill(blade);
				g.setColor(alpha(INK, 0.88f));
				g.setStroke(LOCAL_EDGE);
				g.draw(blade);
				g.setColor(alpha(edge, 0.40f));
				g.setStroke(LOCAL_FINE);
				g.draw(bladeEdge);
			}
			world(g);
		}

		private void drawStruts(Graphics2D g, Ctx c) {
			buildStrut(0.30, 1.20, 0.070, 0.048);
			for (var i = 0; i < 4; i++) {
				var angle = Math.PI * 0.25 + i * TAU / 4;
				local(g, c, angle);
				g.setPaint(STRUT_PAINT);
				g.fill(strut);
				g.setColor(alpha(INK, 0.92f));
				g.setStroke(LOCAL_EDGE);
				g.draw(strut);
				g.setColor(alpha(BRASS_HI, 0.45f));
				g.setStroke(LOCAL_FINE);
				g.draw(strutEdge);
				g.setColor(alpha(TEAL, 0.30f + 0.40f * (float) c.energy));
				g.setStroke(LOCAL_FINE);
				line.setLine(0.42, 0, 1.12, 0);
				g.draw(line);
				g.setColor(alpha(BRASS_MID, 0.95f));
				fillOval(g, 1.14 - 0.030, -0.030, 0.060, 0.060);
				g.setColor(alpha(BRASS_HI, 0.75f));
				fillOval(g, 1.14 - 0.016, -0.016, 0.032, 0.032);
			}
			world(g);

			for (var i = 0; i < 4; i++) {
				var angle = Math.PI * 0.25 + i * TAU / 4;
				var frac = (c.t * 0.55 + i / 4.0) % 1.0;
				var radius = c.R * (0.34 + frac * 0.80);
				var x = c.cx + Math.cos(angle) * radius;
				var y = c.cy + Math.sin(angle) * radius * c.aspect;
				var s = c.R * (0.014 + 0.012 * Math.sin(frac * Math.PI));
				g.setColor(alpha(ENERGY, 0.25f));
				fillOval(g, x - s * 3, y - s * 3, s * 6, s * 6);
				g.setColor(alpha(WHITE_HOT, 0.95f));
				fillOval(g, x - s, y - s, s * 2, s * 2);
			}
		}

		private void drawCore(Graphics2D g, Ctx c) {
			// Dark aperture backing: lets the plasma read as a glowing ring behind the iris.
			var backing = c.R * 0.36;
			g.setColor(alpha(INK, 0.88f));
			fillOval(g, c.cx - backing, c.cy - backing * c.aspect, backing * 2, backing * 2 * c.aspect);

			var iris = 18;
			for (var i = 0; i < iris; i++) {
				local(g, c, c.innerSpin * 1.4 + i * TAU / iris);
				buildIrisBlade(0.15, 0.34);
				g.setPaint(i % 2 == 0 ? COPPER_BLADE : BRASS_BLADE);
				g.fill(blade);
				g.setColor(alpha(INK, 0.72f));
				g.setStroke(LOCAL_EDGE);
				g.draw(blade);
			}
			world(g);

			var hole = c.R * 0.15;
			g.setStroke(ringCStroke);
			g.setColor(alpha(BRASS_HI, 0.78f));
			circle(g, c, hole * 1.05);
			g.setStroke(hairlineStroke);
			g.setColor(alpha(TEAL_HI, 0.42f + 0.30f * (float) c.energy));
			circle(g, c, hole * 1.18);

			var hot = hole * (0.80 + 0.35 * c.beat);
			g.setColor(alpha(WARM_WHITE, 0.55f));
			fillOval(g, c.cx - hot, c.cy - hot * c.aspect, hot * 2, hot * 2 * c.aspect);
			g.setColor(alpha(WHITE_HOT, 0.95f));
			fillOval(g, c.cx - hot * 0.55, c.cy - hot * 0.55 * c.aspect,
					hot * 1.1, hot * 1.1 * c.aspect);
		}

		private void drawPlasmaArcs(Graphics2D g, Ctx c) {
			var arcs = 4;
			for (var a = 0; a < arcs; a++) {
				plasma.reset();
				var base = c.innerSpin * 1.6 + a * TAU / arcs;
				var segments = 10;
				for (var s = 0; s <= segments; s++) {
					var f = s / (double) segments;
					var radius = c.R * (0.30 + 0.58 * f);
					var angle = base + Math.sin(c.t * 1.3 + a * 1.9 + s * 0.55) * 0.10;
					var jitter = Math.sin(c.t * 7.0 + a * 2.4 + s * 1.8) * c.R * 0.012 * (0.4 + f);
					var x = c.cx + Math.cos(angle) * radius + jitter;
					var y = c.cy + Math.sin(angle) * radius * c.aspect + jitter * 0.55;
					if (s == 0) {
						plasma.moveTo(x, y);
					} else {
						plasma.lineTo(x, y);
					}
				}
				var flicker = 0.4 + 0.6 * Math.abs(Math.sin(c.t * 3.0 + a * 2.1));
				g.setStroke(plasmaGlowStroke);
				g.setColor(alpha(ENERGY, 0.20f * (float) (flicker * c.energy)));
				g.draw(plasma);
				g.setStroke(plasmaCoreStroke);
				g.setColor(alpha(WHITE_HOT, 0.50f * (float) flicker));
				g.draw(plasma);
			}
		}

		private void drawOrbits(Graphics2D g, Ctx c) {
			var count = 18;
			for (var i = 0; i < count; i++) {
				var orbit = c.R * (0.50 + (i % 5) * 0.10);
				var dir = (i % 2 == 0) ? 1 : -1;
				var speed = 0.30 + (i % 4) * 0.20;
				var a = dir * c.t * speed + i * 2.399;
				var x = c.cx + Math.cos(a) * orbit;
				var y = c.cy + Math.sin(a) * orbit * c.aspect;

				var px = x;
				var py = y;
				var tail = 10;
				g.setStroke(hairlineStroke);
				for (var k = 1; k <= tail; k++) {
					var ta = a - dir * k * 0.025;
					var tx = c.cx + Math.cos(ta) * orbit;
					var ty = c.cy + Math.sin(ta) * orbit * c.aspect;
					g.setColor(alpha(TEAL_HI, 0.22f * (1f - k / (float) tail)));
					line.setLine(px, py, tx, ty);
					g.draw(line);
					px = tx;
					py = ty;
				}
				g.setColor(alpha(TEAL_HI, 0.28f));
				fillOval(g, x - 4, y - 4, 8, 8);
				g.setColor(alpha(WHITE_HOT, 0.95f));
				fillOval(g, x - 1.4, y - 1.4, 2.8, 2.8);
			}
		}

		// ------------------------------------------------------------------
		// Bloom
		// ------------------------------------------------------------------

		private void drawBloom(Graphics2D g, Ctx c) {
			var gg = (Graphics2D) bloom.getGraphics();
			try {
				gg.setComposite(AlphaComposite.Clear);
				gg.fillRect(0, 0, bloomWidth, bloomHeight);
				gg.setComposite(AlphaComposite.SrcOver);
				gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				gg.scale(1.0 / bloomScaleX, 1.0 / bloomScaleY);
				drawReactorGlow(gg, c);
				drawEmberGlow(gg, c);
			} finally {
				gg.dispose();
			}
			blurBloom();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(bloom, 0, 0, c.w, c.h, null);
			g.setComposite(AlphaComposite.SrcOver.derive(0.30f));
			g.drawImage(bloom, 0, 0, c.w, c.h, null);
			g.setComposite(AlphaComposite.SrcOver);
		}

		private void drawReactorGlow(Graphics2D g, Ctx c) {
			var apertureX = c.cx + Math.sin(c.t * 0.24) * c.R * 0.075;
			var apertureY = c.cy + Math.cos(c.t * 0.19) * c.R * 0.045;
			var halo = c.R * 0.22;
			g.setPaint(glowCorePaint);
			fillOval(g, apertureX - halo, apertureY - halo * c.aspect, halo * 2, halo * 2 * c.aspect);

			g.setStroke(ringGlowStroke);
			for (var ring = 0; ring < 6; ring++) {
				var depth = (ring / 6.0 + c.t * 0.05) % 1.0;
				var radius = c.R * (0.16 + depth * depth * 1.55);
				g.setColor(alpha(ring % 3 == 0 ? ENERGY : COPPER,
						(float) ((0.035 + depth * 0.09) * c.energy)));
				circle(g, c, radius);
			}

			for (var spark = 0; spark < 14; spark++) {
				var seed = fract(Math.sin((spark + 8.1) * 53.73) * 11832.319);
				var depth = fract(seed + c.t * (0.08 + spark % 3 * 0.012));
				var angle = spark * 2.39996;
				var distance = c.R * (0.15 + depth * depth * 1.65);
				var x = c.cx + Math.cos(angle) * distance;
				var y = c.cy + Math.sin(angle) * distance * c.aspect;
				var s = c.R * (0.018 + depth * 0.025);
				g.setColor(alpha(spark % 5 == 0 ? TEAL_HI : BRASS_HI, 0.40f));
				fillOval(g, x - s, y - s, s * 2, s * 2);
			}
		}

		private void drawEmberGlow(Graphics2D g, Ctx c) {
			for (var e : embers) {
				var fade = (float) Math.clamp(1 - e.life / e.maxLife, 0, 1);
				var s = e.size * 2.2;
				g.setColor(alpha(e.heat > 0.5 ? TEAL_HI : BRASS_HI, fade * 0.35f));
				fillOval(g, e.x - s, e.y - s, s * 2, s * 2);
			}
		}

		private void blurBloom() {
			var w = bloomWidth;
			var h = bloomHeight;
			var r = BLOOM_BLUR_RADIUS;
			if (w < 2 || h < 2 || r < 1) {
				return;
			}
			for (var pass = 0; pass < BLOOM_PASSES; pass++) {
				blurHorizontal(bloomPixels, bloomScratch, w, h, r);
				blurVertical(bloomScratch, bloomPixels, w, h, r);
			}
		}

		private static void blurHorizontal(int[] src, int[] dst, int w, int h, int r) {
			var span = r * 2 + 1;
			for (var y = 0; y < h; y++) {
				var base = y * w;
				var a = 0;
				var rr = 0;
				var gg = 0;
				var bb = 0;
				for (var k = -r; k <= r; k++) {
					var c = src[base + clampIndex(k, w)];
					a += c >>> 24;
					rr += (c >> 16) & 0xFF;
					gg += (c >> 8) & 0xFF;
					bb += c & 0xFF;
				}
				for (var x = 0; x < w; x++) {
					dst[base + x] = ((a / span) << 24) | ((rr / span) << 16) | ((gg / span) << 8) | (bb / span);
					var add = src[base + clampIndex(x + r + 1, w)];
					var rem = src[base + clampIndex(x - r, w)];
					a += (add >>> 24) - (rem >>> 24);
					rr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
					gg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
					bb += (add & 0xFF) - (rem & 0xFF);
				}
			}
		}

		private static void blurVertical(int[] src, int[] dst, int w, int h, int r) {
			var span = r * 2 + 1;
			for (var x = 0; x < w; x++) {
				var a = 0;
				var rr = 0;
				var gg = 0;
				var bb = 0;
				for (var k = -r; k <= r; k++) {
					var c = src[clampIndex(k, h) * w + x];
					a += c >>> 24;
					rr += (c >> 16) & 0xFF;
					gg += (c >> 8) & 0xFF;
					bb += c & 0xFF;
				}
				for (var y = 0; y < h; y++) {
					dst[y * w + x] = ((a / span) << 24) | ((rr / span) << 16) | ((gg / span) << 8) | (bb / span);
					var add = src[clampIndex(y + r + 1, h) * w + x];
					var rem = src[clampIndex(y - r, h) * w + x];
					a += (add >>> 24) - (rem >>> 24);
					rr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
					gg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
					bb += (add & 0xFF) - (rem & 0xFF);
				}
			}
		}

		private static int clampIndex(int index, int size) {
			return index < 0 ? 0 : (index >= size ? size - 1 : index);
		}

		// ------------------------------------------------------------------
		// Embers
		// ------------------------------------------------------------------

		private void ensureEmbers() {
			while (embers.size() < EMBER_COUNT) {
				var ember = new Ember();
				spawnEmber(ember, true);
				embers.add(ember);
			}
		}

		private void spawnEmber(Ember ember, boolean initial) {
			ember.x = random.nextDouble() * ctx.w;
			ember.y = initial ? random.nextDouble() * ctx.h : ctx.h + random.nextDouble() * 40;
			ember.vx = (random.nextDouble() - 0.5) * 6;
			ember.vy = -(12 + random.nextDouble() * 30);
			ember.life = 0;
			ember.maxLife = 5 + random.nextDouble() * 7;
			ember.size = 0.6 + random.nextDouble() * 1.8;
			ember.heat = random.nextDouble();
		}

		private void advanceEmbers(long elapsedMillis) {
			var delta = lastElapsed < 0 ? 0.032
					: Math.min(0.08, Math.max(0, (elapsedMillis - lastElapsed) / 1000.0));
			lastElapsed = elapsedMillis;
			for (var ember : embers) {
				ember.life += delta;
				ember.x += (ember.vx + Math.sin(ctx.t * 0.8 + ember.heat * 6) * 8) * delta;
				ember.y += ember.vy * delta;
				if (ember.life > ember.maxLife || ember.y < -20) {
					spawnEmber(ember, false);
				}
			}
		}

		private void drawEmbers(Graphics2D g, Ctx c) {
			for (var ember : embers) {
				var fade = (float) Math.clamp(1 - ember.life / ember.maxLife, 0, 1);
				var color = ember.heat > 0.5 ? TEAL_HI : BRASS_HI;
				g.setColor(alpha(color, fade * 0.30f));
				fillOval(g, ember.x - ember.size * 3, ember.y - ember.size * 3,
						ember.size * 6, ember.size * 6);
				g.setColor(alpha(WHITE_HOT, fade * 0.9f));
				fillOval(g, ember.x - ember.size * 0.6, ember.y - ember.size * 0.6,
						ember.size * 1.2, ember.size * 1.2);
			}
		}

		// ------------------------------------------------------------------
		// Geometry helpers
		// ------------------------------------------------------------------

		private void buildBlade(double rIn, double rOut, double wIn, double wOut, double sweep) {
			var midR = (rIn + rOut) * 0.5;
			var rootLead = -wIn / rIn;
			var rootTrail = wIn / rIn;
			var tipLead = sweep - wOut / rOut;
			var tipTrail = sweep + wOut / rOut;
			var midLead = sweep * 0.5 - (wIn + wOut) * 0.5 / midR;
			var midTrail = sweep * 0.5 + (wIn + wOut) * 0.5 / midR;

			blade.reset();
			blade.moveTo(px(rIn, rootLead), py(rIn, rootLead));
			blade.quadTo(px(midR, midLead), py(midR, midLead), px(rOut, tipLead), py(rOut, tipLead));
			blade.quadTo(px(rOut, sweep), py(rOut, sweep), px(rOut, tipTrail), py(rOut, tipTrail));
			blade.quadTo(px(midR, midTrail), py(midR, midTrail), px(rIn, rootTrail), py(rIn, rootTrail));
			blade.quadTo(px(rIn * 0.9, 0), py(rIn * 0.9, 0), px(rIn, rootLead), py(rIn, rootLead));
			blade.closePath();

			bladeEdge.reset();
			bladeEdge.moveTo(px(rIn, rootLead), py(rIn, rootLead));
			bladeEdge.quadTo(px(midR, midLead), py(midR, midLead), px(rOut, tipLead), py(rOut, tipLead));
		}

		private static double px(double radius, double angle) {
			return Math.cos(angle) * radius;
		}

		private static double py(double radius, double angle) {
			return Math.sin(angle) * radius;
		}

		private void buildIrisBlade(double rIn, double rOut) {
			blade.reset();
			var width = (rOut - rIn) * 0.55;
			blade.moveTo(rIn, -width * 0.7);
			blade.quadTo((rIn + rOut) * 0.5, -width * 1.25, rOut, -width * 0.35);
			blade.lineTo(rOut, width * 0.35);
			blade.quadTo((rIn + rOut) * 0.5, width * 0.45, rIn, width * 0.7);
			blade.closePath();
		}

		private void buildStrut(double rIn, double rOut, double wIn, double wOut) {
			strut.reset();
			strut.moveTo(rIn, -wIn);
			strut.lineTo(rOut, -wOut);
			strut.lineTo(rOut, wOut);
			strut.lineTo(rIn, wIn);
			strut.closePath();

			strutEdge.reset();
			strutEdge.moveTo(rIn, -wIn);
			strutEdge.lineTo(rOut, -wOut);
		}

		private void local(Graphics2D g, Ctx c, double angle) {
			work.setToTranslation(c.cx, c.cy);
			work.scale(c.R, c.R * c.aspect);
			work.rotate(angle);
			g.setTransform(work);
		}

		private void world(Graphics2D g) {
			work.setToIdentity();
			g.setTransform(work);
		}

		private void fillOval(Graphics2D g, double x, double y, double w, double h) {
			oval.setFrame(x, y, w, h);
			g.fill(oval);
		}

		private void circle(Graphics2D g, Ctx c, double radius) {
			oval.setFrame(c.cx - radius, c.cy - radius * c.aspect, radius * 2, radius * 2 * c.aspect);
			g.draw(oval);
		}

		private void drawArc(Graphics2D g, Ctx c, double radius, double start, double extent) {
			arcShape.setArc(c.cx - radius, c.cy - radius * c.aspect, radius * 2,
					radius * 2 * c.aspect, start, extent, Arc2D.OPEN);
			g.draw(arcShape);
		}

		private static LinearGradientPaint bladeGradient(Color a, Color b, Color c, Color d, Color e) {
			return new LinearGradientPaint(new Point2D.Double(0, -0.06), new Point2D.Double(0, 0.06),
					new float[] { 0f, 0.44f, 0.50f, 0.56f, 1f }, new Color[] { a, b, c, d, e });
		}

		private static TexturePaint grainTexture() {
			var size = 96;
			var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
			var noise = new Random(0x9E3779B9L);
			for (var i = 0; i < pixels.length; i++) {
				var value = noise.nextInt(256);
				var a = noise.nextInt(26);
				pixels[i] = (a << 24) | (value << 16) | (value << 8) | value;
			}
			return new TexturePaint(image, new Rectangle2D.Double(0, 0, size, size));
		}

		private static final class Ctx {
			int w;
			int h;
			long ms;
			double t;
			double cx;
			double cy;
			double unit;
			double R;
			double aspect;
			double beat;
			double energy;
			double outerSpin;
			double midSpin;
			double innerSpin;
		}

		private static final class Ember {
			double x;
			double y;
			double vx;
			double vy;
			double life;
			double maxLife;
			double size;
			double heat;
		}
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
