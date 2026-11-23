"""Janelas do radar no PC (PySide6): camada de marcadores, botao e painel de filtros."""
import unicodedata

from PySide6.QtCore import QPoint, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QIcon, QPainter, QPainterPath, QPen, QPixmap
from PySide6.QtWidgets import (QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QListWidget,
                               QListWidgetItem, QPushButton, QScrollArea, QVBoxLayout, QWidget)

from .engine import CAP_H

GOLD, GOLD_L, BG, BAR, TEXT, MUTED = "#D4AF37", "#FFE6A8", "#12141F", "#1E2235", "#E8E8F0", "#9A9FB5"

PRIORITY = ["Inimigos", "Animal", "Especialidade da Área", "Inventário / Materiais", "Minérios",
            "Madeira", "Pescar", "Baú", "Itens Especiais"]

OVERLAY_FLAGS = (Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
                 | Qt.WindowDoesNotAcceptFocus | Qt.NoDropShadowWindowHint)


def norm(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()


class IconCache:
    def __init__(self, data):
        self.d = data
        self.raw = {}

    def get(self, cid):
        if cid not in self.raw:
            p = self.d.icon_path(cid)
            self.raw[cid] = QPixmap(p) if p else QPixmap()
        return self.raw[cid]


class Overlay(QWidget):
    """Camada transparente sobre a janela do jogo; o mouse passa direto para o jogo."""

    def __init__(self, data, icons):
        super().__init__(None, OVERLAY_FLAGS | Qt.WindowTransparentForInput)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.d = data
        self.icons = icons
        self.layers = []           # [(id, np.array Nx2)]
        self.mode = None           # "map" | "mini" | None
        self.state = None
        self.drawn_map = []        # (x, y, r) em px da captura, para mascarar
        self.drawn_mini = []
        self.mini_geom = None
        self.ui_rects = []          # areas da interface do jogo (sem marcadores), fracoes de H

    def set_layers(self, ids):
        self.layers = [(i, self.d.points[i]) for i in ids if i in self.d.points]
        self.update()

    def show_map(self, pose):
        self.mode, self.state = "map", pose
        self.update()

    def show_mini(self, px, py, k, geom):
        self.mode, self.state, self.mini_geom = "mini", (px, py, k), geom
        self.update()

    def clear(self):
        if self.mode is not None:
            self.mode = self.state = None
            self.drawn_map, self.drawn_mini = [], []
            self.update()

    def paintEvent(self, _):
        if not self.mode or not self.layers:
            self.drawn_map, self.drawn_mini = [], []
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        W, H = self.width(), self.height()
        cap = CAP_H / H                       # tela -> captura
        drawn = []
        if self.mode == "map":
            pose = self.state
            per = pose.k * cap                # px do mapa por px da tela
            cx0, cy0, rmax, r = W / 2, H / 2, None, max(9, 0.021 * H)
            ox, oy = pose.cx, pose.cy
            alpha = 1.0
        else:
            px, py, k = self.state
            mcx, mcy, mr = self.mini_geom
            per = k * cap
            cx0, cy0, rmax, r = mcx * H, mcy * H, mr * H * 0.93, max(5, 0.010 * H)
            ox, oy = px, py
            alpha = 0.65                      # translucido: o minimapa continua visivel
        p.setOpacity(alpha)
        bg = QBrush(QColor(18, 20, 31, 230 if self.mode == "map" else 140))
        pen = QPen(QColor(GOLD), max(1.0, r * 0.14))
        blocked = []
        if self.mode == "map":
            for side, a, y0, c, y1 in self.ui_rects:
                x0, x1 = (a * H, c * H) if side == "L" else (W - a * H, W - c * H)
                blocked.append((x0, y0 * H, x1, y1 * H))
        n = 0
        for cid, xy in self.layers:
            pm = self.icons.get(cid)
            sx = (xy[:, 0] - ox) / per + cx0
            sy = (xy[:, 1] - oy) / per + cy0
            for x, y in zip(sx, sy):
                if x < -r or y < -r or x > W + r or y > H + r:
                    continue
                if rmax is not None and (x - cx0) ** 2 + (y - cy0) ** 2 > (rmax - r * 0.6) ** 2:
                    continue
                if any(bx0 <= x <= bx1 and by0 <= y <= by1 for bx0, by0, bx1, by1 in blocked):
                    continue
                p.setPen(Qt.NoPen)
                p.setBrush(bg)
                p.drawEllipse(QRectF(x - r, y - r, 2 * r, 2 * r))
                if not pm.isNull():
                    ir = r * 0.82
                    p.drawPixmap(QRectF(x - ir, y - ir, 2 * ir, 2 * ir), pm, QRectF(pm.rect()))
                p.setBrush(Qt.NoBrush)
                p.setPen(pen)
                p.drawEllipse(QRectF(x - r, y - r, 2 * r, 2 * r))
                drawn.append((x * cap, y * cap, (r + 3) * cap))
                n += 1
                if n >= 900:
                    break
            if n >= 900:
                break
        p.end()
        if self.mode == "map":
            self.drawn_map, self.drawn_mini = drawn, []
        else:
            self.drawn_mini, self.drawn_map = drawn, []


class RadarButton(QWidget):
    clicked = Signal()
    moved = Signal(float)

    def __init__(self):
        super().__init__(None, OVERLAY_FLAGS)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Teyvat Radar: filtros")
        self.count = 0
        self._press = None
        self._moved = False

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        s = self.width()
        p.setBrush(QColor(30, 34, 53, 235))
        p.setPen(QPen(QColor(GOLD), max(2, s * 0.05)))
        p.drawEllipse(QRectF(2, 2, s - 4, s - 4))
        p.setPen(QColor(GOLD_L))
        f = QFont("Segoe UI Symbol")
        f.setPixelSize(int(s * (0.42 if self.count else 0.5)))
        p.setFont(f)
        p.drawText(self.rect(), Qt.AlignCenter, str(self.count) if self.count else "⚔")

    def mousePressEvent(self, e):
        self._press = e.globalPosition().toPoint()
        self._start = self.pos()
        self._moved = False

    def mouseMoveEvent(self, e):
        if self._press is None:
            return
        d = e.globalPosition().toPoint() - self._press
        if abs(d.y()) > 6:
            self._moved = True
        if self._moved:
            self.move(self._start.x(), self._start.y() + d.y())

    def mouseReleaseEvent(self, e):
        if self._moved:
            self.moved.emit(float(self.y()))
        else:
            self.clicked.emit()
        self._press = None


STYLE = """
QWidget#panel { background: %(BG)s; border: 2px solid %(GOLD)s; border-radius: 14px; }
QLabel { color: %(TEXT)s; }
QLabel#title { color: %(GOLD_L)s; font-size: 17px; font-weight: 600; }
QLabel#muted { color: %(MUTED)s; }
QLabel#section { color: %(GOLD)s; font-size: 12px; padding-top: 8px; }
QLineEdit { background: #0B0D16; color: %(TEXT)s; border: 1px solid #333; border-radius: 8px; padding: 6px 10px; }
QListWidget { background: #161927; color: %(TEXT)s; border: none; outline: none; }
QListWidget::item { padding: 6px; border-radius: 6px; }
QListWidget::item:selected { background: #3A3220; color: %(GOLD_L)s; }
QPushButton { color: %(TEXT)s; background: transparent; border: 1px solid #88D4AF37; border-radius: 10px; padding: 8px 16px; }
QPushButton:hover { background: #262B44; }
QPushButton#primary { background: %(GOLD)s; color: #1A1405; border: none; font-weight: 600; }
QPushButton#close { border: none; font-size: 16px; padding: 4px 10px; }
QScrollArea { border: none; background: transparent; }
QFrame#item { background: #1B1F31; border: 1px solid #33FFFFFF; border-radius: 10px; }
QFrame#item[on="true"] { background: #3A3220; border: 2px solid %(GOLD)s; }
""" % dict(BG=BG, GOLD=GOLD, GOLD_L=GOLD_L, TEXT=TEXT, MUTED=MUTED)


class ItemTile(QFrame):
    toggled = Signal(int)

    def __init__(self, cat, pixmap, on):
        super().__init__()
        self.setObjectName("item")
        self.cid = cat.id
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(108, 100)
        v = QVBoxLayout(self)
        v.setContentsMargins(4, 6, 4, 4)
        v.setSpacing(2)
        ic = QLabel()
        ic.setAlignment(Qt.AlignCenter)
        if not pixmap.isNull():
            ic.setPixmap(pixmap.scaled(42, 42, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        v.addWidget(ic)
        t = QLabel(cat.name)
        t.setWordWrap(True)
        t.setAlignment(Qt.AlignCenter)
        t.setStyleSheet("font-size: 11px;")
        v.addWidget(t)
        self.set_on(on)

    def set_on(self, on):
        self.setProperty("on", "true" if on else "false")
        self.style().unpolish(self)
        self.style().polish(self)

    def mousePressEvent(self, e):
        self.toggled.emit(self.cid)


class FilterPanel(QWidget):
    applied = Signal(list)
    closed = Signal()

    def __init__(self, data, icons, selected):
        super().__init__(None, Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.d = data
        self.icons = icons
        self.sel = set(selected)
        self.tiles = {}
        cats = [c for n in PRIORITY for c in data.roots if c.name == n]
        cats += [c for c in data.roots if c not in cats]
        self.cats = cats

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        box = QWidget()
        box.setObjectName("panel")
        box.setStyleSheet(STYLE)
        outer.addWidget(box)
        v = QVBoxLayout(box)
        v.setContentsMargins(14, 10, 14, 12)

        head = QHBoxLayout()
        t = QLabel("Filtros do Radar")
        t.setObjectName("title")
        head.addWidget(t)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Buscar (ex.: Hilichurl, Lírio Calla)")
        self.search.textChanged.connect(self._on_search)
        head.addWidget(self.search, 1)
        x = QPushButton("✕")
        x.setObjectName("close")
        x.clicked.connect(self.closed.emit)
        head.addWidget(x)
        v.addLayout(head)

        body = QHBoxLayout()
        self.catlist = QListWidget()
        self.catlist.setIconSize(QSize(26, 26))
        self.catlist.setFixedWidth(250)
        for c in cats:
            it = QListWidgetItem(c.name)
            ip = data.icon_for(c)
            if ip:
                it.setIcon(QIcon(ip))
            it.setData(Qt.UserRole, c.id)
            self.catlist.addItem(it)
        self.catlist.currentRowChanged.connect(self._on_cat)
        body.addWidget(self.catlist)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        body.addWidget(self.scroll, 1)
        v.addLayout(body, 1)

        foot = QHBoxLayout()
        clear = QPushButton("Limpar filtros")
        clear.clicked.connect(self._clear)
        foot.addWidget(clear)
        self.count = QLabel()
        self.count.setObjectName("muted")
        self.count.setAlignment(Qt.AlignCenter)
        foot.addWidget(self.count, 1)
        ok = QPushButton("Aplicar")
        ok.setObjectName("primary")
        ok.clicked.connect(lambda: self.applied.emit(sorted(self.sel)))
        foot.addWidget(ok)
        v.addLayout(foot)

        self.catlist.setCurrentRow(0)
        self._refresh_counts()

    # --------------------------------------------------------- conteudo
    def _grid(self, sections):
        w = QWidget()
        w.setStyleSheet(STYLE)
        lay = QVBoxLayout(w)
        lay.setContentsMargins(6, 0, 6, 6)
        cols = max(2, (self.scroll.viewport().width() - 20) // 114) if self.scroll.viewport().width() > 200 else 6
        self.tiles = {}
        for title, items in sections:
            if title:
                s = QLabel(title)
                s.setObjectName("section")
                lay.addWidget(s)
            g = QGridLayout()
            g.setSpacing(6)
            for i, c in enumerate(items):
                tile = ItemTile(c, self.icons.get(c.id), c.id in self.sel)
                tile.toggled.connect(self._toggle)
                self.tiles[c.id] = tile
                g.addWidget(tile, i // cols, i % cols)
            lay.addLayout(g)
        lay.addStretch(1)
        self.scroll.setWidget(w)

    def _on_cat(self, row):
        if row < 0:
            return
        c = self.cats[row]
        grouped = any(ch.children for ch in c.children)
        if not grouped:
            self._grid([(None, c.leaves())])
        else:
            loose = [ch for ch in c.children if not ch.children]
            secs = [(None, loose)] if loose else []
            secs += [(ch.name, ch.leaves()) for ch in c.children if ch.children]
            self._grid(secs)

    def _on_search(self, text):
        q = norm(text.strip())
        if len(q) < 2:
            self._on_cat(self.catlist.currentRow())
            return
        found = [c for r in self.cats for c in r.leaves() if q in norm(c.name)]
        self._grid([("%d resultado(s)" % len(found) if found else "Nada encontrado", found)])

    def _toggle(self, cid):
        if cid in self.sel:
            self.sel.discard(cid)
        else:
            self.sel.add(cid)
        if cid in self.tiles:
            self.tiles[cid].set_on(cid in self.sel)
        self._refresh_counts()

    def _clear(self):
        self.sel.clear()
        for cid, t in self.tiles.items():
            t.set_on(False)
        self._refresh_counts()

    def _refresh_counts(self):
        for i, c in enumerate(self.cats):
            n = sum(1 for l in c.leaves() if l.id in self.sel)
            self.catlist.item(i).setText("%s  • %d" % (c.name, n) if n else c.name)
        self.count.setText("%d item(ns) marcado(s)" % len(self.sel) if self.sel else "Nenhum item marcado")

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.closed.emit()
        else:
            super().keyPressEvent(e)
