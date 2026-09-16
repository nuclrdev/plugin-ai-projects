package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Procedural nocturne: flooded shrine, refracted rain, ember-lit incense and candle bloom. */
final class RainySanctuaryEffect implements DesktopBackgroundEffect {

	private static final int W = 960;
	private static final int H = 600;
	private static final int WATER = 354;
	private static final Color CLEAR = new Color(0, 0, 0, 0);
	private final CachedLayer altar = new CachedLayer(false, 1);
	private final CachedLayer finish = new CachedLayer(false, 1);
	private final Path2D.Double path = new Path2D.Double();
	private BufferedImage landscape;
	private BufferedImage water;
	private BufferedImage smoke;
	private BufferedImage halo;
	private BufferedImage plume;
	private BufferedImage wax;
	private BufferedImage bronze;
	private int[] plumePixels;
	private final double[] vaporNoise = new double[128 * 128];
	private final double[] waterSines = new double[W];
	private final double[] waterCosines = new double[W];
	private int[] scenePixels;
	private int[] waterPixels;

	@Override public String id() { return "rainy-sanctuary"; }
	@Override public String displayName() { return "Rainy Sanctuary"; }
	@Override public String description() {
		return "Midnight at a flooded shrine: luminous rain, rippling reflections, candle bloom and ember-lit incense.";
	}
	@Override public int frameDelayMillis() { return 40; }
	@Override public void reset() {
		landscape = null;
		water = null;
		scenePixels = null;
		waterPixels = null;
		altar.discard();
		finish.discard();
	}

	@Override
	public void paint(Graphics2D graphics, int width, int height, long elapsedMillis) {
		if (width <= 0 || height <= 0) return;
		ensureScene();
		var g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			// A fixed software framebuffer bounds the CPU work even on a 4K desktop.
			g.scale(width / (double) W, height / (double) H);
			var time = Math.max(0, elapsedMillis) / 1000.0;
			g.drawImage(landscape, 0, 0, null);
			paintWater(g, time);
			paintLightShafts(g, time);
			paintMist(g, time);
			paintRain(g, time, false);
			paintLensDrops(g, time);
			g.scale(W / (double) width, H / (double) height);
			altar.paint(g, width, height, this::paintAltar);
			g.scale(width / (double) W, height / (double) H);
			paintCandle(g, 161, 513, 30, 76, time);
			paintCandle(g, 214, 531, 23, 53, time + 2);
			paintCandle(g, 119, 524, 19, 43, time + 4);
			paintIncensePlume(g, time);
			for (var stick = 0; stick < 3; stick++) {
				var x = 742 + stick * 17;
				var y = 414 + stick * 7;
				glow(g, x, y, 16, new Color(255, 84, 26, 65));
				g.setColor(new Color(255, 198, 104));
				g.fill(new Ellipse2D.Double(x - 1, y - 2, 2, 3));
			}
			paintRain(g, time, true);
			g.scale(W / (double) width, H / (double) height);
			finish.paint(g, width, height, this::paintFinish);
		} finally { g.dispose(); }
	}

	private void ensureScene() {
		if (landscape != null) return;
		landscape = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		scenePixels = ((DataBufferInt) landscape.getRaster().getDataBuffer()).getData();
		var ridge = new double[4][W];
		for (var layer = 0; layer < 4; layer++) {
			for (var x = 0; x < W; x++) {
				ridge[layer][x] = 225 + layer * 29 + Math.sin(x * .005 + layer * 1.7) * 48
						+ (fbm(x * .009, layer * 11.7) - .5) * (95 - layer * 12);
			}
		}
		for (var y = 0; y < H; y++) {
			for (var x = 0; x < W; x++) {
				var cloud = fbm(x * .006, y * .012);
				var moon = Math.exp(-square((x - 370) / 170.0) - square((y - 133) / 135.0));
				var veil = .42 + cloud * .85;
				double r = 6 + moon * 52 * veil + cloud * 6;
				double green = 16 + moon * 87 * veil + cloud * 12;
				double b = 25 + moon * 93 * veil + cloud * 16;
				for (var layer = 0; layer < 4; layer++) {
					if (y > ridge[layer][x]) {
						var fog = Math.exp(-square((y - 328) / 44.0));
						r = 7 + (3 - layer) * 4 + fog * 11;
						green = 18 + (3 - layer) * 7 + fog * 22;
						b = 24 + (3 - layer) * 8 + fog * 23;
					}
				}
				var grain = (hash(x, y) - .5) * 3;
				scenePixels[y * W + x] = rgb(r + grain, green + grain, b + grain);
			}
		}
		var g = landscape.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// The moon is partially veiled; the wide cyan scattering is in the pixel field.
			glow(g, 370, 128, 44, new Color(176, 223, 222, 45));
			for (var my = -13; my <= 13; my++) for (var mx = -13; mx <= 13; mx++) {
				if (mx * mx + my * my > 169) continue;
				var texture = fbm(mx * .3 + 8, my * .3 + 8);
				g.setColor(new Color(172, 212, 214, (int) (50 + texture * 70)));
				g.fillRect(370 + mx, 128 + my, 1, 1);
			}
			for (var i = 0; i < 70; i++) {
				var x = (int) (hash(i, 3) * W);
				var y = 292 + hash(i, 4) * 38;
				var h = 20 + hash(i, 8) * 50;
				g.setColor(new Color(9, 27, 30, 115));
				g.setStroke(new BasicStroke(1));
				g.drawLine(x, (int) y, x, (int) (y - h));
				for (var j = 0; j < 6; j++) {
					var yy = y - h + j * h / 7;
					var spread = 3 + j * 2;
					g.drawLine(x - spread, (int) yy + 5, x, (int) yy);
					g.drawLine(x + spread, (int) yy + 5, x, (int) yy);
				}
			}
			paintGate(g);
			paintMaple(g);
			// A procession of small lanterns supplies warm, reflected points in the depth.
			for (var i = 0; i < 9; i++) {
				var x = 460 + i * 39;
				var y = 342 - Math.sin(i * .35) * 7;
				glow(g, x, y - 11, 26, new Color(255, 119, 45, 70));
				g.setColor(new Color(8, 17, 20));
				g.fillRect(x - 3, (int) y - 13, 6, 19);
				g.setColor(new Color(243, 165, 83));
				g.fillRect(x - 2, (int) y - 14, 4, 5);
			}
		} finally { g.dispose(); }
		water = new BufferedImage(W, H - WATER, BufferedImage.TYPE_INT_RGB);
		waterPixels = ((DataBufferInt) water.getRaster().getDataBuffer()).getData();
		if (smoke == null) smoke = cloudSprite();
		if (halo == null) halo = haloSprite();
		if (wax == null) wax = waxTexture();
		if (bronze == null) bronze = bowlTexture();
		if (plume == null) {
			plume = new BufferedImage(128, 200, BufferedImage.TYPE_INT_ARGB);
			plumePixels = ((DataBufferInt) plume.getRaster().getDataBuffer()).getData();
			for (var y = 0; y < 128; y++) for (var x = 0; x < 128; x++) {
				vaporNoise[y * 128 + x] = fbm(x * .11, y * .11);
			}
		}
	}

	private void paintMaple(Graphics2D g) {
		// Foreground maple branches: a deliberately asymmetric canopy above the lens.
		g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(4, 11, 16));
		path.reset(); path.moveTo(-12, 110);
		path.curveTo(58, 116, 91, 53, 185, 42);
		path.curveTo(232, 39, 249, 9, 291, -5); g.draw(path);
		for (var branch = 0; branch < 9; branch++) {
			var bx = 26 + branch * 26;
			var by = 94 - branch * 11;
			var ex = bx + 35 + hash(branch, 81) * 62;
			var ey = by + 12 + hash(branch, 82) * 44;
			g.setColor(new Color(4, 12, 17));
			g.setStroke(new BasicStroke((float) (3.5 - branch * .25)));
			path.reset(); path.moveTo(bx, by); path.quadTo(ex - 24, ey - 4, ex, ey); g.draw(path);
			for (var leaf = 0; leaf < 16; leaf++) {
				var seed = branch * 17 + leaf;
				var lx = bx + (ex - bx) * hash(seed, 83) + (hash(seed, 86) - .5) * 48;
				var ly = by + (ey - by) * hash(seed, 84) + hash(seed, 85) * 32;
				var size = 4 + hash(seed, 87) * 8;
				var rotation = hash(seed, 88) * 6.28;
				path.reset();
				for (var point = 0; point < 14; point++) {
					var angle = rotation + point * Math.PI / 7;
					var radius = point % 2 == 0 ? size * (.65 + .35 * Math.sin(point * .31)) : size * .34;
					var px = lx + Math.cos(angle) * radius;
					var py = ly + Math.sin(angle) * radius * .7;
					if (point == 0) path.moveTo(px, py); else path.lineTo(px, py);
				}
				path.closePath();
				var light = hash(seed, 89);
				g.setColor(new Color((int) (25 + light * 70), (int) (23 + light * 20), (int) (24 + light * 10), 225));
				g.fill(path);
				g.setColor(new Color(149, 109, 74, 60));
				g.setStroke(new BasicStroke(.5f));
				g.draw(new java.awt.geom.Line2D.Double(lx, ly, lx + Math.cos(rotation) * size, ly + Math.sin(rotation) * size * .7));
			}
		}
	}

	private void paintGate(Graphics2D g) {
		// A torii in three-quarter lighting, with wet vermilion edges and a curved cap.
		g.setColor(new Color(10, 20, 23));
		g.fillRect(615, 206, 15, 146);
		g.fillRect(753, 206, 15, 146);
		g.setPaint(new GradientPaint(613, 0, new Color(119, 58, 42), 632, 0, new Color(18, 26, 28)));
		g.fillRect(613, 203, 17, 149);
		g.setPaint(new GradientPaint(752, 0, new Color(102, 48, 37), 770, 0, new Color(15, 22, 25)));
		g.fillRect(752, 203, 17, 149);
		g.setColor(new Color(55, 36, 31));
		g.fillRect(594, 232, 195, 11);
		g.setColor(new Color(139, 75, 50, 155));
		g.fillRect(594, 231, 195, 2);
		path.reset();
		path.moveTo(573, 192);
		path.curveTo(630, 209, 748, 210, 810, 190);
		path.lineTo(802, 205);
		path.curveTo(735, 220, 638, 218, 581, 207);
		path.closePath();
		g.setColor(new Color(9, 17, 21));
		g.fill(path);
		g.setStroke(new BasicStroke(2));
		g.setColor(new Color(100, 95, 78));
		path.reset();
		path.moveTo(574, 192);
		path.curveTo(630, 209, 748, 210, 809, 191);
		g.draw(path);
		g.setColor(new Color(39, 32, 28));
		g.fillRect(682, 213, 16, 36);
		g.setColor(new Color(164, 130, 72, 140));
		g.drawRect(685, 217, 10, 25);
		g.setStroke(new BasicStroke(1));
		path.reset();
		path.moveTo(630, 257);
		path.quadTo(690, 275, 752, 257);
		g.setColor(new Color(126, 115, 86, 145));
		g.draw(path);
		for (var x = 648; x <= 735; x += 22) {
			g.setColor(new Color(171, 181, 165, 150));
			path.reset();
			path.moveTo(x, 264); path.lineTo(x - 3, 272); path.lineTo(x + 1, 273);
			path.lineTo(x - 2, 280); path.lineTo(x + 5, 270); path.lineTo(x + 1, 269);
			g.fill(path);
		}
	}

	private void paintWater(Graphics2D g, double time) {
		for (var x = 0; x < W; x++) {
			waterSines[x] = Math.sin(x * .035 + time);
			waterCosines[x] = Math.cos(x * .035 + time);
		}
		for (var y = 0; y < H - WATER; y++) {
			var depth = y / (double) (H - WATER);
			var reflectedY = Math.max(0, WATER - 1 - (int) (y * .83));
			var shift = Math.sin(y * .13 - time * 1.8) * (1 + depth * 5)
					+ Math.sin(y * .47 + time * 2.1) * depth * 3;
			var band = .46 + Math.sin(y * .8 + Math.sin(y * .13 - time) * 2) * .09;
			var rowSin = Math.sin(y * .1) * depth * 3;
			var rowCos = Math.cos(y * .1) * depth * 3;
			for (var x = 0; x < W; x++) {
				var sx = Math.clamp((int) (x + shift + waterSines[x] * rowCos + waterCosines[x] * rowSin), 0, W - 1);
				var source = scenePixels[reflectedY * W + sx];
				waterPixels[y * W + x] = rgb(((source >> 16) & 255) * band + 3,
						((source >> 8) & 255) * band + 7, (source & 255) * band + 10);
			}
		}
		g.drawImage(water, 0, WATER, null);
		g.setStroke(new BasicStroke(.6f));
		for (var i = 0; i < 85; i++) {
			var phase = (time * (.5 + hash(i, 9) * .5) + hash(i, 2)) % 1;
			var y = 364 + hash(i, 6) * 175;
			var radius = (2 + phase * 20) * (y - 345) / 180;
			g.setColor(new Color(119, 182, 182, (int) ((1 - phase) * 50)));
			g.draw(new Ellipse2D.Double(hash(i, 7) * W - radius, y, radius * 2, radius * .24));
		}
	}

	private void paintMist(Graphics2D g, double time) {
		for (var i = 0; i < 16; i++) {
			var x = (i * 93 + time * (2 + i % 3)) % 1200 - 120;
			g.setComposite(AlphaComposite.SrcOver.derive(.07f));
			g.drawImage(smoke, (int) x, 268 + i % 4 * 15, 230, 65, null);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	private void paintLightShafts(Graphics2D g, double time) {
		var clip = g.getClip();
		g.clipRect(0, 0, W, WATER);
		for (var i = 0; i < 7; i++) {
			var angle = -.8 + i * .24 + Math.sin(time * .12 + i) * .035;
			var endX = 370 + Math.sin(angle) * 470;
			var endY = 128 + Math.cos(angle) * 470;
			path.reset(); path.moveTo(367, 130);
			path.lineTo(endX - 28, endY); path.lineTo(endX + 28, endY); path.closePath();
			g.setPaint(new GradientPaint(370, 160, new Color(111, 176, 187, 0),
					(float) endX, (float) endY, new Color(111, 176, 187, 5)));
			g.fill(path);
		}
		g.setClip(clip);
	}

	private void paintRain(Graphics2D g, double time, boolean near) {
		var count = near ? 34 : 470;
		for (var i = 0; i < count; i++) {
			var seed = i + (near ? 900 : 0);
			var depth = hash(seed, 5);
			var speed = near ? 390 + depth * 210 : 115 + depth * 260;
			var y = (hash(seed, 11) * 700 + time * speed) % 700 - 50;
			var x = (hash(seed, 12) * 1100 + time * 19 + y * .09) % 1100 - 70;
			var light = Math.exp(-square((x - 370) / 240.0)) * .8 + .2;
			var alpha = (int) ((near ? 29 : 12 + depth * 40) * light);
			g.setColor(new Color(163, 215, 220, alpha));
			g.setStroke(new BasicStroke(near ? 1.8f : (float) (.45 + depth * .5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			var length = near ? 36 : 6 + depth * 18;
			g.draw(new java.awt.geom.Line2D.Double(x, y, x + length * .12, y + length));
		}
		if (near) {
			// Defocused rain catches the candlelight at the front of the lens.
			for (var i = 0; i < 10; i++) {
				var x = hash(i, 55) * W;
				var y = (hash(i, 56) * 700 + time * 90) % 700 - 50;
				g.setComposite(AlphaComposite.SrcOver.derive(.10f));
				g.drawImage(halo, (int) x, (int) y, 12 + i % 3 * 6, 22 + i % 3 * 6, null);
			}
			g.setComposite(AlphaComposite.SrcOver);
		}
	}

	private void paintAltar(Graphics2D graphics, int width, int height) {
		var g = (Graphics2D) graphics.create();
		try {
			g.scale(width / (double) W, height / (double) H);
			path.reset();
			path.moveTo(0, 528); path.lineTo(185, 496); path.lineTo(372, 538);
			path.lineTo(666, 538); path.lineTo(805, 504); path.lineTo(960, 536);
			path.lineTo(960, 600); path.lineTo(0, 600); path.closePath();
			g.setPaint(new GradientPaint(0, 502, new Color(24, 33, 35), 0, 600, new Color(4, 9, 13)));
			g.fill(path);
			var clip = g.getClip();
			g.clip(path);
			// Wet stone grain and directional scratches, baked once per size.
			for (var i = 0; i < 10000; i++) {
				var x = hash(i, 21) * W;
				var y = 498 + hash(i, 22) * 102;
				var light = Math.exp(-square((x - 167) / 100.0));
				g.setColor(new Color((int) (74 + light * 100), (int) (96 + light * 36), 100,
						(int) (hash(i, 23) * 36)));
				g.draw(new java.awt.geom.Line2D.Double(x, y, x + hash(i, 24) * 4 + 1, y));
			}
			g.setClip(clip);
			glow(g, 170, 531, 110, new Color(234, 130, 46, 30));
			g.setColor(new Color(0, 4, 7, 180));
			g.fillOval(690, 541, 157, 18);
			g.setPaint(new GradientPaint(709, 0, new Color(17, 33, 37), 805, 0, new Color(63, 84, 77)));
			g.fillArc(706, 499, 118, 91, 180, 180);
			g.drawImage(bronze, 706, 537, 118, 47, null);
			g.setColor(new Color(99, 115, 100));
			g.fillOval(706, 530, 118, 17);
			g.setColor(new Color(29, 36, 35));
			g.fillOval(711, 533, 108, 11);
			for (var i = 0; i < 90; i++) {
				g.setColor(new Color(123, 130, 111, 70));
				g.fillRect(722 + (int) (hash(i, 41) * 82), 535 + (int) (hash(i, 42) * 7), 1, 1);
			}
			for (var i = 0; i < 3; i++) {
				g.setStroke(new BasicStroke(2));
				g.setColor(new Color(40, 30, 27));
				g.drawLine(758 + i * 9, 538, 742 + i * 17, 414 + i * 7);
				g.setStroke(new BasicStroke(.7f));
				g.setColor(new Color(165, 105, 65));
				g.drawLine(758 + i * 9, 538, 742 + i * 17, 414 + i * 7);
			}
		} finally { g.dispose(); }
	}

	private void paintCandle(Graphics2D g, double x, double bottom, double width, double height, double time) {
		var top = bottom - height;
		var sway = Math.sin(time * 3.7) * 1.7 + Math.sin(time * 9.3) * .6;
		var pulse = .92 + Math.sin(time * 7.1) * .08;
		glow(g, x, top - 12, (float) (104 * pulse), new Color(255, 129, 34, 48));
		glow(g, x, top - 12, 34, new Color(255, 171, 64, 70));
		g.setColor(new Color(4, 9, 12));
		g.fill(new Ellipse2D.Double(x - width, bottom - 4, width * 2, 10));
		g.setPaint(new java.awt.LinearGradientPaint((float) (x - width / 2), 0, (float) (x + width / 2), 0,
				new float[] { 0, .25f, .65f, 1 }, new Color[] { new Color(80, 54, 35),
						new Color(191, 145, 78), new Color(231, 190, 111), new Color(111, 79, 44) }));
		g.fill(new java.awt.geom.RoundRectangle2D.Double(x - width / 2, top, width, height, 5, 5));
		g.drawImage(wax, (int) (x - width / 2), (int) top + 3, (int) width, (int) height - 3, null);
		g.setPaint(new GradientPaint(0, (float) top, new Color(255, 183, 76, 0),
				0, (float) bottom, new Color(9, 15, 17, 150)));
		g.fill(new java.awt.geom.Rectangle2D.Double(x - width / 2, top + 3, width, height - 3));
		g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(213, 170, 99, 155));
		for (var i = 0; i < 4; i++) {
			var xx = x - width * .35 + i * width * .22;
			g.draw(new java.awt.geom.Line2D.Double(xx, top + 3, xx, top + 8 + hash(i, (int) x) * height * .35));
		}
		g.setColor(new Color(234, 193, 121));
		g.fill(new Ellipse2D.Double(x - width / 2, top - 2, width, 7));
		g.setColor(new Color(94, 61, 32));
		g.fill(new Ellipse2D.Double(x - width * .26, top, width * .52, 3));
		for (var layer = 0; layer < 5; layer++) {
			var span = 7 - layer;
			path.reset();
			path.moveTo(x, top - 2);
			path.curveTo(x - span, top - 7, x - span + sway, top - 15, x + sway, top - 31 + layer * 3);
			path.curveTo(x + sway + 2, top - 18, x + span, top - 7, x, top - 2);
			g.setColor(new Color(255, 125 + layer * 27, 33 + layer * 40, 180 + layer * 15));
			g.fill(path);
		}
		g.setColor(new Color(58, 83, 161, 170));
		g.fill(new Ellipse2D.Double(x - 2, top - 6, 4, 4));
		g.setColor(new Color(41, 28, 24));
		g.setStroke(new BasicStroke(1));
		g.draw(new java.awt.geom.Line2D.Double(x, top + 1, x + 1, top - 6));
	}

	private void paintIncensePlume(Graphics2D g, double time) {
		// A lit density field rather than lines or a chain of circles. Noise advects upwards;
		// three thin laminar stems roll into broad, translucent curls as they cool.
		for (var y = 0; y < 200; y++) {
			var worldY = 142 + y * 1.45;
			for (var x = 0; x < 128; x++) {
				var worldX = 646 + x * 1.45;
				double density = 0;
				for (var stick = 0; stick < 3; stick++) {
					var rise = (414 + stick * 7 - worldY) / 270.0;
					if (rise < 0 || rise > 1) continue;
					var center = 742 + stick * 17 - rise * rise * 47
							+ Math.sin(rise * 13 - time * .75 + stick * .9) * rise * 27
							+ Math.sin(rise * 27 - time * 1.1) * rise * 5;
					var turbulence = vapor(x * .8 + stick * 17, y * .7 + time * 9);
					var spread = 1.3 + rise * 16;
					var distance = (worldX - center + (turbulence - .5) * rise * 25) / spread;
					density += Math.exp(-distance * distance * 1.7) * Math.pow(1 - rise, 1.3)
							* (.3 + turbulence * 1.5);
				}
				var light = vapor(x * .8 + 2, y * .7 + time * 9) - vapor(x * .8 - 2, y * .7 + time * 9);
				var alpha = Math.clamp((int) (density * 108), 0, 150);
				plumePixels[y * 128 + x] = alpha << 24 | rgb(136 + light * 230, 168 + light * 230, 179 + light * 230);
			}
		}
		g.drawImage(plume, 646, 142, 186, 290, null);
	}

	private double vapor(double x, double y) {
		var ix = (int) Math.floor(x); var iy = (int) Math.floor(y);
		var fx = x - ix; var fy = y - iy;
		var a = vaporNoise[(iy & 127) * 128 + (ix & 127)] * (1 - fx)
				+ vaporNoise[(iy & 127) * 128 + ((ix + 1) & 127)] * fx;
		var b = vaporNoise[((iy + 1) & 127) * 128 + (ix & 127)] * (1 - fx)
				+ vaporNoise[((iy + 1) & 127) * 128 + ((ix + 1) & 127)] * fx;
		return a * (1 - fy) + b * fy;
	}

	private void paintLensDrops(Graphics2D g, double time) {
		for (var i = 0; i < 19; i++) {
			var x = 24 + hash(i, 101) * 906;
			var y = (hash(i, 102) * 720 + time * (1.5 + hash(i, 103) * 4)) % 720 - 70;
			var radius = 2.5 + hash(i, 104) * 5;
			// Sample a displaced patch of the actual scene inside each curved bead.
			var clip = g.getClip();
			var bead = new Ellipse2D.Double(x - radius, y - radius * 1.5, radius * 2, radius * 3);
			g.clip(bead);
			var sx = Math.clamp((int) x - 18, 0, W - 37);
			var sy = Math.clamp((int) y - 22, 0, WATER - 45);
			g.drawImage(landscape, (int) (x - radius), (int) (y - radius * 1.5),
					(int) (x + radius), (int) (y + radius * 1.5), sx + 36, sy + 44, sx, sy, null);
			g.setClip(clip);
			g.setStroke(new BasicStroke(.7f));
			g.setColor(new Color(152, 202, 211, 58));
			g.draw(bead);
			g.setColor(new Color(209, 228, 222, 110));
			g.draw(new java.awt.geom.Arc2D.Double(x - radius * .65, y - radius, radius * 1.3, radius * 2, 220, 72, java.awt.geom.Arc2D.OPEN));
		}
	}

	private void paintFinish(Graphics2D graphics, int width, int height) {
		var g = (Graphics2D) graphics.create();
		try {
			g.scale(width / (double) W, height / (double) H);
			g.setPaint(new RadialGradientPaint(470, 270, 580,
					new float[] { 0, .45f, 1 }, new Color[] { CLEAR, new Color(1, 5, 12, 15), new Color(1, 4, 10, 215) }));
			g.fillRect(0, 0, W, H);
			// Fine grain unifies the procedural layers without a scanline filter.
			for (var i = 0; i < 16000; i++) {
				g.setColor(new Color(160, 197, 203, i % 3 + 2));
				g.fillRect((int) (hash(i, 71) * W), (int) (hash(i, 72) * H), 1, 1);
			}
		} finally { g.dispose(); }
	}

	private static BufferedImage cloudSprite() {
		var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (var y = 0; y < 64; y++) for (var x = 0; x < 64; x++) {
			var radius = square((x - 31.5) / 31.5) + square((y - 31.5) / 31.5);
			var density = Math.max(0, 1 - radius);
			var alpha = (int) (255 * density * density * (.55 + fbm(x * .12, y * .12) * .45));
			pixels[y * 64 + x] = alpha << 24 | 0xC3D3D1;
		}
		return image;
	}

	private static BufferedImage haloSprite() {
		var image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
		var g = image.createGraphics();
		glow(g, 24, 24, 23, new Color(212, 203, 168, 150));
		g.dispose();
		return image;
	}

	private static BufferedImage waxTexture() {
		var image = new BufferedImage(64, 192, BufferedImage.TYPE_INT_RGB);
		var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (var y = 0; y < 192; y++) for (var x = 0; x < 64; x++) {
			var nx = (x - 31.5) / 32;
			var nz = Math.sqrt(Math.max(0, 1 - nx * nx));
			var diffuse = Math.max(0, -.4 * nx + .8 * nz);
			var specular = Math.pow(Math.max(0, -.25 * nx + .968 * nz), 32) * 23;
			var sss = Math.exp(-y / 29.0) * 48;
			var grain = (noise(x * 1.2, y * .35) - .5) * 9;
			var streak = noise(x * .35, y * .016) * 13;
			pixels[y * 64 + x] = rgb(37 + diffuse * 151 + specular + sss + grain - streak,
					31 + diffuse * 115 + specular + sss * .6 + grain - streak,
					23 + diffuse * 61 + specular * .7 + sss * .1 + grain - streak);
		}
		return image;
	}

	private static BufferedImage bowlTexture() {
		var image = new BufferedImage(236, 94, BufferedImage.TYPE_INT_ARGB);
		var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (var y = 0; y < 94; y++) for (var x = 0; x < 236; x++) {
			var nx = (x - 117.5) / 118;
			var ny = y / 94.0;
			var radius = nx * nx + ny * ny;
			if (radius > 1) continue;
			var nz = Math.sqrt(1 - radius);
			var diffuse = Math.max(0, -.5 * nx - .35 * ny + .65 * nz);
			var specular = Math.pow(Math.max(0, -.48 * nx - .2 * ny + .85 * nz), 25);
			var patina = fbm(x * .07, y * .12);
			var grain = (hash(x, y) - .5) * 13;
			pixels[y * 236 + x] = 0xff000000 | rgb(13 + diffuse * 42 + specular * 68 + grain,
					23 + diffuse * 48 + specular * 66 + patina * 10 + grain,
					27 + diffuse * 43 + specular * 48 + patina * 6 + grain);
		}
		return image;
	}

	private static void glow(Graphics2D g, double x, double y, float radius, Color color) {
		g.setPaint(new RadialGradientPaint((float) x, (float) y, radius,
				new float[] { 0, .22f, 1 }, new Color[] { color,
						new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 3),
						new Color(color.getRed(), color.getGreen(), color.getBlue(), 0) }));
		g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
	}

	private static double square(double value) { return value * value; }
	private static int rgb(double r, double g, double b) {
		return Math.clamp((int) r, 0, 255) << 16 | Math.clamp((int) g, 0, 255) << 8 | Math.clamp((int) b, 0, 255);
	}
	private static double hash(int x, int y) {
		var value = x * 374761393 + y * 668265263;
		value = (value ^ (value >>> 13)) * 1274126177;
		return ((value ^ (value >>> 16)) & 0x7fffffff) / 2147483648.0;
	}
	private static double noise(double x, double y) {
		var ix = (int) Math.floor(x);
		var iy = (int) Math.floor(y);
		var fx = x - ix; var fy = y - iy;
		fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
		var a = hash(ix, iy) * (1 - fx) + hash(ix + 1, iy) * fx;
		var b = hash(ix, iy + 1) * (1 - fx) + hash(ix + 1, iy + 1) * fx;
		return a * (1 - fy) + b * fy;
	}
	private static double fbm(double x, double y) {
		return noise(x, y) * .55 + noise(x * 2.03, y * 2.03) * .27 + noise(x * 4.07, y * 4.07) * .12
				+ noise(x * 8.13, y * 8.13) * .06;
	}
}
