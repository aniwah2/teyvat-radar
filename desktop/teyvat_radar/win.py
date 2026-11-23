"""Acesso ao Windows (via ctypes, sem dependencias extras):
achar a janela do Genshin, a area dela na tela, saber se esta em primeiro plano
e esconder as nossas janelas da captura de tela."""
import ctypes
import os
import sys

IS_WIN = sys.platform == "win32"
GAME_EXES = {"genshinimpact.exe", "yuanshen.exe"}

if IS_WIN:
    from ctypes import wintypes
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    EnumWindowsProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)


def set_dpi_aware():
    """Coordenadas em pixels reais, mesmo com a escala do Windows em 125%/150%."""
    if not IS_WIN:
        return
    try:
        ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
        return
    except Exception:
        pass
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def _exe_of(hwnd):
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    h = kernel32.OpenProcess(0x1000, False, pid.value)       # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = wintypes.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value).lower()
        return ""
    finally:
        kernel32.CloseHandle(h)


_cache = {"hwnd": None}


def find_game():
    """HWND da janela principal do Genshin, ou None."""
    if not IS_WIN:
        return None
    h = _cache["hwnd"]
    if h and user32.IsWindow(h) and user32.IsWindowVisible(h):
        return h
    found = []

    def cb(hwnd, _):
        if user32.IsWindowVisible(hwnd) and _exe_of(hwnd) in GAME_EXES:
            r = wintypes.RECT()
            user32.GetClientRect(hwnd, ctypes.byref(r))
            found.append((r.right * r.bottom, hwnd))
        return True

    user32.EnumWindows(EnumWindowsProc(cb), 0)
    _cache["hwnd"] = max(found)[1] if found else None
    return _cache["hwnd"]


def client_rect(hwnd):
    """(x, y, largura, altura) da area do jogo na tela, ou None se minimizado."""
    if not IS_WIN or not hwnd or user32.IsIconic(hwnd):
        return None
    r = wintypes.RECT()
    if not user32.GetClientRect(hwnd, ctypes.byref(r)):
        return None
    p = wintypes.POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(p))
    if r.right < 200 or r.bottom < 150:
        return None
    return p.x, p.y, r.right, r.bottom


def foreground():
    return user32.GetForegroundWindow() if IS_WIN else None


def exclude_from_capture(hwnd):
    """Nossas janelas nao aparecem em capturas de tela (nem na nossa, nem em gravadores)."""
    if IS_WIN and hwnd:
        try:
            user32.SetWindowDisplayAffinity(wintypes.HWND(hwnd), 0x11)   # WDA_EXCLUDEFROMCAPTURE
        except Exception:
            pass


def activate(hwnd):
    if IS_WIN and hwnd:
        try:
            user32.SetForegroundWindow(hwnd)
        except Exception:
            pass
