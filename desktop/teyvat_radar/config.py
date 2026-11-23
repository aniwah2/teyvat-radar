"""Preferencias do usuario (filtros, escala do minimapa...) em %LOCALAPPDATA%/TeyvatRadar."""
import json
import os

from .data import app_dir


class Config(dict):
    DEFAULTS = {"selected": [], "mini_k": None, "minimap": True, "button_y": None}

    def __init__(self, path=None):
        super().__init__(self.DEFAULTS)
        self.path = path or os.path.join(app_dir(), "config.json")
        try:
            with open(self.path, encoding="utf-8") as f:
                self.update(json.load(f))
        except Exception:
            pass

    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dict(self), f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)
