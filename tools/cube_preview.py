"""
Offline preview of the NowPlayingSlab compositing stack.

Reimplements CubeGeometry.project and the CubeMaterial layer order in numpy so the
cube can be inspected without a device. Keep the constants below in sync with
CubeGeometry.kt and NowPlayingSlabMetrics — this is a preview of the real transform,
not an artist's impression.

This is also the harness for the baked-material work: the raytracer will render into
the same pose, so what you see here is where its output will land.

    python tools/cube_preview.py art.png out.png
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# ── Constants mirrored from the Kotlin ────────────────────────────────────────
FACE_DP = 100.0
THICKNESS_DP = 14.0
CORNER_DP = 10.0
MARGIN_DP = 18.0
REFLECTION_GAP_DP = 3.0
REFLECTION_COMPRESS = 0.42
BASE_TILT_X = 18.0
BASE_TILT_Y = 22.0
BASE_ROLL = -4.0
CAMERA_DISTANCE_FACTOR = 5.0
ART_DEPTH_FRACTION = 0.85  # 0 = flush with the front surface, 1 = against the back

DENSITY = 6.0      # preview render scale
SUPERSAMPLE = 2    # rendered at DENSITY*SUPERSAMPLE then resolved down

# PIL fills polygons with hard edges. Skia antialiases on device, so supersampling
# here is a fidelity fix, not just a cosmetic one.


def project(x, y, z, rot_x, rot_y, camera_z, cx, cy, roll):
    """Straight port of CubeGeometry.project."""
    rx, ry, rr = np.radians([rot_x, rot_y, roll])
    y1 = y * np.cos(rx) - z * np.sin(rx)
    z1 = y * np.sin(rx) + z * np.cos(rx)
    x2 = x * np.cos(ry) + z1 * np.sin(ry)
    z2 = -x * np.sin(ry) + z1 * np.cos(ry)
    scale = camera_z / (camera_z - z2)
    ox, oy = x2 * scale, y1 * scale
    return (cx + ox * np.cos(rr) - oy * np.sin(rr),
            cy + ox * np.sin(rr) + oy * np.cos(rr),
            z2)


class Pose:
    def __init__(self, hw, hh, hd, radius, rot_x, rot_y, roll, camera_z, cx, cy,
                 scale=1.0, mirror_line=None, mirror_compress=1.0):
        self.hw, self.hh, self.hd = hw * scale, hh * scale, hd * scale
        self.radius, self.scale = radius * scale, scale
        self.rot_x, self.rot_y, self.roll = rot_x, rot_y, roll
        self.camera_z, self.cx, self.cy = camera_z, cx, cy
        self.mirror_line, self.mirror_compress = mirror_line, mirror_compress
        self._args = (hw, hh, hd, radius, rot_x, rot_y, roll, camera_z, cx, cy, scale)

    def _raw(self, x, y, z):
        return project(x, y, z, self.rot_x, self.rot_y, self.camera_z,
                       self.cx, self.cy, self.roll)

    def p(self, x, y, z):
        px, py, pz = self._raw(x, y, z)
        if self.mirror_line is not None:
            py = self.mirror_line + (self.mirror_line - py) * self.mirror_compress
        return px, py, pz

    @property
    def art_z(self):
        return self.hd - ART_DEPTH_FRACTION * 2 * self.hd

    def front_uv(self, u, v):
        return self.p(-self.hw + u * 2 * self.hw, -self.hh + v * 2 * self.hh, self.hd)

    def art_uv(self, u, v):
        return self.p(-self.hw + u * 2 * self.hw, -self.hh + v * 2 * self.hh, self.art_z)

    def art_outline(self):
        return [self.p(x, y, self.art_z)[:2] for x, y in self._outline_model()]

    def visible_inner_wall(self):
        """Segments turned away from the viewer: seen from inside, through the glass."""
        model = self._outline_model()
        f = [self.p(x, y, self.hd)[:2] for x, y in model]
        a = [self.p(x, y, self.art_z)[:2] for x, y in model]
        rf = [self._raw(x, y, self.hd)[:2] for x, y in model]
        ra = [self._raw(x, y, self.art_z)[:2] for x, y in model]
        out = []
        for i in range(len(model)):
            j = (i + 1) % len(model)
            q = [rf[i], rf[j], ra[j], ra[i]]
            area = 0.5 * sum(q[k][0] * q[(k + 1) % 4][1] - q[(k + 1) % 4][0] * q[k][1]
                             for k in range(4))
            if area > 0:
                out.append([f[i], f[j], a[j], a[i]])
        return out

    def _corners(self, fn):
        hw, hh, hd = self.hw, self.hh, self.hd
        return dict(
            fTL=fn(-hw, -hh, hd), fTR=fn(hw, -hh, hd),
            fBR=fn(hw, hh, hd), fBL=fn(-hw, hh, hd),
            bTL=fn(-hw, -hh, -hd), bTR=fn(hw, -hh, -hd),
            bBR=fn(hw, hh, -hd), bBL=fn(-hw, hh, -hd))

    @property
    def corners(self):
        return self._corners(self.p)

    def face_quad(self, face, raw=False):
        c = self._corners(self._raw if raw else self.p)
        return {
            'RIGHT': [c['fTR'], c['fBR'], c['bBR'], c['bTR']],
            'LEFT':  [c['fBL'], c['fTL'], c['bTL'], c['bBL']],
            'TOP':   [c['fTL'], c['fTR'], c['bTR'], c['bTL']],
            'BOTTOM': [c['fBR'], c['fBL'], c['bBL'], c['bBR']],
        }[face]

    def visible_faces(self):
        out = []
        for face in ('RIGHT', 'LEFT', 'TOP', 'BOTTOM'):
            q = self.face_quad(face, raw=True)
            area = 0.5 * sum(q[i][0] * q[(i + 1) % 4][1] - q[(i + 1) % 4][0] * q[i][1]
                             for i in range(4))
            if area < 0:
                out.append((face, np.mean([pt[2] for pt in q])))
        return [f for f, _ in sorted(out, key=lambda t: t[1])]

    def _outline_model(self, segments=8):
        hw, hh, r = self.hw, self.hh, self.radius
        pts = []
        for ccx, ccy, start in ((-hw + r, -hh + r, 180), (hw - r, -hh + r, 270),
                                (hw - r, hh - r, 0), (-hw + r, hh - r, 90)):
            for s in range(segments + 1):
                a = np.radians(start + 90 * s / segments)
                pts.append((ccx + r * np.cos(a), ccy + r * np.sin(a)))
        return pts

    def outline(self):
        return [self.p(x, y, self.hd)[:2] for x, y in self._outline_model()]

    def back_outline(self):
        return [self.p(x, y, -self.hd)[:2] for x, y in self._outline_model()]

    def visible_rim(self):
        """Extrude the rounded outline, not the sharp corner quads."""
        model = self._outline_model()
        f = [self.p(x, y, self.hd)[:2] for x, y in model]
        b = [self.p(x, y, -self.hd)[:2] for x, y in model]
        rf = [self._raw(x, y, self.hd)[:2] for x, y in model]
        rb = [self._raw(x, y, -self.hd)[:2] for x, y in model]
        out = []
        for i in range(len(model)):
            j = (i + 1) % len(model)
            q = [rf[i], rf[j], rb[j], rb[i]]
            area = 0.5 * sum(q[k][0] * q[(k + 1) % 4][1] - q[(k + 1) % 4][0] * q[k][1]
                             for k in range(4))
            if area < 0:
                out.append([f[i], f[j], b[j], b[i]])
        return out

    def mirrored_below(self, line, compress):
        return Pose(*self._args, mirror_line=line, mirror_compress=compress)

    def bounds(self):
        c = list(self.corners.values())
        xs, ys = [p[0] for p in c], [p[1] for p in c]
        return min(xs), min(ys), max(xs), max(ys)


# ── Raster helpers ────────────────────────────────────────────────────────────

def blank(shape):
    return np.zeros((shape[1], shape[0], 4), np.float32)


def over(dst, src):
    a = src[..., 3:4]
    dst[..., :3] = src[..., :3] * a + dst[..., :3] * (1 - a)
    dst[..., 3:4] = a + dst[..., 3:4] * (1 - a)
    return dst


def poly_mask(shape, pts, blur=0.0):
    img = Image.new('L', shape, 0)
    ImageDraw.Draw(img).polygon([(float(x), float(y)) for x, y in pts], fill=255)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(img, np.float32) / 255.0


def _ramp(t, stops):
    out = np.zeros(t.shape + (4,), np.float32)
    pos = [s[0] for s in stops]
    for i in range(len(stops) - 1):
        lo, hi = pos[i], pos[i + 1]
        seg = (t >= lo) & (t <= hi)
        if not seg.any():
            continue
        f = ((t[seg] - lo) / max(hi - lo, 1e-6))[:, None]
        out[seg] = np.array(stops[i][1]) * (1 - f) + np.array(stops[i + 1][1]) * f
    out[t <= pos[0]] = stops[0][1]
    out[t >= pos[-1]] = stops[-1][1]
    return out


def linear_gradient(shape, start, end, stops):
    ys, xs = np.mgrid[0:shape[1], 0:shape[0]].astype(np.float32)
    d = np.array(end, np.float32) - np.array(start, np.float32)
    denom = float(d @ d) or 1.0
    t = ((xs - start[0]) * d[0] + (ys - start[1]) * d[1]) / denom
    return _ramp(np.clip(t, 0, 1), stops)


def radial_gradient(shape, center, radius, stops):
    ys, xs = np.mgrid[0:shape[1], 0:shape[0]].astype(np.float32)
    t = np.sqrt((xs - center[0]) ** 2 + (ys - center[1]) ** 2) / max(radius, 1e-6)
    return _ramp(np.clip(t, 0, 1), stops)


def homography(src, dst):
    rows = []
    for (x, y), (u, v) in zip(src, dst):
        rows += [[x, y, 1, 0, 0, 0, -u * x, -u * y, -u],
                 [0, 0, 0, x, y, 1, -v * x, -v * y, -v]]
    _, _, vt = np.linalg.svd(np.array(rows, np.float64))
    h = vt[-1].reshape(3, 3)
    return h / h[2, 2]


def warp_art(shape, art, quad):
    """Inverse-map the face quad back into art space and sample bilinearly."""
    w, h = art.size
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    inv = np.linalg.inv(homography(src, [(p[0], p[1]) for p in quad]))
    ys, xs = np.mgrid[0:shape[1], 0:shape[0]].astype(np.float64)
    uvw = inv @ np.stack([xs.ravel(), ys.ravel(), np.ones(xs.size)])
    u = (uvw[0] / uvw[2]).reshape(xs.shape).astype(np.float32)
    v = (uvw[1] / uvw[2]).reshape(xs.shape).astype(np.float32)
    inside = ((u >= 0) & (u < w) & (v >= 0) & (v < h)).astype(np.float32)
    del uvw, xs, ys

    pix = np.asarray(art.convert('RGB'), np.float32) / 255.0
    x0 = np.clip(np.floor(u), 0, w - 1).astype(np.int32)
    y0 = np.clip(np.floor(v), 0, h - 1).astype(np.int32)
    x1 = np.clip(x0 + 1, 0, w - 1)
    y1 = np.clip(y0 + 1, 0, h - 1)
    fx = np.clip(u - x0, 0, 1)[..., None]
    fy = np.clip(v - y0, 0, 1)[..., None]
    rgb = (pix[y0, x0] * (1 - fx) * (1 - fy) + pix[y0, x1] * fx * (1 - fy) +
           pix[y1, x0] * (1 - fx) * fy + pix[y1, x1] * fx * fy)

    out = np.zeros(shape[::-1] + (4,), np.float32)
    out[..., :3] = rgb
    out[..., 3] = inside
    return out


# ── Palette (approximates PlaybackColors.generateCubePalette) ─────────────────

def cube_palette(art):
    import colorsys
    small = np.asarray(art.convert('RGB').resize((64, 64)), np.float32) / 255.0
    hsv = np.array([colorsys.rgb_to_hsv(*px) for px in small.reshape(-1, 3)])
    vibrant = hsv[np.argmax(hsv[:, 1] * hsv[:, 2])]
    h, s, v = vibrant

    def mk(sat_mul, val_mul, sat_range, val_range):
        ss = float(np.clip(s * sat_mul, *sat_range))
        vv = float(np.clip(v * val_mul, *val_range))
        return np.array(list(colorsys.hsv_to_rgb(h, ss, vv)) + [1.0], np.float32)

    return dict(
        primary=mk(0.9, 0.8, (0.15, 0.9), (0.10, 0.85)),
        shadow=mk(0.75, 0.45, (0.10, 0.75), (0.05, 0.55)),
        specular=mk(0.4, 1.4, (0.05, 0.45), (0.55, 1.0)))


def rgba(c, a):
    return [float(c[0]), float(c[1]), float(c[2]), float(a)]


TRANSPARENT = [0, 0, 0, 0]


# ── The layer stack ───────────────────────────────────────────────────────────

def draw_body(canvas, pose, art, pal, ambient):
    shape = (canvas.shape[1], canvas.shape[0])
    x0, y0, x1, y1 = pose.bounds()

    if ambient:
        w = (x1 - x0) * 1.18
        h = w * 0.16
        cx, cy = (x0 + x1) / 2, y1 - h * 0.25
        shadow = radial_gradient(shape, (cx, cy), w / 2, [
            (0.0, [0, 0, 0, 0.30]), (0.5, [0, 0, 0, 0.135]), (1.0, TRANSPARENT)])
        ell = Image.new('L', shape, 0)
        ImageDraw.Draw(ell).ellipse([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], fill=255)
        shadow[..., 3] *= np.asarray(ell, np.float32) / 255.0
        over(canvas, shadow)

        r = (x1 - x0) * 0.85
        over(canvas, radial_gradient(shape, ((x0 + x1) / 2, (y0 + y1) / 2), r, [
            (0.0, rgba(pal['specular'], 0.22)),
            (0.45, rgba(pal['primary'], 0.14)),
            (1.0, TRANSPARENT)]))

    rim = pose.visible_rim()
    if rim:
        sx = np.mean([(q[2][0] + q[3][0] - q[0][0] - q[1][0]) / 2 for q in rim])
        sy = np.mean([(q[2][1] + q[3][1] - q[0][1] - q[1][1]) / 2 for q in rim])
        anchor = rim[0][0]
        grad = linear_gradient(shape, anchor, (anchor[0] + sx, anchor[1] + sy), [
            (0.00, rgba(pal['specular'], 1)), (0.14, rgba(pal['primary'], 1)),
            (0.62, rgba(pal['shadow'], 1)), (1.00, rgba(pal['shadow'], 1))])
        ribbon = Image.new('L', shape, 0)
        rd = ImageDraw.Draw(ribbon)
        for q in rim:
            rd.polygon([(float(x), float(y)) for x, y in q], fill=255)
        grad[..., 3] *= np.asarray(ribbon, np.float32) / 255.0
        over(canvas, grad)

    mask = poly_mask(shape, pose.outline())

    # Interior body first, so the gap between the art plane and the face is never a hole.
    face_layer = np.zeros(shape[::-1] + (4,), np.float32)
    face_layer[..., :3] = pal['shadow'][:3]
    face_layer[..., 3] = 1.0

    art_quad = [pose.art_uv(0, 0), pose.art_uv(1, 0), pose.art_uv(1, 1), pose.art_uv(0, 1)]
    over(face_layer, warp_art(shape, art, art_quad))

    wall = pose.visible_inner_wall()
    if wall:
        af = [pose.art_uv(u, v) for u, v in ((0, 0), (1, 0), (1, 1), (0, 1))]
        ff = [pose.front_uv(u, v) for u, v in ((0, 0), (1, 0), (1, 1), (0, 1))]
        sx = np.mean([a[0] - f[0] for a, f in zip(af, ff)])
        sy = np.mean([a[1] - f[1] for a, f in zip(af, ff)])
        anchor = wall[0][0]
        wg = linear_gradient(shape, anchor, (anchor[0] + sx, anchor[1] + sy), [
            (0.00, rgba(pal['specular'], 0.85)), (0.30, rgba(pal['primary'], 1)),
            (1.00, rgba(pal['shadow'], 1))])
        wm = Image.new('L', shape, 0)
        wd = ImageDraw.Draw(wm)
        for q in wall:
            wd.polygon([(float(x), float(y)) for x, y in q], fill=255)
        wg[..., 3] *= np.asarray(wm, np.float32) / 255.0
        over(face_layer, wg)

        contact = Image.new('L', shape, 0)
        cd = ImageDraw.Draw(contact)
        for q in wall:
            cd.line([tuple(map(float, q[3])), tuple(map(float, q[2]))],
                    fill=255, width=max(1, int(DENSITY * SUPERSAMPLE / 2)))
        cl = np.zeros(shape[::-1] + (4,), np.float32)
        cl[..., 3] = np.asarray(contact, np.float32) / 255.0 * 0.35
        over(face_layer, cl)

    c = pose.corners
    fx = [c[k][0] for k in ('fTL', 'fTR', 'fBR', 'fBL')]
    fy = [c[k][1] for k in ('fTL', 'fTR', 'fBR', 'fBL')]
    top, bottom = min(fy), max(fy)

    gloss = linear_gradient(shape, (0, top), (0, bottom), [
        (0.00, [1, 1, 1, 0.28]), (0.10, [1, 1, 1, 0.20]), (0.30, [1, 1, 1, 0.09]),
        (0.44, TRANSPARENT), (1.00, TRANSPARENT)])
    over(face_layer, gloss)
    shimmer = linear_gradient(shape, (min(fx), top + (bottom - top) * 0.1), (max(fx), bottom), [
        (0.00, TRANSPARENT), (0.38, TRANSPARENT), (0.48, [1, 1, 1, 0.10]),
        (0.56, [1, 1, 1, 0.10]), (0.66, TRANSPARENT), (1.00, TRANSPARENT)])
    over(face_layer, shimmer)
    weight = linear_gradient(shape, (0, top), (0, bottom), [
        (0.80, TRANSPARENT), (1.00, [0, 0, 0, 0.16])])
    over(face_layer, weight)

    face_layer[..., 3] *= mask
    over(canvas, face_layer)

    if ambient:
        edge = Image.new('L', shape, 0)
        ImageDraw.Draw(edge).line(
            [(float(x), float(y)) for x, y in pose.outline()] + [pose.outline()[0]],
            fill=255, width=max(1, int(DENSITY * SUPERSAMPLE / 2)))
        stroke = np.zeros(shape[::-1] + (4,), np.float32)
        stroke[..., :3] = 1.0
        stroke[..., 3] = np.asarray(edge, np.float32) / 255.0 * 0.30
        over(canvas, stroke)


def render(art_path, out_path):
    art = Image.open(art_path)
    edge = min(art.size)
    art = art.crop(((art.width - edge) // 2, (art.height - edge) // 2,
                    (art.width - edge) // 2 + edge, (art.height - edge) // 2 + edge))
    art = art.resize((1024, 1024), Image.LANCZOS)
    pal = cube_palette(art)

    d = DENSITY * SUPERSAMPLE
    face = FACE_DP * d
    width = int((FACE_DP + MARGIN_DP * 2) * d)
    height = int((FACE_DP + MARGIN_DP * 2 + REFLECTION_GAP_DP
                  + FACE_DP * REFLECTION_COMPRESS + MARGIN_DP) * d)
    shape = (width, height)

    pose = Pose(face / 2, face / 2, THICKNESS_DP * d / 2, CORNER_DP * d,
                BASE_TILT_X, BASE_TILT_Y, BASE_ROLL,
                face * CAMERA_DISTANCE_FACTOR, width / 2, MARGIN_DP * d + face / 2)

    canvas = blank(shape)
    draw_body(canvas, pose, art, pal, ambient=True)

    line = pose.bounds()[3] + REFLECTION_GAP_DP * d
    refl = blank(shape)
    draw_body(refl, pose.mirrored_below(line, REFLECTION_COMPRESS), art, pal, ambient=False)
    ys = np.mgrid[0:height, 0:width][0].astype(np.float32)
    t = np.clip((ys - line) / max(height - line, 1e-6), 0, 1)
    fade = np.clip(0.18 * (1 - t / 0.55), 0, 1)
    fade[ys < line] = 0
    refl[..., 3] *= fade
    over(canvas, refl)

    # A dark surface so the glass and bloom read the way they will over a wallpaper.
    bg = np.zeros((height, width, 4), np.float32)
    bg[..., :3] = np.linspace(0.10, 0.03, height)[:, None, None]
    bg[..., 3] = 1.0
    out = over(bg, canvas)
    img = Image.fromarray((np.clip(out[..., :3], 0, 1) * 255).astype(np.uint8))
    if SUPERSAMPLE > 1:
        img = img.resize((width // SUPERSAMPLE, height // SUPERSAMPLE), Image.LANCZOS)
    img.save(out_path)
    print(f"wrote {out_path}  ({img.width}x{img.height} from {width}x{height})  "
          f"visible faces: {pose.visible_faces()}")


if __name__ == '__main__':
    render(sys.argv[1] if len(sys.argv) > 1 else 'art.png',
           sys.argv[2] if len(sys.argv) > 2 else 'cube_preview.png')
