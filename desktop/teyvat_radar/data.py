"""Pacote de dados do Teyvat Radar (o mesmo do app Android).

Baixado do branch "data" do repositorio, gerado pelo GitHub Actions a partir do
mapa interativo oficial da HoYoLAB.
"""
import io
import json
import os
import shutil
import struct
import time
import urllib.request
import zipfile

import cv2
import numpy as np

BASE = "https://raw.githubusercontent.com/aniwah2/teyvat-radar/data/"


def app_dir():
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~/.local/share")
    d = os.path.join(base, "TeyvatRadar")
    os.makedirs(d, exist_ok=True)
    return d


def data_dir():
    return os.path.join(app_dir(), "data")


def _get(url, progress=None):
    req = urllib.request.Request(url + ("&" if "?" in url else "?") + "t=%d" % time.time(),
                                 headers={"User-Agent": "TeyvatRadar"})
    with urllib.request.urlopen(req, timeout=60) as r:
        total = int(r.headers.get("Content-Length") or 0)
        buf = io.BytesIO()
        got = 0
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            buf.write(chunk)
            got += len(chunk)
            if progress and total:
                progress(int(got * 100 / total))
        return buf.getvalue()


def local_version():
    try:
        with open(os.path.join(data_dir(), "meta.json"), encoding="utf-8") as f:
            return json.load(f).get("pack_version", "")
    except Exception:
        return ""


def is_ready():
    d = data_dir()
    return all(os.path.exists(os.path.join(d, n)) for n in
               ("features.bin", "points.json", "categories.json", "map_z-2.jpg", "map_z-3.jpg"))


def remote_version():
    return _get(BASE + "pack_version.txt").decode("utf-8").strip()


def download(progress=None):
    """Baixa pack.zip e substitui os dados locais."""
    blob = _get(BASE + "pack.zip", progress)
    tmp = data_dir() + "_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        for name in z.namelist():
            dest = os.path.realpath(os.path.join(tmp, name))
            if not dest.startswith(os.path.realpath(tmp)):
                continue
            if name.endswith("/"):
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(z.read(name))
    shutil.rmtree(data_dir(), ignore_errors=True)
    os.replace(tmp, data_dir())


def update_if_needed(progress=None):
    """Retorna (mudou, mensagem)."""
    try:
        remote = remote_version()
    except Exception as e:
        return False, ("Sem internet; usando os dados salvos." if is_ready() else "Falha ao baixar os dados: %s" % e)
    if is_ready() and remote == local_version():
        return False, "Dados prontos (versão %s)" % remote
    download(progress)
    return True, "Dados atualizados (versão %s)" % remote


class Cat:
    __slots__ = ("id", "name", "count", "parent", "children")

    def __init__(self, o, parent):
        self.id = o["id"]
        self.name = o["name"]
        self.count = o.get("count", 0)
        self.parent = parent
        self.children = [Cat(c, self) for c in o.get("children", [])]

    def leaves(self, out=None):
        out = [] if out is None else out
        if not self.children:
            out.append(self)
        for c in self.children:
            c.leaves(out)
        return out


class Data:
    """Tudo o que o radar precisa, carregado na memoria."""

    def __init__(self, d=None):
        d = d or data_dir()
        self.dir = d
        with open(os.path.join(d, "categories.json"), encoding="utf-8") as f:
            self.roots = [Cat(o, None) for o in json.load(f)]
        self.by_id = {}
        stack = list(self.roots)
        while stack:
            c = stack.pop()
            self.by_id[c.id] = c
            stack.extend(c.children)
        with open(os.path.join(d, "points.json"), encoding="utf-8") as f:
            raw = json.load(f)
        self.points = {int(k): np.array([p[:2] for p in v], np.float32) for k, v in raw.items()}
        b = open(os.path.join(d, "features.bin"), "rb").read()
        n, self.feat_factor = struct.unpack("<ii", b[:8])
        self.feat_xy = np.frombuffer(b[8:8 + 8 * n], "<f4").reshape(-1, 2)
        self.feat_des = np.frombuffer(b[8 + 8 * n:8 + 8 * n + 32 * n], np.uint8).reshape(-1, 32)
        self.z2 = cv2.imread(os.path.join(d, "map_z-2.jpg"), cv2.IMREAD_GRAYSCALE)
        self.z3 = cv2.imread(os.path.join(d, "map_z-3.jpg"), cv2.IMREAD_GRAYSCALE)

    def icon_path(self, cid):
        p = os.path.join(self.dir, "icons", "%d.png" % cid)
        return p if os.path.exists(p) else None

    def icon_for(self, cat):
        p = self.icon_path(cat.id)
        if p:
            return p
        for c in cat.children:
            p = self.icon_for(c)
            if p:
                return p
        return None
