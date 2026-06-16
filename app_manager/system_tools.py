import os
import shutil
import subprocess


def which(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def run_cmd(args: list[str]) -> tuple[int, str]:
    try:
        out = subprocess.check_output(args, stderr=subprocess.STDOUT, text=True)
        return 0, out
    except subprocess.CalledProcessError as e:
        return e.returncode, e.output
    except Exception as e:
        return 1, str(e)


def open_folder_select(file_path: str):
    file_path = os.path.abspath(file_path)
    directory = os.path.dirname(file_path)
    candidates = []
    if which("nautilus"):
        candidates.append(["nautilus", "--select", file_path])
    if which("nemo"):
        candidates.append(["nemo", "--no-desktop", "--browser", "--select", file_path])
    if which("dolphin"):
        candidates.append(["dolphin", "--select", file_path])
    if which("thunar"):
        candidates.append(["thunar", "--select", file_path])

    for cmd in candidates:
        try:
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        except Exception:
            pass

    for fm in ["nemo", "nautilus", "dolphin", "thunar", "pcmanfm"]:
        if which(fm):
            try:
                subprocess.Popen([fm, directory], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except Exception:
                pass

    if which("xdg-open"):
        subprocess.Popen(["xdg-open", directory], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

