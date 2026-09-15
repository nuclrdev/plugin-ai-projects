package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
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
		return List.of(new NoneEffect(), new NeonNetworkEffect(), new MatrixRainEffect(),
				new AetherworksEffect(), new HelixSequencerEffect(), new StarfieldEffect());
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

		/** The backdrop and the vignette never change; only what is between them moves. */
		private static final Scanlines SCANLINES = new Scanlines(new Color(160, 225, 255, 12), 4);

		/**
		 * Node tints quantised onto a wheel. The hue is a continuous function of index
		 * and time, so every node used to mean an HSB conversion and a {@link Color}
		 * allocation per frame; at this resolution a step is invisible and the wheel is
		 * built once.
		 */
		private static final Color[] NODE_HUES = nodeHues();

		private static Color[] nodeHues() {
			var wheel = new Color[512];
			for (var step = 0; step < wheel.length; step++) {
				wheel[step] = Color.getHSBColor(step / (float) wheel.length, 0.83f, 1f);
			}
			return wheel;
		}

		private final CachedLayer backdropLayer = new CachedLayer(true, 1);
		private final CachedLayer vignetteLayer = new CachedLayer(false, 1);
		private final java.util.Random random = new java.util.Random(0x80E0_1985L);
		private final List<Node> nodes = new java.util.ArrayList<>();
		private final List<Link> links = new java.util.ArrayList<>();
		private Projection[] projections = new Projection[0];
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
			backdropLayer.discard();
			vignetteLayer.discard();
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) {
				return;
			}
			var g = (Graphics2D) graphics.create();
			try {
				// RENDER_QUALITY buys gradient and image resampling quality, which is now
				// paid for once per size inside the cached layers rather than once a frame.
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				backdropLayer.paint(g, width, height, this::paintBackdrop);
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
				// The banding is part of the finish, so it is baked into that layer rather
				// than tiled over the top: a fill of the whole desktop cost 8ms a frame.
				vignetteLayer.paint(g, width, height, this::paintFinish);
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

		/**
		 * Project every node for this frame into a buffer that is reused.
		 *
		 * <p>A list of records plus a {@link Color} per node per frame was a few thousand
		 * short-lived objects a second, for a fixed set of nodes whose projected values
		 * are overwritten on the next frame anyway.
		 *
		 * @return the buffer; only the first {@code nodes.size()} entries are valid
		 */
		private Projection[] project(int width, int height, long elapsedMillis) {
			var angle = elapsedMillis * 0.000035;
			var cos = Math.cos(angle);
			var sin = Math.sin(angle);
			var focal = Math.min(width, height) * 0.68;
			var cx = width / 2.0;
			var cy = height / 2.0;
			if (projections.length < nodes.size()) {
				projections = new Projection[nodes.size()];
				for (var slot = 0; slot < projections.length; slot++) {
					projections[slot] = new Projection();
				}
			}
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
				var projection = projections[index];
				projection.node = node;
				projection.x = px;
				projection.y = py;
				projection.depth = z;
				projection.size = size;
				projection.color = NODE_HUES[Math.floorMod(
						Math.round(hue * NODE_HUES.length), NODE_HUES.length)];
			}
			return projections;
		}

		private void paintBackdrop(Graphics2D g, int width, int height) {
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

		private void rebuildLinks(Projection[] projected, long elapsedMillis) {
			links.clear();
			var count = nodes.size();
			for (var i = 0; i < count; i++) {
				var first = projected[i];
				var nearestIndices = new int[] { -1, -1, -1 };
				var nearestDistances = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
				for (var j = i + 1; j < count; j++) {
					var second = projected[j];
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

		private void drawLinks(Graphics2D g, Projection[] projected, long elapsedMillis) {
			for (var linkIndex = 0; linkIndex < links.size(); linkIndex++) {
				var link = links.get(linkIndex);
				var first = projected[link.first];
				var second = projected[link.second];
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

		private void drawNodes(Graphics2D g, Projection[] projected, long elapsedMillis) {
			for (var index = 0; index < nodes.size(); index++) {
				var projection = projected[index];
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

		private void paintFinish(Graphics2D g, int width, int height) {
			ensurePaints(width, height);
			g.setPaint(vignettePaint);
			g.fillRect(0, 0, width, height);
			SCANLINES.paint(g, width, height);
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

		/** Mutable on purpose: one instance per node, rewritten in place every frame. */
		private static final class Projection {
			private Node node;
			private double x;
			private double y;
			private double depth;
			private double size;
			private Color color;
		}

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

		/**
		 * One pattern per colour rotation. The banding shifts colour every 420ms and
		 * otherwise never changes, so three tiled fills stand in for a loop that drew a
		 * line every fourth row of the desktop on every frame.
		 */
		private static final Scanlines[] SCANLINES = createScanlines();

		private static Scanlines[] createScanlines() {
			var patterns = new Scanlines[SCANLINE_COLORS.length];
			for (var phase = 0; phase < patterns.length; phase++) {
				patterns[phase] = new Scanlines(SCANLINE_COLORS, 4, phase);
			}
			return patterns;
		}

		private final CachedLayer backdropLayer = new CachedLayer(true, 1);
		private final CachedLayer vignetteLayer = new CachedLayer(false, 1);

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
			backdropLayer.discard();
			vignetteLayer.discard();
		}

		@Override
		public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
			if (width <= 0 || height <= 0) return;
			var g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				backdropLayer.paint(g, width, height, this::paintBackdrop);
				ensureColumns(width, height);
				advance(elapsedMillis, height);
				drawDataBursts(g, width, height, elapsedMillis);
				drawColumns(g, height, elapsedMillis);
				drawFinish(g, width, height, elapsedMillis);
			} finally {
				g.dispose();
			}
		}

		private void paintBackdrop(Graphics2D g, int width, int height) {
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
			vignetteLayer.paint(g, width, height, this::paintVignette);
			g.setComposite(AlphaComposite.SrcOver);
			SCANLINES[(int) Math.floorMod(elapsedMillis / 420, SCANLINES.length)].paint(g, width, height);
		}

		private void paintVignette(Graphics2D g, int width, int height) {
			ensurePaints(width, height);
			g.setPaint(vignettePaint);
			g.fillRect(0, 0, width, height);
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
