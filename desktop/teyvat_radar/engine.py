"""Reconhecimento do mapa e do minimapa do Genshin (versao PC).

Mesma tecnica do app Android:
  * mapa aberto: o X de fechar e a engrenagem nos lugares de sempre;
  * escala do mapa: posicao do controle na barra de zoom;
  * posicao: pontos ORB da tela casados com a "impressao digital" do mapa oficial;
  * minimapa: o recorte do minimapa casado com o mapa de referencia.

Tudo e medido em fracoes da ALTURA da janela do jogo (a interface do Genshin escala
pela altura e fica presa nas bordas), entao funciona em qualquer resolucao.
"""
import json
import os
import time
from collections import Counter

import cv2
import numpy as np

CAP_H = 640
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")


def load_spec():
    with open(os.path.join(ASSETS, "spec_pc.json"), encoding="utf-8") as f:
        spec = json.load(f)
    tpl = {k: cv2.imread(os.path.join(ASSETS, "tpl_pc_%s.png" % k), cv2.IMREAD_GRAYSCALE)
           for k in ("close_x", "gear", "knob")}
    return spec, tpl


def to_cap(gray):
    """Normaliza uma captura (cinza) para altura CAP_H."""
    h, w = gray.shape[:2]
    if h == CAP_H:
        return gray
    return cv2.resize(gray, (max(1, round(w * CAP_H / h)), CAP_H), interpolation=cv2.INTER_AREA)


class Pose:
    __slots__ = ("cx", "cy", "k", "votes")

    def __init__(self, cx, cy, k, votes=0):
        self.cx, self.cy, self.k, self.votes = cx, cy, k, votes


class MapLocator:
    def __init__(self, data, spec, tpl):
        self.d = data
        self.spec = spec
        self.tpl = tpl
        self.factor = data.feat_factor
        self.orb = cv2.ORB_create(3000, 1.3, 3, 31, 0, 2, 0, 31, 8)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)
        # grade espacial para busca local
        self.cell = 512
        xy = data.feat_xy
        cx = (xy[:, 0] // self.cell).astype(np.int32)
        cy = (xy[:, 1] // self.cell).astype(np.int32)
        self.gw = int(cx.max()) + 1
        self.cell_of = cy * self.gw + cx

    # ------------------------------------------------------ mapa aberto
    def _region(self, g, r, t):
        H, W = g.shape
        x = W - r["x"] * H if r["anchor"] == "right" else r["x"] * H
        x0, y0 = int(max(0, x - 0.03 * H)), int(max(0, (r["y"] - 0.02) * H))
        x1, y1 = int(min(W, x + (r["w"] + 0.03) * H)), int(min(H, (r["y"] + r["h"] + 0.02) * H))
        roi = g[y0:y1, x0:x1]
        if roi.shape[0] < t.shape[0] or roi.shape[1] < t.shape[1]:
            return 0.0
        return float(cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED).max())

    def map_open_score(self, g):
        r = self.spec["regions"]
        return min(self._region(g, r["close_x"], self.tpl["close_x"]),
                   self._region(g, r["gear"], self.tpl["gear"]))

    # ----------------------------------------------------------- escala
    def scale_from_knob(self, g):
        k = self.spec["knob"]
        H = g.shape[0]
        b0, b1 = int(k["band"][0] * H), int(k["band"][1] * H)
        band = g[b0:b1, int(k["x0"] * H):int(k["x1"] * H)]
        t = self.tpl["knob"]
        if band.shape[0] < t.shape[0] or band.shape[1] < t.shape[1]:
            return -1
        r = cv2.matchTemplate(band, t, cv2.TM_CCOEFF_NORMED)
        _, mv, _, ml = cv2.minMaxLoc(r)
        if mv < 0.4:
            return -1
        y = (ml[1] + k["half"] * H + b0) / H
        s640 = k["scale_a"] + k["scale_b"] * (y - k["scale_y0"])
        return max(0.15, s640) * CAP_H / H      # px do mapa (zoom 0) por px da captura

    # ----------------------------------------------------------- posicao
    def ui_mask(self, H, W):
        m = np.full((H, W), 255, np.uint8)
        for side, a, y0, c, y1 in self.spec["ui_mask"]:
            x0, x1 = (a * H, c * H) if side == "L" else (W - a * H, W - c * H)
            m[int(y0 * H):int(y1 * H), int(x0):int(x1)] = 0
        return m

    def locate(self, g, mask, k, prior=None):
        f = self.factor
        fct = k / f
        small = cv2.resize(g, None, fx=fct, fy=fct, interpolation=cv2.INTER_AREA)
        sm = cv2.resize(mask, (small.shape[1], small.shape[0]), interpolation=cv2.INTER_NEAREST)
        kp, des = self.orb.detectAndCompute(small, sm)
        if des is None or len(des) < 10:
            return None
        idx = None
        train = self.d.feat_des
        if prior is not None:
            hw, hh = small.shape[1] * 1.5 + 200, small.shape[0] * 1.5 + 200
            px, py = prior.cx / f, prior.cy / f
            xy = self.d.feat_xy
            idx = np.nonzero((np.abs(xy[:, 0] - px) < hw) & (np.abs(xy[:, 1] - py) < hh))[0]
            if len(idx) < 50:
                return None
            train = self.d.feat_des[idx]
        ms = self.bf.knnMatch(des, train, k=2)
        good = [a for a, b in (m for m in ms if len(m) == 2) if a.distance < 0.85 * b.distance]
        if len(good) < 5:
            return None
        q = np.float32([kp[a.queryIdx].pt for a in good])
        ti = np.array([a.trainIdx for a in good])
        r = self.d.feat_xy[idx[ti] if idx is not None else ti]
        T = r - q
        B = 6
        C = Counter(map(tuple, np.round(T / B).astype(int)))
        best = max(C, key=lambda c: sum(C.get((c[0] + a, c[1] + b), 0) for a in (-1, 0, 1) for b in (-1, 0, 1)))
        votes = sum(C.get((best[0] + a, best[1] + b), 0) for a in (-1, 0, 1) for b in (-1, 0, 1))
        if votes < (5 if prior is not None else 6):
            return None
        inl = np.linalg.norm(T - np.array(best) * B, axis=1) < 2 * B
        sc, t = 1.0, np.median(T[inl], axis=0)
        for _ in range(3):                     # ajuste fino da escala
            qq, rr = q[inl], r[inl]
            if len(qq) < 8:
                break
            qm, rm = qq.mean(0), rr.mean(0)
            den = ((qq - qm) ** 2).sum()
            if den <= 0:
                break
            s2 = ((qq - qm) * (rr - rm)).sum() / den
            if not 0.75 < s2 < 1.33:
                break
            sc, t = s2, rm - s2 * qm
            inl = np.linalg.norm(q * sc + t - r, axis=1) < 8
        cx, cy = (np.array([small.shape[1] / 2, small.shape[0] / 2]) * sc + t) * f
        return Pose(float(cx), float(cy), k * sc, int(votes))


class MiniTracker:
    """Posicao do personagem pelo minimapa. A escala do minimapa e aprendida sozinha
    (calibrate) na primeira vez que o mapa grande e aberto e fechado."""

    def __init__(self, data, spec, k=None):
        m = spec["minimap"]
        self.cx, self.cy, self.r, self.tf = m["cx"], m["cy"], m["r"], m["tpl"]
        self.min_corr = m["min_corr"]
        self.k = k                      # px do mapa (zoom 0) por px numa tela de altura 640
        self.z2 = data.z2
        self.px = self.py = None
        self.corr = 0.0
        self.misses = 0

    def crop(self, g):
        H = g.shape[0]
        h = int(round(self.r * self.tf * H))
        c0x, c0y = int(round(self.cx * H)), int(round(self.cy * H))
        if c0x - h < 0 or c0y - h < 0:
            return None
        return g[c0y - h:c0y + h, c0x - h:c0x + h]

    def _match(self, crop, k, px, py, R):
        """Casa o recorte com o mapa na escala da TELA (bom para minimapas muito ampliados).
        R: raio de busca em px da captura. Retorna (corr, x, y)."""
        h = crop.shape[0] / 2
        half0 = (h + R) * k                      # meia janela em px do zoom 0
        x0, y0 = int((px - half0) / 4), int((py - half0) / 4)
        x1, y1 = int((px + half0) / 4) + 1, int((py + half0) / 4) + 1
        if x0 < 0 or y0 < 0 or x1 > self.z2.shape[1] or y1 > self.z2.shape[0]:
            return -1, px, py
        win = self.z2[y0:y1, x0:x1]
        f = 4.0 / k                               # referencia -> px da captura
        win = cv2.resize(win, None, fx=f, fy=f, interpolation=cv2.INTER_LINEAR if f > 1 else cv2.INTER_AREA)
        if win.shape[0] <= crop.shape[0] or win.shape[1] <= crop.shape[1]:
            return -1, px, py
        res = cv2.matchTemplate(win, crop, cv2.TM_CCOEFF_NORMED)
        _, mv, _, ml = cv2.minMaxLoc(res)
        # (ml + meia) em px da janela redimensionada -> px do zoom 0
        x = x0 * 4 + (ml[0] + crop.shape[1] / 2) * k
        y = y0 * 4 + (ml[1] + crop.shape[0] / 2) * k
        return float(mv), x, y

    def hint(self, x, y):
        self.px, self.py, self.misses = x, y, 0

    def update(self, g):
        if self.k is None or self.px is None:
            return False
        c = self.crop(g)
        if c is None:
            return False
        k = self.k * CAP_H / g.shape[0]
        corr, x, y = self._match(c, k, self.px, self.py, 40)
        if corr < self.min_corr and self.misses >= 2:
            corr, x, y = self._match(c, k, self.px, self.py, 140)
        self.corr = corr
        if corr >= self.min_corr:
            self.px, self.py, self.misses = x, y, 0
            return True
        self.misses += 1
        if self.misses >= 12:
            self.px = self.py = None
        return False

    def calibrate(self, g, px, py):
        """Descobre a escala do minimapa sabendo onde o personagem esta (px, py)."""
        c = self.crop(g)
        if c is None:
            return None
        scores = []
        for k in np.arange(0.5, 4.01, 0.05):
            corr, x, y = self._match(c, float(k), px, py, 30)
            scores.append((corr, float(k), x, y))
        scores.sort(reverse=True)
        best = scores[0]
        med = float(np.median([s[0] for s in scores]))
        if best[0] < 0.55 or best[0] - med < 0.12:
            return None
        fine = max(((self._match(c, k, px, py, 20) + (k,)) for k in np.arange(best[1] - 0.05, best[1] + 0.051, 0.01)),
                   key=lambda t: t[0])
        corr, x, y, k = fine
        self.k = k
        self.px, self.py, self.misses = x, y, 0
        return self.k, corr


class Engine:
    """Estado do radar entre quadros. process() recebe a captura da janela do jogo."""

    def __init__(self, data, config):
        self.spec, tpl = load_spec()
        self.d = data
        self.cfg = config
        self.loc = MapLocator(data, self.spec, tpl)
        self.mini = MiniTracker(data, self.spec, config.get("mini_k"))
        self.map_open = False
        self.open_streak = self.closed_streak = 0
        self.pose = None
        self.just_opened = False
        self.player_at_open = None        # (x, y, hora) da ultima abertura do mapa
        self.calib_tries = 0
        self.status = {}

    def process(self, gray, erase_map=None, erase_mini=None):
        g = to_cap(gray)
        H, W = g.shape
        out = {"map_open": False, "pose": None, "mini": None, "cap_h": H}
        if W < H:
            self._set_open(False)
            return out
        score = self.loc.map_open_score(g)
        self.status["map_score"] = round(score, 2)
        is_open = score > 0.75
        if is_open:
            self.open_streak += 1
            self.closed_streak = 0
        else:
            self.closed_streak += 1
            self.open_streak = 0
        if not self.map_open and self.open_streak >= 2:
            self._set_open(True)
        if self.map_open and self.closed_streak >= 2:
            self._set_open(False)
        out["map_open"] = self.map_open

        if self.map_open and is_open:
            k = self.loc.scale_from_knob(g)
            self.status["map_k"] = round(k, 3)
            if k > 0:
                mask = self.loc.ui_mask(H, W)
                for x, y, r in (erase_map or []):
                    cv2.circle(mask, (int(x), int(y)), int(r), 0, -1)
                p = None
                if self.pose is not None:
                    p = self.loc.locate(g, mask, k, self.pose)
                if p is None:
                    p = self.loc.locate(g, mask, k, None)
                if p is not None:
                    self.pose = p
                    if self.just_opened:
                        self.just_opened = False
                        self.player_at_open = (p.cx, p.cy, time.time())
                        self.mini.hint(p.cx, p.cy)
                self.status["map_pos"] = "ok (%d votos)" % p.votes if p else "não encontrada"
            out["pose"] = self.pose
            return out

        if not is_open and not self.map_open:
            if erase_mini:
                g = g.copy()
                mean = int(g.mean())
                for x, y, r in erase_mini:
                    cv2.circle(g, (int(x), int(y)), int(r), mean, -1)
            if self.mini.k is None:
                self._try_calibrate(g)
            elif self.mini.update(g):
                out["mini"] = (self.mini.px, self.mini.py, self.mini.k * CAP_H / H)
            self.status["mini"] = ("calibrando" if self.mini.k is None else
                                   "%s (semelhança %.2f)" % ("rastreando" if out["mini"] else "procurando", self.mini.corr))
        return out

    def _try_calibrate(self, g):
        pa = self.player_at_open
        if pa is None or time.time() - pa[2] > 20 or self.calib_tries > 30:
            return
        self.calib_tries += 1
        r = self.mini.calibrate(g, pa[0], pa[1])
        if r:
            self.cfg["mini_k"] = r[0]
            self.cfg.save()
            self.status["mini_calib"] = "escala %.2f (semelhança %.2f)" % r

    def recalibrate(self):
        self.mini.k = None
        self.calib_tries = 0
        self.cfg["mini_k"] = None
        self.cfg.save()

    def _set_open(self, v):
        if v == self.map_open:
            return
        self.map_open = v
        if v:
            self.just_opened = True
            self.calib_tries = 0
        else:
            self.pose = None
