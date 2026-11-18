# Usage: python3 preview.py photo.jpg out.jpg [strength]
# Mirrors FrameRenderer.java geometry/colours with PIL, to preview without a TV.
import sys
from PIL import Image, ImageDraw, ImageFilter
W, H = 1920, 1080
def hexc(x): return ((x >> 16) & 255, (x >> 8) & 255, x & 255)
WALL, MATTE, FRAME = hexc(0x2E2B28), hexc(0xF1EDE4), hexc(0x1F1915)
def shade(c, f): return tuple(min(255, int(v * f)) for v in c)
import numpy as np
src, out = sys.argv[1], sys.argv[2]
STRENGTH = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5   # mirrors ArtTreatment.STRENGTH
photo = Image.open(src).convert("RGB")

def art(img, s):
    """Mirror of ArtTreatment.java colour pass (saturation, matte contrast, lower warm light)."""
    if s == 0: return img
    contrast, sat, gain = 1 - .30*s, 1 - .28*s, 1 - .16*s
    warm = np.array([1 + .03*s, 1, 1 - .05*s])
    a = np.asarray(img).astype(np.float32)
    lum = a @ np.array([.213, .715, .072], dtype=np.float32)
    a = lum[..., None] * (1 - sat) + a * sat
    a = a * contrast + 128 * (1 - contrast)
    a = a * gain * warm
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))

def surface(img, s):
    """Mirror of ArtTreatment.drawSurface: gallery-light falloff + fine paper grain."""
    if s == 0: return img
    w, h = img.size
    a = np.asarray(img).astype(np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.hypot(xx - w/2, yy - (h/2 - .08*h)) / (np.hypot(w, h) / 2)
    v = np.clip((r - .55) / .45, 0, 1) * .22 * s
    a = a * (1 - v[..., None])
    rng = np.random.default_rng(7)
    g = np.clip(128 + rng.normal(0, 40, (h, w)), 0, 255)
    alpha = .05 * s
    a = a * (1 - alpha) + g[..., None] * alpha
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
img = Image.new("RGB", (W, H), WALL)
fw, mw, extra, margin = .030*H, .085*H, .012*H, .07*H
b = fw + mw
s = min((W-2*margin-2*b)/photo.width, (H-2*margin-2*b-extra)/photo.height)
pw, ph = photo.width*s, photo.height*s
ow, oh = pw+2*b, ph+2*b+extra
L, T = (W-ow)/2, (H-oh)/2
o = (L, T, L+ow, T+oh); m = (o[0]+fw, o[1]+fw, o[2]-fw, o[3]-fw)
pr = (m[0]+mw, m[1]+mw, m[0]+mw+pw, m[1]+mw+ph)
sh = Image.new("L", (W, H), 0)
ImageDraw.Draw(sh).rectangle((o[0]+.004*H, o[1]+.012*H, o[2]+.004*H, o[3]+.018*H), fill=153)
sh = sh.filter(ImageFilter.GaussianBlur(.025*H/2))
img = Image.composite(Image.new("RGB", (W, H), (0, 0, 0)), img, sh)
d = ImageDraw.Draw(img)
d.polygon([(o[0],o[1]),(o[2],o[1]),(m[2],m[1]),(m[0],m[1])], fill=shade(FRAME,1.35))
d.polygon([(o[0],o[1]),(m[0],m[1]),(m[0],m[3]),(o[0],o[3])], fill=shade(FRAME,1.10))
d.polygon([(o[2],o[1]),(o[2],o[3]),(m[2],m[3]),(m[2],m[1])], fill=shade(FRAME,.95))
d.polygon([(o[0],o[3]),(m[0],m[3]),(m[2],m[3]),(o[2],o[3])], fill=shade(FRAME,.75))
d.rectangle(m, fill=MATTE)
for i in range(int(.02*H)):
    a = 0x30 * (1 - i/(.02*H)) / 255
    d.line((m[0], m[1]+i, m[2], m[1]+i), fill=tuple(int(v*(1-a)) for v in MATTE))
bv = max(2, .005*H)
d.rectangle((pr[0]-bv, pr[1]-bv, pr[2]+bv, pr[3]+bv), fill=hexc(0xFBF8F1))
d.polygon([(pr[2]+bv,pr[1]-bv),(pr[2]+bv,pr[3]+bv),(pr[0]-bv,pr[3]+bv),(pr[0],pr[3]),(pr[2],pr[3]),(pr[2],pr[1])], fill=hexc(0xD9D3C6))
ph_img = photo.resize((round(pw), round(ph)), Image.LANCZOS)
img.paste(surface(art(ph_img, STRENGTH), STRENGTH), (round(pr[0]), round(pr[1])))
img.save(out, quality=90)
