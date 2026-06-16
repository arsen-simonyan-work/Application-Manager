import tkinter as tk

try:
    import customtkinter as ctk
except Exception as e:
    raise SystemExit("customtkinter is required. Install: pip install customtkinter") from e


class _ModalDialog(ctk.CTkToplevel):
    def __init__(
        self,
        parent,
        title: str,
        message: str,
        *,
        kind: str = "info",
        buttons: list[tuple[str, str]] = [("OK", "ok")],
        width: int = 520,
        wraplength: int = 480,
        default_value: str | None = None,
    ):
        super().__init__(parent)
        self.title(title or "")
        self.resizable(False, False)
        self.result: str | None = default_value

        # Ensure CTkToplevel has a proper background in dark mode
        try:
            self.configure(fg_color=("gray92", "gray14"))
        except Exception:
            pass

        self.transient(parent)
        self.grab_set()

        outer = ctk.CTkFrame(self, corner_radius=12)
        outer.pack(fill=tk.BOTH, expand=True, padx=12, pady=12)

        header = ctk.CTkFrame(outer, fg_color="transparent")
        header.pack(fill=tk.X, padx=12, pady=(12, 6))

        kind_color = {
            "info": ("#1f6aa5", "#1f6aa5"),
            "warn": ("#c88a00", "#c88a00"),
            "error": ("#c83c3c", "#c83c3c"),
            "question": ("#1f6aa5", "#1f6aa5"),
        }.get(kind, ("#1f6aa5", "#1f6aa5"))

        # Avoid non-portable canvas usage in CTk; use a colored badge label instead.
        badge_text = {"info": "i", "warn": "!", "error": "×", "question": "?"}.get(kind, "i")
        badge = ctk.CTkLabel(
            header,
            text=badge_text,
            width=22,
            height=22,
            fg_color=kind_color[0],
            text_color="#ffffff",
            corner_radius=11,
            font=ctk.CTkFont(size=14, weight="bold"),
        )
        badge.pack(side=tk.LEFT, padx=(0, 8))

        ctk.CTkLabel(header, text=title or "", font=ctk.CTkFont(size=15, weight="bold")).pack(side=tk.LEFT)

        body = ctk.CTkFrame(outer, fg_color="transparent")
        body.pack(fill=tk.BOTH, expand=True, padx=12, pady=(0, 10))

        ctk.CTkLabel(body, text=message, justify="left", anchor="w", wraplength=wraplength).pack(
            fill=tk.X, expand=True, anchor="w"
        )

        btn_row = ctk.CTkFrame(outer, fg_color="transparent")
        btn_row.pack(fill=tk.X, padx=12, pady=(6, 12))

        def _choose(val: str):
            self.result = val
            self.destroy()

        # right-aligned buttons
        for label, value in reversed(buttons):
            ctk.CTkButton(btn_row, text=label, width=110, command=lambda v=value: _choose(v)).pack(
                side=tk.RIGHT, padx=6
            )

        # Force layout before sizing/centering (some WMs + CTk need this)
        self.update_idletasks()
        try:
            self.update()
        except Exception:
            pass

        req_h = max(180, self.winfo_reqheight())
        self.minsize(width, req_h)
        self.geometry(f"{width}x{req_h}")

        try:
            self._center_over_parent(parent)
        except Exception:
            pass

        try:
            self.lift()
            self.attributes("-topmost", True)
            self.after(50, lambda: self.attributes("-topmost", False))
            self.focus_force()
        except Exception:
            pass

        self.protocol("WM_DELETE_WINDOW", lambda: _choose(default_value or (buttons[-1][1] if buttons else "ok")))

    def _center_over_parent(self, parent):
        self.update_idletasks()
        px = parent.winfo_rootx()
        py = parent.winfo_rooty()
        pw = parent.winfo_width()
        ph = parent.winfo_height()
        w = self.winfo_width()
        h = self.winfo_height()
        x = px + max(0, (pw - w) // 2)
        y = py + max(0, (ph - h) // 2)
        self.geometry(f"+{x}+{y}")


def show_info(parent, title: str, message: str) -> None:
    dlg = _ModalDialog(parent, title, message, kind="info", buttons=[("OK", "ok")], default_value="ok")
    parent.wait_window(dlg)


def show_warning(parent, title: str, message: str) -> None:
    dlg = _ModalDialog(parent, title, message, kind="warn", buttons=[("OK", "ok")], default_value="ok")
    parent.wait_window(dlg)


def show_error(parent, title: str, message: str) -> None:
    dlg = _ModalDialog(parent, title, message, kind="error", buttons=[("OK", "ok")], default_value="ok")
    parent.wait_window(dlg)


def ask_yes_no(parent, title: str, message: str) -> bool:
    dlg = _ModalDialog(
        parent,
        title,
        message,
        kind="question",
        buttons=[("No", "no"), ("Yes", "yes")],
        default_value="no",
    )
    parent.wait_window(dlg)
    return dlg.result == "yes"

