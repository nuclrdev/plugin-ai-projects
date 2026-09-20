from PIL import Image, ImageDraw, ImageFont
import math, os

SIZE = 512
img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# --- Hexagon (C++ logo shape) ---
cx, cy = SIZE // 2, SIZE // 2
hex_r = SIZE // 2 - 16

def hex_points(cx, cy, r):
    pts = []
    for i in range(6):
        angle_deg = 60 * i - 90
        angle_rad = math.radians(angle_deg)
        pts.append((cx + r * math.cos(angle_rad), cy + r * math.sin(angle_rad)))
    return pts

# Outer hex with gradient simulation (two layers)
outer = hex_points(cx, cy, hex_r)
draw.polygon(outer, fill=(10, 30, 70))

inner = hex_points(cx, cy, hex_r - 4)
draw.polygon(inner, fill=(0, 68, 140))

inner2 = hex_points(cx, cy, hex_r - 12)
draw.polygon(inner2, fill=(0, 84, 156))

inner3 = hex_points(cx, cy, hex_r - 20)
draw.polygon(inner3, fill=(0, 68, 156))

# Hex border
draw.polygon(outer, outline=(10, 40, 90), width=3)

# --- "C" letter ---
try:
    font_c = ImageFont.truetype("arial.ttf", 210)
except:
    font_c = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 210)

# Shadow
draw.text((75, 148), "C", fill=(0, 0, 0, 80), font=font_c)
# Main letter
draw.text((72, 144), "C", fill=(255, 255, 255), font=font_c)

# --- "++" symbols ---
try:
    font_plus = ImageFont.truetype("arial.ttf", 64)
except:
    font_plus = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 64)

plus_x_start = 290
plus_y = 185

# First +
bar_h, bar_w = 8, 56
x1 = plus_x_start
draw.rectangle([x1 - bar_w//2, plus_y - bar_h//2, x1 + bar_w//2, plus_y + bar_h//2], fill=(255, 255, 255))
draw.rectangle([x1 - bar_h//2, plus_y - bar_w//2, x1 + bar_h//2, plus_y + bar_w//2], fill=(255, 255, 255))

# Second +
x2 = plus_x_start + 52
draw.rectangle([x2 - bar_w//2, plus_y - bar_h//2, x2 + bar_w//2, plus_y + bar_h//2], fill=(255, 255, 255))
draw.rectangle([x2 - bar_h//2, plus_y - bar_w//2, x2 + bar_h//2, plus_y + bar_w//2], fill=(255, 255, 255))

# --- "23" badge ---
badge_x, badge_y = 275, 320
badge_w, badge_h = 130, 72
badge_r = 16

# Rounded rectangle for badge
def rounded_rect(draw, xy, r, **kwargs):
    x0, y0, x1, y1 = xy
    draw.rectangle([x0 + r, y0, x1 - r, y1], **kwargs)
    draw.rectangle([x0, y0 + r, x1, y1 - r], **kwargs)
    draw.pieslice([x0, y0, x0 + 2*r, y0 + 2*r], 180, 270, **kwargs)
    draw.pieslice([x1 - 2*r, y0, x1, y0 + 2*r], 270, 360, **kwargs)
    draw.pieslice([x0, y1 - 2*r, x0 + 2*r, y1], 90, 180, **kwargs)
    draw.pieslice([x1 - 2*r, y1 - 2*r, x1, y1], 0, 90, **kwargs)

rounded_rect(draw, (badge_x, badge_y, badge_x + badge_w, badge_y + badge_h), badge_r,
             fill=(10, 35, 70), outline=(74, 158, 255), width=3)

try:
    font_23 = ImageFont.truetype("arialbd.ttf", 44)
except:
    font_23 = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 44)

# Center "23" in badge
bbox = draw.textbbox((0, 0), "23", font=font_23)
tw = bbox[2] - bbox[0]
th = bbox[3] - bbox[1]
tx = badge_x + (badge_w - tw) // 2
ty = badge_y + (badge_h - th) // 2 - 2

# Glow effect
draw.text((tx - 1, ty - 1), "23", fill=(30, 100, 200), font=font_23)
draw.text((tx, ty), "23", fill=(74, 158, 255), font=font_23)

# --- Subtle top highlight arc ---
arc_pts = []
for i in range(60):
    angle = math.radians(210 + i * 120 / 59)
    r_arc = hex_r * 0.75
    x = cx + r_arc * math.cos(angle)
    y = cy * 0.58 + r_arc * math.sin(angle) * 0.3
    arc_pts.append((x, y))
for i in range(len(arc_pts) - 1):
    draw.line([arc_pts[i], arc_pts[i+1]], fill=(255, 255, 255, 20), width=2)

# Save
out_path = os.path.join(os.path.dirname(__file__) if '__file__' in dir() else ".", "cpp23-icon.png")
img.save(out_path, "PNG")
print(f"Saved to {out_path}")
