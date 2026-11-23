"""Teyvat Radar para PC: fica na bandeja do Windows e desenha os itens filtrados
por cima do mapa e do minimapa do Genshin."""
import os
import sys
import threading
import time
import traceback

# coordenadas Qt = pixels reais (casam com as do Windows e da captura)
os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "0")

import cv2
import numpy as np

from . import VERSION, win
from .config import Config
from .data import Data, app_dir, is_ready, update_if_needed
from .engine import CAP_H, Engine

from PySide6.QtCore import QObject, QThread, QTimer, Signal, Qt
from PySide6.QtGui import QAction, QIcon
from PySide6.QtWidgets import (QApplication, QMenu, QMessageBox, QProgressDialog, QSystemTrayIcon)

from .ui import FilterPanel, IconCache, Overlay, RadarButton

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
LOG = os.path.join(app_dir(), "radar.log")


def log(msg):
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + msg + "\n")
    except Exception:
        pass


class Worker(QObject):
    """Loop de captura e reconhecimento (thread separada)."""
    result = Signal(object, object)       # (saida do engine, retangulo do jogo)
    hidden = Signal()

    def __init__(self, engine, ui_state):
        super().__init__()
        self.engine = engine
        self.ui = ui_state
        self.running = True

    def run(self):
        import mss
        sct = mss.mss()
        while self.running:
            t0 = time.time()
            try:
                self.step(sct)
            except Exception:
                log(traceback.format_exc())
                time.sleep(1)
            time.sleep(max(0.03, 0.25 - (time.time() - t0)))

    def step(self, sct):
        hwnd = win.find_game()
        if not hwnd:
            self.hidden.emit()
            time.sleep(1.0)
            return
        fg = win.foreground()
        if fg != hwnd and fg not in self.ui["our_hwnds"]:
            self.hidden.emit()                  # outro programa na frente
            time.sleep(0.4)
            return
        if self.ui["panel_open"] or not self.ui["enabled"]:
            return
        rect = win.client_rect(hwnd)
        if not rect:
            self.hidden.emit()
            return
        x, y, w, h = rect
        shot = np.asarray(sct.grab({"left": x, "top": y, "width": w, "height": h}))
        gray = cv2.cvtColor(shot, cv2.COLOR_BGRA2GRAY)
        out = self.engine.process(gray, self.ui["erase_map"], self.ui["erase_mini"])
        self.result.emit(out, rect)


class RadarApp(QObject):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.cfg = Config()
        self.data = Data()
        self.engine = Engine(self.data, self.cfg)
        self.icons = IconCache(self.data)
        self.overlay = Overlay(self.data, self.icons)
        self.overlay.set_layers(self.cfg["selected"])
        self.overlay.ui_rects = self.engine.spec["ui_mask"] + [["R", 0.40, 0, 0, 0.12]]
        self.button = RadarButton()
        self.button.count = len(self.cfg["selected"])
        self.button.clicked.connect(self.open_panel)
        self.button.moved.connect(self.on_button_moved)
        self.panel = None
        self.rect = None
        self.ui = {"our_hwnds": set(), "panel_open": False, "enabled": True,
                   "erase_map": [], "erase_mini": []}
        self.thread = QThread()
        self.worker = Worker(self.engine, self.ui)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.result.connect(self.on_result)
        self.worker.hidden.connect(self.hide_all)
        self.setup_tray()
        self.thread.start()

    # ---------------------------------------------------------- bandeja
    def setup_tray(self):
        icon = QIcon(os.path.join(ASSETS, "icon.png"))
        self.app.setWindowIcon(icon)
        self.tray = QSystemTrayIcon(icon)
        self.tray.setToolTip("Teyvat Radar %s" % VERSION)
        m = QMenu()
        self.act_on = QAction("Radar ligado", checkable=True, checked=True)
        self.act_on.toggled.connect(self.set_enabled)
        m.addAction(self.act_on)
        self.act_mini = QAction("Mostrar no minimapa", checkable=True, checked=bool(self.cfg["minimap"]))
        self.act_mini.toggled.connect(self.set_minimap)
        m.addAction(self.act_mini)
        a = QAction("Recalibrar minimapa", self)
        a.triggered.connect(self.recalibrate)
        m.addAction(a)
        a = QAction("Diagnóstico", self)
        a.triggered.connect(self.show_status)
        m.addAction(a)
        m.addSeparator()
        a = QAction("Sair", self)
        a.triggered.connect(self.quit)
        m.addAction(a)
        self.menu = m
        self.tray.setContextMenu(m)
        self.tray.activated.connect(lambda r: self.show_status() if r == QSystemTrayIcon.Trigger else None)
        self.tray.show()
        msg = ("Abra o mapa do jogo (M): o botão aparece no canto esquerdo." if self.cfg["mini_k"] else
               "Abra o mapa (M) uma vez e feche: o radar aprende a escala do seu minimapa.")
        self.tray.showMessage("Teyvat Radar ativo", msg, icon, 6000)

    def set_enabled(self, on):
        self.ui["enabled"] = on
        if not on:
            self.hide_all()

    def set_minimap(self, on):
        self.cfg["minimap"] = on
        self.cfg.save()

    def recalibrate(self):
        self.engine.recalibrate()
        self.tray.showMessage("Teyvat Radar", "Abra o mapa (M) e feche: o radar vai reaprender a escala do minimapa.")

    def show_status(self):
        s = self.engine.status
        txt = ("Versão %s\nJogo encontrado: %s\nEscala do minimapa: %s\n\n%s" %
               (VERSION, "sim" if win.find_game() else "não (abra o Genshin)",
                "%.2f" % self.cfg["mini_k"] if self.cfg["mini_k"] else "ainda não calibrada",
                "\n".join("%s: %s" % kv for kv in s.items())))
        QMessageBox.information(None, "Teyvat Radar", txt)

    def quit(self):
        self.worker.running = False
        self.thread.quit()
        self.thread.wait(1500)
        self.tray.hide()
        self.app.quit()

    # ---------------------------------------------------------- quadros
    def place(self, w, rect):
        x, y, ww, hh = rect
        if w.geometry().getRect() != (x, y, ww, hh):
            w.setGeometry(x, y, ww, hh)

    def ensure_shown(self, w):
        if not w.isVisible():
            w.show()
            self.ui["our_hwnds"].add(int(w.winId()))

    def on_result(self, out, rect):
        if not self.ui["enabled"] or self.ui["panel_open"]:
            return
        self.rect = rect
        x, y, w, h = rect
        if out["map_open"]:
            size = max(36, int(0.062 * h))
            by = self.cfg["button_y"]
            yy = y + int((by if by is not None else 0.70) * h)
            self.button.setFixedSize(size, size)
            self.button.move(x + int(0.012 * h), min(yy, y + h - size))
            self.ensure_shown(self.button)
            self.button.raise_()
            if out["pose"] is not None and self.cfg["selected"]:
                self.place(self.overlay, rect)
                self.overlay.show_map(out["pose"])
                self.ensure_shown(self.overlay)
            else:
                self.overlay.clear()
        else:
            self.button.hide()
            if out["mini"] is not None and self.cfg["selected"] and self.cfg["minimap"]:
                m = self.engine.mini
                self.place(self.overlay, rect)
                self.overlay.show_mini(out["mini"][0], out["mini"][1], out["mini"][2], (m.cx, m.cy, m.r))
                self.ensure_shown(self.overlay)
            else:
                self.overlay.clear()
        self.ui["erase_map"] = list(self.overlay.drawn_map)
        self.ui["erase_mini"] = list(self.overlay.drawn_mini)

    def hide_all(self):
        if self.ui["panel_open"]:
            return
        self.button.hide()
        self.overlay.clear()

    def on_button_moved(self, ypos):
        if self.rect:
            self.cfg["button_y"] = (ypos - self.rect[1]) / self.rect[3]
            self.cfg.save()

    # ----------------------------------------------------------- painel
    def open_panel(self):
        if self.panel is not None or not self.rect:
            return
        x, y, w, h = self.rect
        self.panel = FilterPanel(self.data, self.icons, self.cfg["selected"])
        pw, ph = int(min(w * 0.8, max(900, w * 0.7))), int(h * 0.86)
        self.panel.setGeometry(x + (w - pw) // 2, y + (h - ph) // 2, pw, ph)
        self.panel.applied.connect(self.on_apply)
        self.panel.closed.connect(self.close_panel)
        self.ui["panel_open"] = True
        self.button.hide()
        self.overlay.clear()
        self.panel.show()
        self.panel.activateWindow()
        self.panel.raise_()
        self.ui["our_hwnds"].add(int(self.panel.winId()))
        QTimer.singleShot(50, lambda: self.panel and self.panel._on_cat(self.panel.catlist.currentRow()))

    def on_apply(self, ids):
        self.cfg["selected"] = ids
        self.cfg.save()
        self.overlay.set_layers(ids)
        self.button.count = len(ids)
        self.button.update()
        self.close_panel()
        self.tray.showMessage("Teyvat Radar", "Filtros removidos" if not ids else
                              "Mostrando %d item(ns) no mapa" % len(ids), QSystemTrayIcon.NoIcon, 2500)

    def close_panel(self):
        if self.panel is None:
            return
        self.ui["our_hwnds"].discard(int(self.panel.winId()))
        self.panel.close()
        self.panel.deleteLater()
        self.panel = None
        self.ui["panel_open"] = False
        self.engine.open_streak = self.engine.closed_streak = 0
        self.engine.map_open = False
        win.activate(win.find_game())       # devolve o foco ao jogo


def prepare_data(app):
    """Baixa/atualiza o pacote de dados com uma barra de progresso."""
    dlg = QProgressDialog("Baixando dados do mapa…", "", 0, 100)
    dlg.setCancelButton(None)
    dlg.setWindowTitle("Teyvat Radar")
    dlg.setMinimumDuration(400)
    dlg.setAutoClose(True)
    res = {}

    def job():
        try:
            res["r"] = update_if_needed(lambda pc: res.__setitem__("pc", pc))
        except Exception as e:
            res["err"] = e

    t = threading.Thread(target=job, daemon=True)
    t.start()
    while t.is_alive():
        dlg.setValue(res.get("pc", 0))
        app.processEvents()
        time.sleep(0.05)
    dlg.close()
    if "err" in res and not is_ready():
        QMessageBox.critical(None, "Teyvat Radar", "Não consegui baixar os dados do mapa.\n\n%s" % res["err"])
        return False
    return is_ready()


def selftest(folder):
    """Autoteste usado no GitHub Actions: dados, reconhecimento e janelas."""
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    lines, ok = [], True
    try:
        changed, msg = update_if_needed()
        lines.append("dados: " + msg)
        d = Data()
        cfg = Config(os.path.join(app_dir(), "selftest_cfg.json"))
        e = Engine(d, cfg)
        g = cv2.imread(os.path.join(folder, "map_liyue.png"), cv2.IMREAD_GRAYSCALE)
        for _ in range(3):
            out = e.process(g)
        p = out["pose"]
        good = out["map_open"] and p is not None and abs(p.cx - 22474) < 80 and abs(p.cy - 19150) < 80
        ok &= bool(good)
        lines.append("mapa Liyue: %s %s" % ("OK" if good else "FALHOU", (round(p.cx), round(p.cy)) if p else None))
        g = cv2.imread(os.path.join(folder, "game_natlan.png"), cv2.IMREAD_GRAYSCALE)
        for _ in range(3):
            out = e.process(g)
        good = not out["map_open"]
        ok &= good
        lines.append("jogo normal sem mapa: %s" % ("OK" if good else "FALHOU"))
        app = QApplication.instance() or QApplication(sys.argv)
        icons = IconCache(d)
        ov = Overlay(d, icons)
        ov.set_layers([c.id for c in d.roots[0].leaves()[:3]])
        ov.resize(1366, 768)
        ov.show_map(p or type("P", (), {"cx": 22474, "cy": 19150, "k": 2.7})())
        ov.grab()
        panel = FilterPanel(d, icons, [])
        panel.resize(1000, 650)
        panel.show()
        panel._on_cat(0)
        panel._on_search("hili")
        panel.grab()
        lines.append("janelas: OK")
    except Exception:
        ok = False
        lines.append("ERRO:\n" + traceback.format_exc())
    lines.append("RESULTADO: " + ("OK" if ok else "FALHOU"))
    with open(os.path.join(folder, "selftest.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        code = selftest(sys.argv[sys.argv.index("--selftest") + 1])
        sys.stdout.flush()
        os._exit(code)            # sem desmontar o Qt (evita travar na saida)
    win.set_dpi_aware()
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName("Teyvat Radar")
    if not prepare_data(app):
        sys.exit(1)
    try:
        radar = RadarApp(app)
    except Exception:
        log(traceback.format_exc())
        QMessageBox.critical(None, "Teyvat Radar", "Erro ao iniciar:\n\n" + traceback.format_exc()[-1500:])
        sys.exit(1)
    code = app.exec()
    os._exit(code)
