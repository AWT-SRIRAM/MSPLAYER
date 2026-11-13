"""
MS PLAYER - Windows 11 Desktop Edition
Powered by VideoLAN LibVLC Engine (C:\\Program Files\\VideoLAN\\VLC\\libvlc.dll)
Liquid Glass Dark Aesthetic with Full Keyboard & Mouse Ergonomics
"""

import sys
import os
import ctypes
from ctypes import c_void_p, c_int, c_int64, c_float, c_char_p, POINTER, byref, Structure
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import time
import json

# Setup DLL directory for LibVLC on Windows
VLC_DIR = r"C:\Program Files\VideoLAN\VLC"
if os.path.exists(VLC_DIR):
    try:
        os.add_dll_directory(VLC_DIR)
    except Exception:
        pass
    os.environ['PATH'] = VLC_DIR + os.pathsep + os.environ.get('PATH', '')

VLC_DLL = os.path.join(VLC_DIR, "libvlc.dll")
if not os.path.exists(VLC_DLL):
    print(f"Error: LibVLC not found at {VLC_DLL}")
    sys.exit(1)

vlc = ctypes.CDLL(VLC_DLL)

# C function declarations for LibVLC 64-bit safe pointers
vlc.libvlc_new.restype = c_void_p
vlc.libvlc_new.argtypes = [c_int, POINTER(c_char_p)]
vlc.libvlc_release.argtypes = [c_void_p]

vlc.libvlc_media_new_path.restype = c_void_p
vlc.libvlc_media_new_path.argtypes = [c_void_p, c_char_p]
vlc.libvlc_media_release.argtypes = [c_void_p]

vlc.libvlc_media_player_new_from_media.restype = c_void_p
vlc.libvlc_media_player_new_from_media.argtypes = [c_void_p]
vlc.libvlc_media_player_release.argtypes = [c_void_p]

vlc.libvlc_media_player_set_hwnd.argtypes = [c_void_p, c_void_p]
vlc.libvlc_media_player_play.restype = c_int
vlc.libvlc_media_player_play.argtypes = [c_void_p]
vlc.libvlc_media_player_pause.argtypes = [c_void_p]
vlc.libvlc_media_player_stop.argtypes = [c_void_p]
vlc.libvlc_media_player_is_playing.restype = c_int
vlc.libvlc_media_player_is_playing.argtypes = [c_void_p]

vlc.libvlc_media_player_get_time.restype = c_int64
vlc.libvlc_media_player_get_time.argtypes = [c_void_p]
vlc.libvlc_media_player_set_time.argtypes = [c_void_p, c_int64]

vlc.libvlc_media_player_get_length.restype = c_int64
vlc.libvlc_media_player_get_length.argtypes = [c_void_p]

vlc.libvlc_media_player_get_position.restype = c_float
vlc.libvlc_media_player_get_position.argtypes = [c_void_p]
vlc.libvlc_media_player_set_position.argtypes = [c_void_p, c_float]

vlc.libvlc_media_player_set_rate.restype = c_int
vlc.libvlc_media_player_set_rate.argtypes = [c_void_p, c_float]
vlc.libvlc_media_player_get_rate.restype = c_float
vlc.libvlc_media_player_get_rate.argtypes = [c_void_p]

vlc.libvlc_audio_get_volume.restype = c_int
vlc.libvlc_audio_get_volume.argtypes = [c_void_p]
vlc.libvlc_audio_set_volume.restype = c_int
vlc.libvlc_audio_set_volume.argtypes = [c_void_p, c_int]

vlc.libvlc_video_get_spu_count.restype = c_int
vlc.libvlc_video_get_spu_count.argtypes = [c_void_p]
vlc.libvlc_video_get_spu.restype = c_int
vlc.libvlc_video_get_spu.argtypes = [c_void_p]
vlc.libvlc_video_set_spu.restype = c_int
vlc.libvlc_video_set_spu.argtypes = [c_void_p, c_int]

vlc.libvlc_audio_get_track_count.restype = c_int
vlc.libvlc_audio_get_track_count.argtypes = [c_void_p]
vlc.libvlc_audio_get_track.restype = c_int
vlc.libvlc_audio_get_track.argtypes = [c_void_p]
vlc.libvlc_audio_set_track.restype = c_int
vlc.libvlc_audio_set_track.argtypes = [c_void_p, c_int]

vlc.libvlc_video_take_snapshot.restype = c_int
vlc.libvlc_video_take_snapshot.argtypes = [c_void_p, c_int, c_char_p, c_int, c_int]

vlc.libvlc_audio_equalizer_new.restype = c_void_p
vlc.libvlc_audio_equalizer_release.argtypes = [c_void_p]
vlc.libvlc_audio_equalizer_set_preamp.restype = c_int
vlc.libvlc_audio_equalizer_set_preamp.argtypes = [c_void_p, c_float]
vlc.libvlc_audio_equalizer_set_amp_at_index.restype = c_int
vlc.libvlc_audio_equalizer_set_amp_at_index.argtypes = [c_void_p, c_float, c_int]
vlc.libvlc_media_player_set_equalizer.restype = c_int
vlc.libvlc_media_player_set_equalizer.argtypes = [c_void_p, c_void_p]


def format_time(ms):
    if ms < 0:
        ms = 0
    total_sec = ms // 1000
    m, s = divmod(total_sec, 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


# Windows 11 Modern Glass Palette
BG_DARK = "#090E17"
PANEL_BG = "#101826"
CARD_BG = "#152033"
ACCENT = "#00A8FF"
CYAN = "#66D8F3"
TEXT_PRIMARY = "#FFFFFF"
TEXT_MUTED = "#8D96A7"
BORDER_COLOR = "#1F2E45"

# Equalizer Presets (10-Band Gains in dB)
EQ_PRESETS = {
    "Original": [0.0] * 10,
    "Headphones": [4.8, 11.2, 5.6, -3.2, -2.4, 1.6, 4.8, 9.6, 12.8, 14.4],
    "Earbuds Vocal": [-3.0, -2.0, -1.0, 1.5, 3.5, 4.5, 3.5, 2.0, 1.0, 0.0],
    "Deep Bass": [6.0, 5.0, 3.5, 1.5, 0.0, -1.0, 0.0, 1.0, 1.0, 1.0],
    "Cinematic Spatial": [2.0, 1.0, 0.0, -1.0, 0.0, 1.5, 2.0, 3.0, 4.5, 4.5],
    "Night Mode": [-2.0, -1.0, 0.0, 2.0, 3.0, 3.5, 2.0, 0.0, -2.0, -4.0],
    "Clarity": [-2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5, 4.0, 3.0, 1.5],
}


class MSPlayerDesktop:
    def __init__(self, root):
        self.root = root
        self.root.title("MS PLAYER - Windows 11 Edition")
        self.root.geometry("1100x680")
        self.root.minsize(800, 500)
        self.root.configure(bg=BG_DARK)

        # Apply Windows 11 Immersive Dark Titlebar
        self.apply_win11_dark_mode()

        # Initialize LibVLC Instance
        self.vlc_inst = vlc.libvlc_new(0, None)
        self.player = None
        self.equalizer = None
        self.current_file = None
        self.is_fullscreen = False
        self.is_locked = False
        self.seeking = False
        self.volume = 100
        self.current_rate = 1.0
        self.controls_visible = True
        self.last_mouse_move = time.time()
        self.active_eq_preset = "Headphones"

        self.setup_ui()
        self.setup_bindings()
        self.start_timer()

        # Check command line args
        if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
            self.open_media(sys.argv[1])

    def apply_win11_dark_mode(self):
        try:
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            # DWMWA_USE_IMMERSIVE_DARK_MODE = 20
            DWMWA_USE_IMMERSIVE_DARK_MODE = 20
            value = ctypes.c_int(1)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, byref(value), ctypes.sizeof(value)
            )
        except Exception:
            pass

    def setup_ui(self):
        # Main Layout
        self.root.rowconfigure(0, weight=1)
        self.root.columnconfigure(0, weight=1)

        self.main_container = tk.Frame(self.root, bg=BG_DARK)
        self.main_container.grid(row=0, column=0, sticky="nsew")
        self.main_container.rowconfigure(0, weight=1)
        self.main_container.rowconfigure(1, weight=0)
        self.main_container.columnconfigure(0, weight=1)

        # Video Viewport
        self.video_frame = tk.Frame(self.main_container, bg="black")
        self.video_frame.grid(row=0, column=0, sticky="nsew")

        # Minimal Top Speed & Status Capsule (Floating Overlay)
        self.capsule_label = tk.Label(
            self.video_frame,
            text=">> 2.0× Speed",
            font=("Segoe UI", 11, "bold"),
            fg=CYAN,
            bg="#162235",
            padx=14,
            pady=4,
            relief="flat",
            highlightthickness=1,
            highlightbackground="#2C4466",
        )

        # Lock Overlay Button
        self.lock_banner = tk.Button(
            self.video_frame,
            text="🔒 Tap to Unlock",
            font=("Segoe UI", 11, "bold"),
            fg=CYAN,
            bg="#141E2E",
            activeforeground="#FFFFFF",
            activebackground="#1E314D",
            padx=16,
            pady=8,
            relief="flat",
            highlightthickness=1,
            highlightbackground=ACCENT,
            command=self.toggle_lock,
            cursor="hand2",
        )

        # Bottom Control Panel
        self.control_panel = tk.Frame(self.main_container, bg=PANEL_BG, padx=16, pady=10)
        self.control_panel.grid(row=1, column=0, sticky="ew")
        self.control_panel.columnconfigure(1, weight=1)

        # 1. Timeline & Position Row
        self.timeline_frame = tk.Frame(self.control_panel, bg=PANEL_BG)
        self.timeline_frame.pack(fill="x", expand=True, pady=(0, 6))

        self.time_cur_label = tk.Label(
            self.timeline_frame, text="00:00", font=("Consolas", 10), fg=TEXT_MUTED, bg=PANEL_BG
        )
        self.time_cur_label.pack(side="left", padx=(0, 8))

        self.seek_var = tk.DoubleVar()
        self.seekbar = ttk.Scale(
            self.timeline_frame,
            from_=0,
            to=1000,
            orient="horizontal",
            variable=self.seek_var,
            command=self.on_seek,
        )
        self.seekbar.pack(side="left", fill="x", expand=True)
        self.seekbar.bind("<ButtonPress-1>", self.on_seek_start)
        self.seekbar.bind("<ButtonRelease-1>", self.on_seek_end)

        self.time_tot_label = tk.Label(
            self.timeline_frame, text="00:00", font=("Consolas", 10), fg=TEXT_MUTED, bg=PANEL_BG
        )
        self.time_tot_label.pack(side="right", padx=(8, 0))

        # 2. Main Controls Bar
        self.btn_row = tk.Frame(self.control_panel, bg=PANEL_BG)
        self.btn_row.pack(fill="x", expand=True)

        # Left Group: Open, Subtitles, Audio, EQ
        self.btn_open = self.create_btn(self.btn_row, "📁 Open", self.choose_file)
        self.btn_open.pack(side="left", padx=4)

        self.sub_var = tk.StringVar(value="Subtitles")
        self.sub_menu_btn = tk.Menubutton(
            self.btn_row,
            textvariable=self.sub_var,
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
            activebackground=BORDER_COLOR,
            activeforeground=CYAN,
            relief="flat",
            padx=10,
            pady=4,
            font=("Segoe UI", 9, "bold"),
            cursor="hand2",
        )
        self.sub_menu = tk.Menu(self.sub_menu_btn, tearoff=0, bg=CARD_BG, fg=TEXT_PRIMARY)
        self.sub_menu_btn["menu"] = self.sub_menu
        self.sub_menu_btn.pack(side="left", padx=4)

        self.eq_var = tk.StringVar(value=f"EQ: {self.active_eq_preset}")
        self.eq_menu_btn = tk.Menubutton(
            self.btn_row,
            textvariable=self.eq_var,
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
            activebackground=BORDER_COLOR,
            activeforeground=CYAN,
            relief="flat",
            padx=10,
            pady=4,
            font=("Segoe UI", 9, "bold"),
            cursor="hand2",
        )
        self.eq_menu = tk.Menu(self.eq_menu_btn, tearoff=0, bg=CARD_BG, fg=TEXT_PRIMARY)
        for preset in EQ_PRESETS.keys():
            self.eq_menu.add_command(
                label=preset, command=lambda p=preset: self.apply_equalizer(p)
            )
        self.eq_menu_btn["menu"] = self.eq_menu
        self.eq_menu_btn.pack(side="left", padx=4)

        # Center Group: Seek -10, Play/Pause, Seek +10, Speed
        self.center_frame = tk.Frame(self.btn_row, bg=PANEL_BG)
        self.center_frame.pack(side="left", expand=True)

        self.btn_rewind = self.create_btn(self.center_frame, "⏪ -10s", lambda: self.seek_relative(-10000))
        self.btn_rewind.pack(side="left", padx=4)

        self.btn_play = self.create_btn(self.center_frame, "▶ Play", self.toggle_play, primary=True)
        self.btn_play.pack(side="left", padx=6)

        self.btn_forward = self.create_btn(self.center_frame, "+10s ⏩", lambda: self.seek_relative(10000))
        self.btn_forward.pack(side="left", padx=4)

        self.speed_btn = self.create_btn(self.center_frame, "1.0×", self.cycle_speed)
        self.speed_btn.pack(side="left", padx=6)

        # Right Group: Volume (up to 200%), Lock, Snapshot, Fullscreen
        self.btn_snap = self.create_btn(self.btn_row, "📸", self.take_snapshot)
        self.btn_snap.pack(side="right", padx=3)

        self.btn_fs = self.create_btn(self.btn_row, "⛶", self.toggle_fullscreen)
        self.btn_fs.pack(side="right", padx=3)

        self.btn_lock = self.create_btn(self.btn_row, "🔓", self.toggle_lock)
        self.btn_lock.pack(side="right", padx=3)

        self.vol_label = tk.Label(
            self.btn_row, text="100%", font=("Segoe UI", 9, "bold"), fg=CYAN, bg=PANEL_BG
        )
        self.vol_label.pack(side="right", padx=(2, 4))

        self.vol_var = tk.DoubleVar(value=100)
        self.vol_slider = ttk.Scale(
            self.btn_row,
            from_=0,
            to=200,
            orient="horizontal",
            variable=self.vol_var,
            command=self.on_volume,
            length=100,
        )
        self.vol_slider.pack(side="right", padx=4)

        self.vol_icon = tk.Label(
            self.btn_row, text="🔊", font=("Segoe UI", 10), fg=TEXT_PRIMARY, bg=PANEL_BG
        )
        self.vol_icon.pack(side="right", padx=(4, 0))

    def create_btn(self, parent, text, command, primary=False):
        bg = ACCENT if primary else CARD_BG
        fg = "#FFFFFF"
        active_bg = "#33BAFF" if primary else BORDER_COLOR
        btn = tk.Button(
            parent,
            text=text,
            command=command,
            bg=bg,
            fg=fg,
            activebackground=active_bg,
            activeforeground="#FFFFFF",
            relief="flat",
            padx=10,
            pady=4,
            font=("Segoe UI", 9, "bold" if primary else "normal"),
            cursor="hand2",
            highlightthickness=0,
        )
        return btn

    def setup_bindings(self):
        self.root.bind("<space>", lambda e: self.toggle_play())
        self.root.bind("<Left>", lambda e: self.seek_relative(-10000))
        self.root.bind("<Right>", lambda e: self.seek_relative(10000))
        self.root.bind("<Up>", lambda e: self.adjust_volume(5))
        self.root.bind("<Down>", lambda e: self.adjust_volume(-5))
        self.root.bind("<f>", lambda e: self.toggle_fullscreen())
        self.root.bind("<F11>", lambda e: self.toggle_fullscreen())
        self.root.bind("<Escape>", lambda e: self.exit_fullscreen())
        self.root.bind("<m>", lambda e: self.toggle_mute())
        self.root.bind("<s>", lambda e: self.cycle_subtitles())
        self.root.bind("<l>", lambda e: self.toggle_lock())
        self.root.bind("<Control-s>", lambda e: self.take_snapshot())

        self.video_frame.bind("<Double-Button-1>", lambda e: self.toggle_fullscreen())
        self.video_frame.bind("<Button-1>", self.on_video_click)
        self.video_frame.bind("<Motion>", self.on_mouse_move)

    def on_video_click(self, event):
        if self.is_locked:
            self.show_unlock_banner()
            return
        self.toggle_play()

    def on_mouse_move(self, event):
        self.last_mouse_move = time.time()
        if not self.controls_visible:
            self.control_panel.grid()
            self.controls_visible = True

    def toggle_lock(self):
        self.is_locked = not self.is_locked
        if self.is_locked:
            self.btn_lock.configure(text="🔒", bg="#E53935")
            self.show_capsule("Screen Locked", 1500)
            self.show_unlock_banner()
        else:
            self.btn_lock.configure(text="🔓", bg=CARD_BG)
            self.lock_banner.place_forget()
            self.show_capsule("Unlocked", 1200)

    def show_unlock_banner(self):
        self.lock_banner.place(relx=0.04, rely=0.5, anchor="w")
        self.root.after(2800, lambda: self.lock_banner.place_forget())

    def show_capsule(self, text, duration_ms=1300):
        self.capsule_label.configure(text=text)
        self.capsule_label.place(relx=0.5, rely=0.06, anchor="center")
        self.root.after(duration_ms, lambda: self.capsule_label.place_forget())

    def cycle_speed(self):
        if self.is_locked:
            return
        speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]
        cur = round(self.current_rate, 2)
        idx = 2
        for i, s in enumerate(speeds):
            if abs(cur - s) < 0.05:
                idx = (i + 1) % len(speeds)
                break
        self.current_rate = speeds[idx]
        self.speed_btn.configure(text=f"{self.current_rate}×")
        if self.player:
            vlc.libvlc_media_player_set_rate(self.player, c_float(self.current_rate))
        self.show_capsule(f">> {self.current_rate}× Speed", 1300)

    def choose_file(self):
        path = filedialog.askopenfilename(
            title="Open Video File",
            filetypes=[
                ("Video Files", "*.mp4 *.mkv *.avi *.mov *.flv *.webm *.ts *.m4v *.wmv *.pending*"),
                ("Audio Files", "*.mp3 *.m4a *.aac *.flac *.ogg *.wav"),
                ("All Files", "*.*"),
            ],
        )
        if path:
            self.open_media(path)

    def open_media(self, path):
        if not os.path.exists(path):
            return
        self.current_file = path

        if self.player:
            vlc.libvlc_media_player_stop(self.player)
            vlc.libvlc_media_player_release(self.player)

        m = vlc.libvlc_media_new_path(self.vlc_inst, path.encode("utf-8"))
        self.player = vlc.libvlc_media_player_new_from_media(m)
        vlc.libvlc_media_release(m)

        # Attach hardware video drawing to Tkinter frame HWND
        hwnd = self.video_frame.winfo_id()
        vlc.libvlc_media_player_set_hwnd(self.player, c_void_p(hwnd))

        # Setup audio equalizer
        self.apply_equalizer(self.active_eq_preset)

        # Set Volume
        vlc.libvlc_audio_set_volume(self.player, int(self.volume))

        # Set Rate
        vlc.libvlc_media_player_set_rate(self.player, c_float(self.current_rate))

        # Start playback
        vlc.libvlc_media_player_play(self.player)
        self.btn_play.configure(text="⏸ Pause")
        self.root.title(f"MS PLAYER - {os.path.basename(path)}")

        # Populate subtitle menu after media parses
        self.root.after(800, self.refresh_tracks)

    def apply_equalizer(self, preset_name):
        self.active_eq_preset = preset_name
        self.eq_var.set(f"EQ: {preset_name}")
        gains = EQ_PRESETS.get(preset_name, [0.0] * 10)
        if not self.equalizer:
            self.equalizer = vlc.libvlc_audio_equalizer_new()
        if self.equalizer:
            vlc.libvlc_audio_equalizer_set_preamp(self.equalizer, c_float(0.0))
            for i, db in enumerate(gains):
                vlc.libvlc_audio_equalizer_set_amp_at_index(self.equalizer, c_float(db), i)
            if self.player:
                vlc.libvlc_media_player_set_equalizer(self.player, self.equalizer)
        self.show_capsule(f"Audio EQ: {preset_name}", 1200)

    def refresh_tracks(self):
        if not self.player:
            return
        self.sub_menu.delete(0, "end")
        count = vlc.libvlc_video_get_spu_count(self.player)
        cur_spu = vlc.libvlc_video_get_spu(self.player)

        self.sub_menu.add_command(
            label="Disable Subtitles",
            command=lambda: self.set_subtitle(-1),
        )
        for i in range(count):
            self.sub_menu.add_command(
                label=f"Subtitle Track {i + 1}",
                command=lambda track=i: self.set_subtitle(track),
            )

    def set_subtitle(self, track_id):
        if not self.player or self.is_locked:
            return
        vlc.libvlc_video_set_spu(self.player, track_id)
        msg = f"Subtitle: Track {track_id + 1}" if track_id >= 0 else "Subtitles: Disabled"
        self.show_capsule(msg, 1200)

    def cycle_subtitles(self):
        if not self.player or self.is_locked:
            return
        count = vlc.libvlc_video_get_spu_count(self.player)
        if count <= 0:
            self.show_capsule("No Subtitle Tracks", 1000)
            return
        cur = vlc.libvlc_video_get_spu(self.player)
        nxt = -1 if cur >= count - 1 else cur + 1
        self.set_subtitle(nxt)

    def toggle_play(self):
        if self.is_locked or not self.player:
            return
        if vlc.libvlc_media_player_is_playing(self.player):
            vlc.libvlc_media_player_pause(self.player)
            self.btn_play.configure(text="▶ Play")
        else:
            vlc.libvlc_media_player_play(self.player)
            self.btn_play.configure(text="⏸ Pause")

    def seek_relative(self, delta_ms):
        if self.is_locked or not self.player:
            return
        cur = vlc.libvlc_media_player_get_time(self.player)
        tot = vlc.libvlc_media_player_get_length(self.player)
        target = max(0, min(tot, cur + delta_ms))
        vlc.libvlc_media_player_set_time(self.player, target)
        tag = f"+{abs(delta_ms)//1000}s ⏩" if delta_ms > 0 else f"⏪ -{abs(delta_ms)//1000}s"
        self.show_capsule(tag, 1000)

    def on_seek_start(self, event):
        self.seeking = True

    def on_seek_end(self, event):
        if self.player:
            frac = self.seek_var.get() / 1000.0
            vlc.libvlc_media_player_set_position(self.player, c_float(frac))
        self.seeking = False

    def on_seek(self, value):
        if self.player and self.seeking:
            tot = vlc.libvlc_media_player_get_length(self.player)
            target = int((float(value) / 1000.0) * tot)
            self.time_cur_label.configure(text=format_time(target))

    def on_volume(self, value):
        self.volume = int(float(value))
        self.vol_label.configure(text=f"{self.volume}%")
        if self.player:
            vlc.libvlc_audio_set_volume(self.player, self.volume)

    def adjust_volume(self, delta):
        if self.is_locked:
            return
        new_vol = max(0, min(200, self.volume + delta))
        self.vol_var.set(new_vol)
        self.on_volume(new_vol)
        self.show_capsule(f"Volume: {new_vol}%", 900)

    def toggle_mute(self):
        if self.volume > 0:
            self.last_volume = self.volume
            self.vol_var.set(0)
            self.on_volume(0)
            self.show_capsule("Muted", 1000)
        else:
            restore = getattr(self, "last_volume", 100)
            self.vol_var.set(restore)
            self.on_volume(restore)
            self.show_capsule(f"Volume: {restore}%", 1000)

    def toggle_fullscreen(self):
        self.is_fullscreen = not self.is_fullscreen
        self.root.attributes("-fullscreen", self.is_fullscreen)

    def exit_fullscreen(self):
        if self.is_fullscreen:
            self.is_fullscreen = False
            self.root.attributes("-fullscreen", False)

    def take_snapshot(self):
        if not self.player:
            return
        out_dir = r"S:\msplayer\outputs"
        os.makedirs(out_dir, exist_ok=True)
        snap_path = os.path.join(out_dir, "pc_snapshot.png")
        if os.path.exists(snap_path):
            try:
                os.remove(snap_path)
            except Exception:
                pass
        res = vlc.libvlc_video_take_snapshot(self.player, 0, snap_path.encode("utf-8"), 0, 0)
        if res == 0:
            self.show_capsule("📸 Snapshot Saved!", 1400)
            print(f"Snapshot saved to: {snap_path}")

    def start_timer(self):
        if self.player and not self.seeking:
            cur = vlc.libvlc_media_player_get_time(self.player)
            tot = vlc.libvlc_media_player_get_length(self.player)
            if tot > 0:
                frac = (cur / float(tot)) * 1000.0
                self.seek_var.set(frac)
                self.time_cur_label.configure(text=format_time(cur))
                self.time_tot_label.configure(text=format_time(tot))
        self.root.after(250, self.start_timer)


def main():
    root = tk.Tk()
    app = MSPlayerDesktop(root)
    root.mainloop()


if __name__ == "__main__":
    main()
