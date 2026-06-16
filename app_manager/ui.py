import os
import queue
import shlex
import subprocess
import threading
import tkinter as tk
from tkinter import ttk
from pathlib import Path

try:
    import customtkinter as ctk
except Exception as e:
    raise SystemExit("customtkinter is required. Install: pip install customtkinter") from e

from app_paths import get_resource_path
from .constants import GROUPS, HOME, ICON_SIZE, UNIQUE_PRIORITY
from .icons import load_default_icon, load_icon_image
from .scanner import collect_flatpak_list, collect_snap_list, collect_wine_programs, iter_desktop_records
from .system_tools import open_folder_select, run_cmd, which
from .dialogs import ask_yes_no, show_error, show_info, show_warning
from .uninstall import (
    first_abs_executable_from_exec,
    package_owning_file,
    resolve_user_actions,
    snap_name_from,
    uninstall_package,
)


class App(ctk.CTk):
    def __init__(self):
        super().__init__(className="ApplicationManager")
        ctk.set_appearance_mode("system")
        ctk.set_default_color_theme("blue")
        self.title("Application Manager")
        self.geometry("1360x820")
        self.minsize(980, 620)
        # some window managers ignore zoom/fullscreen if called too early
        self.after(10, self._open_maximized)

        self.scan_queue: queue.Queue = queue.Queue()
        self.scan_thread: threading.Thread | None = None
        self.scan_running = False
        self.icon_cache: dict[str, object] = {}
        self.default_icon = load_default_icon()
        self.records: dict[str, list[dict]] = {}
        self.view_records: dict[str, list[dict]] = {}
        self.render_index: dict[str, int] = {}
        self.chunk_size = 120
        self.progress_total = 0
        self.progress_done = 0
        self.all_tabs = ["All", "Unique", "Duplicates"] + GROUPS
        self.status_var = tk.StringVar(value="Ready")

        self._build_ui()
        self.after(100, self.rescan)
        self._set_window_icon()

    @property
    def list_row_height(self) -> int:
        return ICON_SIZE + 28

    def _set_window_icon(self):
        for parts in (("assets", "icons", "app-icon.png"), ("assets", "PM.png")):
            icon_path = get_resource_path(*parts)
            try:
                if icon_path.exists():
                    img = tk.PhotoImage(file=str(icon_path))
                    self.iconphoto(True, img)
                    self._window_icon_ref = img
                    return
            except Exception:
                continue

    def _open_maximized(self):
        try:
            self.update_idletasks()
        except Exception:
            pass
        try:
            self.attributes("-zoomed", True)
            return
        except Exception:
            pass
        try:
            self.state("zoomed")
            return
        except Exception:
            pass
        try:
            self.geometry(f"{self.winfo_screenwidth()}x{self.winfo_screenheight()}+0+0")
            self.update_idletasks()
            return
        except Exception:
            pass
        # last resort: true fullscreen (no window borders)
        try:
            self.attributes("-fullscreen", True)
        except Exception:
            pass

    def _build_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(
            "Treeview",
            rowheight=self.list_row_height,
            background="#222426",
            foreground="#f4f5f7",
            fieldbackground="#222426",
            borderwidth=0,
            relief="flat",
            font=("Segoe UI", 13),
        )
        style.configure(
            "Treeview.Heading",
            background="#17354f",
            foreground="#f5f5f5",
            borderwidth=0,
            relief="flat",
            font=("Segoe UI", 11, "bold"),
        )
        style.map(
            "Treeview",
            background=[("selected", "#18598c")],
            foreground=[("selected", "#ffffff")],
        )
        style.map(
            "Treeview.Heading",
            background=[("active", "#1d4668")],
        )

        content = ctk.CTkFrame(self, fg_color="transparent")
        content.pack(fill=tk.BOTH, expand=True, padx=12, pady=(12, 8))
        content.grid_columnconfigure(0, weight=3)
        content.grid_columnconfigure(1, weight=0, minsize=460)
        content.grid_rowconfigure(0, weight=1)

        tabs_wrap = ctk.CTkFrame(content, corner_radius=18, fg_color=("#ddd5c8", "#1d1f22"), border_width=1, border_color=("#c8bba9", "#2f3338"))
        tabs_wrap.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        tabs_wrap.grid_rowconfigure(0, weight=1)
        tabs_wrap.grid_columnconfigure(0, weight=1)

        self.tabs = ctk.CTkTabview(
            tabs_wrap,
            command=self._on_tab_changed,
            corner_radius=16,
            fg_color=("#f2ede5", "#1f2124"),
            segmented_button_fg_color=("#d7c8b5", "#2b2f35"),
            segmented_button_selected_color=("#1f6aa5", "#2d86cb"),
            segmented_button_selected_hover_color=("#2c7fbd", "#3995dd"),
            segmented_button_unselected_color=("#d7c8b5", "#2b2f35"),
            segmented_button_unselected_hover_color=("#cbb8a1", "#373c44"),
            text_color=("#3f2d1d", "#d8dde5"),
            text_color_disabled=("#7a6f63", "#6f7782"),
            border_width=0,
        )
        self.tabs.grid(row=0, column=0, sticky="nsew", padx=10, pady=10)
        segmented_button = getattr(self.tabs, "_segmented_button", None)
        if segmented_button is not None:
            segmented_button.configure(height=38, font=ctk.CTkFont(size=15, weight="bold"))
        self.trees: dict[str, ttk.Treeview] = {}
        self.tree_to_tab: dict[ttk.Treeview, str] = {}
        for tab_name in self.all_tabs:
            tab = self.tabs.add(tab_name)
            tree = self._create_tree(tab, tab_name)
            self.trees[tab_name] = tree
            self.records[tab_name] = []
            self.view_records[tab_name] = []
            self.render_index[tab_name] = 0
            self.tree_to_tab[tree] = tab_name
            tree.bind("<<TreeviewSelect>>", lambda _e: self._update_details_for_current())
            tree.bind("<Double-1>", lambda _e: self.on_launch())
            tree.bind("<MouseWheel>", self._on_tree_scroll)
            tree.bind("<Button-4>", self._on_tree_scroll)
            tree.bind("<Button-5>", self._on_tree_scroll)
            tree.bind("<KeyRelease-Up>", self._on_tree_scroll)
            tree.bind("<KeyRelease-Down>", self._on_tree_scroll)
            tree.bind("<KeyRelease-Prior>", self._on_tree_scroll)
            tree.bind("<KeyRelease-Next>", self._on_tree_scroll)

        self.details_panel = ctk.CTkFrame(content, corner_radius=12, width=460)
        self.details_panel.grid(row=0, column=1, sticky="nsew")
        self.details_panel.grid_propagate(False)
        self._build_details_panel(self.details_panel)

    def _build_details_panel(self, parent):
        ctk.CTkLabel(parent, text="Details", font=ctk.CTkFont(size=18, weight="bold")).pack(anchor="w", padx=14, pady=(14, 10))

        # Search + scan + progress (moved from top bar)
        tools = ctk.CTkFrame(parent, fg_color="transparent")
        tools.pack(fill=tk.X, padx=12, pady=(0, 12))
        tools.grid_columnconfigure(0, weight=1)
        tools.grid_columnconfigure(1, weight=0)

        ctk.CTkLabel(tools, text="Search", anchor="w").grid(row=0, column=0, sticky="w", padx=(2, 8), pady=(0, 6))
        self.var_search = tk.StringVar()
        self.search_entry = ctk.CTkEntry(tools, textvariable=self.var_search, placeholder_text="Name, command, path")
        self.search_entry.grid(row=1, column=0, sticky="ew", padx=(0, 8))
        self.search_entry.bind("<Return>", lambda _: self.apply_filter())

        btn_col = ctk.CTkFrame(tools, fg_color="transparent")
        btn_col.grid(row=1, column=1, sticky="e")
        self.btn_apply = ctk.CTkButton(btn_col, text="Find", command=self.apply_filter, width=90)
        self.btn_apply.pack(padx=2, pady=(0, 6))
        self.btn_clear = ctk.CTkButton(btn_col, text="Clear", command=self.clear_filter, width=90)
        self.btn_clear.pack(padx=2, pady=(0, 6))
        self.btn_scan = ctk.CTkButton(btn_col, text="Rescan", command=self.rescan, width=90)
        self.btn_scan.pack(padx=2)

        ctk.CTkLabel(parent, textvariable=self.status_var, anchor="w", text_color=("gray35", "gray70")).pack(
            fill=tk.X, padx=14, pady=(0, 6)
        )
        self.progress = ctk.CTkProgressBar(parent, width=420)
        self.progress.pack(fill=tk.X, padx=14, pady=(0, 10))
        self.progress.set(0)

        self.details_icon = ctk.CTkLabel(parent, text="")
        self.details_icon.pack(anchor="w", padx=14, pady=(0, 6))

        self.details_name = ctk.CTkLabel(
            parent,
            text="Select an item",
            font=ctk.CTkFont(size=15, weight="bold"),
            anchor="w",
            justify="left",
            wraplength=420,
        )
        self.details_name.pack(fill=tk.X, padx=14, pady=(0, 8))

        self.details_source = ctk.CTkLabel(parent, text="Source: -", anchor="w", text_color=("gray35", "gray70"))
        self.details_source.pack(fill=tk.X, padx=14, pady=2)
        self.details_entry = ctk.CTkLabel(parent, text="Entry: -", anchor="w", text_color=("gray35", "gray70"))
        self.details_entry.pack(fill=tk.X, padx=14, pady=2)

        self.details_exec = ctk.CTkLabel(parent, text="Exec: -", justify="left", anchor="w", wraplength=420)
        self.details_exec.pack(fill=tk.X, padx=14, pady=(10, 4))
        self.details_path = ctk.CTkLabel(parent, text="Path: -", justify="left", anchor="w", wraplength=420)
        self.details_path.pack(fill=tk.X, padx=14, pady=4)

        actions = ctk.CTkFrame(parent, fg_color="transparent")
        actions.pack(fill=tk.X, padx=10, pady=(14, 12))
        actions.grid_columnconfigure(0, weight=1)
        actions.grid_columnconfigure(1, weight=1)
        btn_w = 200
        ctk.CTkButton(actions, text="Launch", command=self.on_launch, width=btn_w).grid(row=0, column=0, padx=4, pady=4, sticky="w")
        ctk.CTkButton(actions, text="Folder", command=self.on_open_folder, width=btn_w).grid(row=0, column=1, padx=4, pady=4, sticky="w")
        ctk.CTkButton(actions, text="Open .desktop", command=self.on_open_file, width=btn_w).grid(row=1, column=0, padx=4, pady=4, sticky="w")
        ctk.CTkButton(actions, text="Uninstall", command=self.on_uninstall, width=btn_w).grid(row=1, column=1, padx=4, pady=4, sticky="w")

    def _update_details_panel(self, rec: dict | None):
        if not rec:
            self.details_icon.configure(image=None)
            self.details_name.configure(text="Select an item")
            self.details_source.configure(text="Source: -")
            self.details_entry.configure(text="Entry: -")
            self.details_exec.configure(text="Exec: -")
            self.details_path.configure(text="Path: -")
            return

        icon = self._img_for(rec.get("icon_path"))
        self.details_icon.configure(image=icon)
        self.details_icon._image_ref = icon
        self.details_name.configure(text=rec.get("name", "-"))
        self.details_source.configure(text=f"Source: {rec.get('grp', '-')}")
        self.details_entry.configure(text=f"Entry: {rec.get('entry', '-')}")
        self.details_exec.configure(text=f"Exec: {rec.get('exec') or '-'}")
        self.details_path.configure(text=f"Path: {rec.get('path') or '-'}")

    def _create_tree(self, parent, tab_name: str):
        wrap = ctk.CTkFrame(parent, corner_radius=14, fg_color=("#f7f2ea", "#232529"))
        wrap.pack(fill=tk.BOTH, expand=True, padx=12, pady=(6, 12))
        cols = ("Entry", "Source", "Exec", "Path")
        tree = ttk.Treeview(wrap, columns=cols, show="tree", selectmode="browse")
        tree.column("#0", width=520, stretch=True)
        for c in cols:
            tree.column(c, width=0, stretch=False)
        scrollbar = ctk.CTkScrollbar(wrap, orientation="vertical", command=lambda *args: self._tree_yview(tab_name, *args))
        tree.configure(yscrollcommand=scrollbar.set)
        tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(12, 6), pady=12)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y, padx=(0, 12), pady=12)
        return tree

    def _tree_yview(self, tab_name: str, *args):
        tree = self.trees[tab_name]
        tree.yview(*args)
        self._maybe_load_more(tab_name)

    def _set_controls(self, enabled: bool):
        state = "normal" if enabled else "disabled"
        self.btn_scan.configure(state=state)
        self.btn_apply.configure(state=state)
        self.btn_clear.configure(state=state)
        self.search_entry.configure(state=state)

    def _clear_trees(self):
        for tab in self.all_tabs:
            self.records[tab] = []
            self.view_records[tab] = []
            self.render_index[tab] = 0
            tree = self.trees[tab]
            for item in tree.get_children():
                tree.delete(item)

    def _img_for(self, icon_path: str | None):
        if not icon_path:
            return self.default_icon
        if icon_path not in self.icon_cache:
            self.icon_cache[icon_path] = load_icon_image(icon_path) or self.default_icon
        return self.icon_cache[icon_path]

    def _scan_worker(self):
        records = list(iter_desktop_records())
        for name, _version in collect_snap_list():
            records.append({"name": name, "grp": "Snap", "entry": f"{name}.snap", "exec": f"snap run {name}", "path": "", "icon_path": None})
        for app_id in collect_flatpak_list():
            records.append({"name": app_id, "grp": "Flatpak", "entry": f"{app_id}.flatpakref", "exec": f"flatpak run {app_id}", "path": "", "icon_path": None})
        for prog in collect_wine_programs():
            records.append({"name": prog, "grp": "Wine", "entry": f"{prog}.wine", "exec": "", "path": "", "icon_path": None})

        total = len(records)
        self.scan_queue.put(("begin", total))
        unique_map: dict[tuple[str, str], dict] = {}
        unique_extras: list[dict] = []

        for idx, rec in enumerate(records, start=1):
            self.scan_queue.put(("progress", idx))
            key = (rec["name"].strip().lower(), rec["exec"].strip().lower())
            cur = unique_map.get(key)
            if not cur:
                unique_map[key] = rec
            elif UNIQUE_PRIORITY.get(rec["grp"], 0) > UNIQUE_PRIORITY.get(cur["grp"], 0):
                unique_extras.append({**cur, "reason": f"Replaced by higher-priority: {rec['grp']}"})
                unique_map[key] = rec
            else:
                unique_extras.append({**rec, "reason": f"Duplicate ({cur['grp']})"})

        unique_records = sorted(unique_map.values(), key=lambda x: x["name"].lower())
        dup_records = []
        for item in unique_extras:
            with_reason = dict(item)
            with_reason["entry"] = f"{item['entry']} - {item.get('reason', 'duplicate')}"
            dup_records.append(with_reason)
        self.scan_queue.put(("done", {"all": records, "unique": unique_records, "dups": dup_records}))

    def rescan(self):
        if self.scan_running:
            return
        self.scan_running = True
        self._set_controls(False)
        self._clear_trees()
        self.progress_total = 0
        self.progress_done = 0
        self.progress.set(0)
        self.status_var.set("Scanning...")
        self.scan_thread = threading.Thread(target=self._scan_worker, daemon=True)
        self.scan_thread.start()
        self.after(40, self._drain_scan_queue)

    def _drain_scan_queue(self):
        completed = False
        payload_done = None
        while True:
            try:
                kind, payload = self.scan_queue.get_nowait()
            except queue.Empty:
                break
            if kind == "begin":
                self.progress_total = max(1, int(payload))
                self.status_var.set(f"Scanning 0/{self.progress_total}")
            elif kind == "progress":
                self.progress_done = int(payload)
                if self.progress_total:
                    self.progress.set(min(1.0, self.progress_done / self.progress_total))
                self.status_var.set(f"Scanning {self.progress_done}/{self.progress_total}")
            elif kind == "done":
                completed = True
                payload_done = payload

        if completed and payload_done is not None:
            self._apply_scan_results(payload_done)
            self.scan_running = False
            self._set_controls(True)
            self.status_var.set(f"Ready: {len(self.records['All'])} items")
            self.progress.set(1)
            return

        self.after(40, self._drain_scan_queue)

    def _fill_tree(self, tab: str, records: list[dict]):
        self.records[tab] = records
        self.view_records[tab] = records
        self._reset_tree_lazy(tab)

    def _reset_tree_lazy(self, tab: str):
        tree = self.trees[tab]
        self.render_index[tab] = 0
        for item in tree.get_children():
            tree.delete(item)
        self._append_tree_chunk(tab)

    def _append_tree_chunk(self, tab: str):
        tree = self.trees[tab]
        data = self.view_records[tab]
        start = self.render_index[tab]
        end = min(start + self.chunk_size, len(data))
        for rec in data[start:end]:
            tree.insert(
                "",
                tk.END,
                text=f"     {rec['name']}",
                image=self._img_for(rec.get("icon_path")),
                values=(rec["entry"], rec["grp"], rec["exec"], rec["path"]),
            )
        self.render_index[tab] = end

    def _maybe_load_more(self, tab: str):
        tree = self.trees[tab]
        first, last = tree.yview()
        while last > 0.95 and self.render_index[tab] < len(self.view_records[tab]):
            self._append_tree_chunk(tab)
            first, last = tree.yview()

    def _on_tree_scroll(self, event):
        tree = event.widget
        tab = self.tree_to_tab.get(tree)
        if tab:
            self.after_idle(lambda: self._maybe_load_more(tab))

    def _apply_scan_results(self, data: dict):
        all_records = data["all"]
        unique_records = data["unique"]
        dup_records = data["dups"]

        grouped = {g: [] for g in GROUPS}
        for rec in all_records:
            grp = rec["grp"] if rec["grp"] in grouped else "Other"
            grouped[grp].append(rec)

        self._fill_tree("All", all_records)
        self._fill_tree("Unique", unique_records)
        self._fill_tree("Duplicates", dup_records)
        for grp in GROUPS:
            self._fill_tree(grp, grouped[grp])
        self._update_details_for_current()

    def _current_tab(self) -> str:
        return self.tabs.get()

    def _on_tab_changed(self):
        tab = self._current_tab()
        self._maybe_load_more(tab)
        self._update_details_for_current()

    def _selected_record(self):
        tab = self._current_tab()
        tree = self.trees[tab]
        sel = tree.selection()
        if not sel:
            return None
        idx = tree.index(sel[0])
        if idx < 0 or idx >= len(self.view_records[tab]):
            return None
        return self.view_records[tab][idx]

    def _update_details_for_current(self):
        rec = self._selected_record()
        if not rec:
            self._update_details_panel(None)
            return
        self._update_details_panel(rec)

    def get_selected(self):
        rec = self._selected_record()
        if not rec:
            return None, None, None, None, None
        return rec["name"], rec["grp"], rec["exec"], rec["path"], rec["entry"]

    def apply_filter(self):
        query = self.var_search.get().strip().lower()
        tab = self._current_tab()
        source = self.records[tab]
        if not query:
            filtered = source
        else:
            filtered = []
            for r in source:
                hay = f"{r.get('name','')} {r.get('grp','')} {r.get('entry','')} {r.get('exec','')} {r.get('path','')}".lower()
                if query in hay:
                    filtered.append(r)
        self.view_records[tab] = filtered
        self._reset_tree_lazy(tab)
        self.status_var.set(f"Shown: {len(filtered)}")
        self._update_details_panel(None)

    def clear_filter(self):
        self.var_search.set("")
        tab = self._current_tab()
        self.view_records[tab] = self.records[tab]
        self._reset_tree_lazy(tab)

    def on_launch(self):
        name, src, exec_clean, _path, _entry = self.get_selected()
        if not name:
            show_warning(self, "Launch", "Select an item.")
            return
        cmd = None
        if exec_clean:
            try:
                cmd = shlex.split(exec_clean)
            except ValueError:
                cmd = exec_clean.split()
        elif src == "Snap":
            cmd = ["snap", "run", name]
        elif src == "Flatpak":
            cmd = ["flatpak", "run", name]
        elif src == "Wine":
            cmd = ["wine", "uninstaller"]
        if not cmd:
            show_info(self, "Launch", "No launch command found.")
            return
        try:
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as e:
            show_error(self, "Launch", f"Error: {e}")

    def on_open_folder(self):
        name, _src, _exec_clean, path, _entry = self.get_selected()
        if not name:
            show_warning(self, "Open folder", "Select an item.")
            return
        if not path:
            show_info(self, "Open folder", "No .desktop path for this item.")
            return
        open_folder_select(path)

    def on_open_file(self):
        name, _src, _exec_clean, path, _entry = self.get_selected()
        if not name:
            show_warning(self, "Open .desktop", "Select an item.")
            return
        if not path:
            show_info(self, "Open .desktop", "No .desktop file for this item.")
            return
        editor = os.environ.get("EDITOR")
        if editor and which(editor):
            subprocess.Popen([editor, path])
            return
        if which("xdg-open"):
            subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        show_info(self, "Open .desktop", path)

    def on_uninstall(self):
        name, src, exec_clean, path, entry = self.get_selected()
        if not name:
            show_warning(self, "Uninstall", "Select an item.")
            return
        if src == "Snap":
            snap_name = snap_name_from(exec_clean or "", entry or "", path or "") or name.split()[0]
            if not ask_yes_no(self, "Uninstall Snap", f"Remove snap '{snap_name}'?"):
                return
            code, out = run_cmd(["snap", "remove", snap_name])
            if code == 0:
                show_info(self, "Uninstall", f"Snap '{snap_name}' removed.")
                self.rescan()
            else:
                show_error(self, "Uninstall", out)
            return
        if src == "Flatpak":
            app_id = os.path.basename(path).replace(".desktop", "") if path else name.split()[0]
            if not ask_yes_no(self, "Uninstall Flatpak", f"Remove flatpak '{app_id}'?"):
                return
            code, out = run_cmd(["flatpak", "uninstall", "-y", app_id])
            if code == 0:
                show_info(self, "Uninstall", f"Flatpak '{app_id}' removed.")
                self.rescan()
            else:
                show_error(self, "Uninstall", out)
            return
        if src == "Wine":
            if not which("wine"):
                show_info(self, "Uninstall", "Wine is not installed.")
                return
            subprocess.Popen(["wine", "uninstaller"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        if src == "User":
            actions = resolve_user_actions(exec_clean or "", path or "")
            if not actions:
                show_info(self, "Uninstall", "Nothing to remove for this item.")
                return
            if not ask_yes_no(self, "Uninstall (user entry)", "Planned actions:\n- " + "\n- ".join(actions) + "\n\nContinue?"):
                return
            errors = []
            bin_path = first_abs_executable_from_exec(exec_clean or "")
            if bin_path and bin_path.startswith(HOME) and os.path.isfile(bin_path):
                try:
                    os.remove(bin_path)
                except Exception as e:
                    errors.append(str(e))
            if path and path.endswith(".desktop"):
                try:
                    os.remove(path)
                except Exception as e:
                    errors.append(str(e))
            if errors:
                show_error(self, "Uninstall", "\n".join(errors))
            else:
                show_info(self, "Uninstall", "Done.")
            self.rescan()
            return

        target = first_abs_executable_from_exec(exec_clean or "") or path
        pkg = package_owning_file(target) if target else None
        if not pkg:
            show_info(self, "Uninstall", "Could not determine owning package. Opening .desktop folder.")
            if path:
                open_folder_select(path)
            return
        if not ask_yes_no(self, "Uninstall package", f"Remove system package '{pkg}'?"):
            return
        ok, out = uninstall_package(pkg)
        if ok:
            show_info(self, "Uninstall", f"Package '{pkg}' removed.")
            self.rescan()
        else:
            show_error(self, "Uninstall", out)


def main():
    app = App()
    app.mainloop()
