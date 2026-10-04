"""Takes the README screenshots on an Android emulator (run by .github/workflows/screenshots.yml).

Fills the phone with a few real screenshots (Settings pages, web pages in Chrome), lets
Dot Screenshots read them, then captures its main screens into docs/screenshots/.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.pdrajan.dotscreenshots"
OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/screenshots"
APK = sys.argv[2] if len(sys.argv) > 2 else "DotScreenshots.apk"


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def wait(seconds):
    time.sleep(seconds)


def ui_nodes():
    for _ in range(3):
        # A failed dump would otherwise leave the previous screen's file behind.
        sh("rm -f /sdcard/ui.xml")
        out = sh("uiautomator dump /sdcard/ui.xml 2>&1")
        xml = sh("cat /sdcard/ui.xml 2>/dev/null")
        if xml.strip().startswith("<?xml"):
            return list(ET.fromstring(xml).iter("node"))
        print("!! couldn't read the screen:", out.strip()[:120], flush=True)
        wait(1)
    return []


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return x1, y1, x2, y2


def find(label, exact=False):
    for n in ui_nodes():
        for key in ("text", "content-desc"):
            v = n.get(key) or ""
            if (v == label) if exact else (label.lower() in v.lower()):
                return n
    return None


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


def tap(label, exact=False, required=True):
    n = find(label, exact)
    if n is None:
        if required:
            print(f"!! not on screen: {label}")
            save("debug-missing-" + re.sub(r"\W+", "-", label.lower()))
        return False
    tap_node(n)
    return True


def size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


def save(name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    print("saved", path)


def dismiss_dialogs():
    """System popups ("… isn't responding") would end up in the sample screenshots."""
    nodes = ui_nodes()
    if any("isn't responding" in (n.get("text") or "") for n in nodes):
        tap("Wait", exact=True, required=False)
        wait(2)


def system_screenshot():
    """A real screenshot, the way the phone takes one (lands in Pictures/Screenshots)."""
    dismiss_dialogs()
    sh("input keyevent 120")
    wait(4)


def demo_status_bar():
    sh("settings put global sysui_demo_allowed 1")
    for extra in [
        "-e command enter",
        "-e command clock -e hhmm 0930",
        "-e command battery -e level 100 -e plugged false",
        "-e command network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none",
        "-e command notifications -e visible false",
    ]:
        sh(f"am broadcast -a com.android.systemui.demo {extra}")


def fill_library():
    """About a dozen ordinary screenshots for the app to read."""
    # No error popups, no Chrome notification prompt, and a few quiet seconds after boot.
    sh("settings put global hide_error_dialogs 1")
    sh("pm grant com.android.chrome android.permission.POST_NOTIFICATIONS")
    wait(45)
    pages = [
        "android.settings.WIFI_SETTINGS",
        "android.settings.DISPLAY_SETTINGS",
        "android.settings.DEVICE_INFO_SETTINGS",
        "android.intent.action.POWER_USAGE_SUMMARY",
        "android.settings.SETTINGS",
    ]
    for action in pages:
        sh(f"am start -W -a {action}")
        wait(3)
        system_screenshot()
    sh("am force-stop com.android.settings")

    if "com.android.chrome" in sh("pm list packages com.android.chrome"):
        # Skip Chrome's first-run screens.
        sh("echo 'chrome --disable-fre --no-default-browser-check --no-first-run' > /data/local/tmp/chrome-command-line")
        sh("am set-debug-app --persistent com.android.chrome")
        w, h = size()
        # The last one taken is the newest: the viewer shots open it.
        for url in [
            "https://en.m.wikipedia.org/wiki/Indian_Railways",
            "https://en.m.wikipedia.org/wiki/Taj_Mahal",
            "https://en.m.wikipedia.org/wiki/Pizza",
            "https://en.m.wikipedia.org/wiki/Golden_Retriever",
        ]:
            sh(f"am start -W -a android.intent.action.VIEW -d {url} com.android.chrome")
            wait(10)
            # Chrome's notification prompt covers the page.
            if tap("No thanks", exact=True, required=False):
                wait(2)
            # Scroll a little, to the article's picture.
            sh(f"input swipe {w // 2} {int(h * 0.7)} {w // 2} {int(h * 0.45)} 400")
            wait(3)
            system_screenshot()
        sh("am force-stop com.android.chrome")
    else:
        print("Chrome not installed; Settings screenshots only")
    sh("input keyevent 3")
    wait(2)


def in_front():
    out = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
    return PKG in out


def launch():
    sh(f"am start -W -n {PKG}/.MainActivity")
    wait(5)


def report_logs(label):
    """The app's crash and its own log lines, into the job log (artifacts can't be read back)."""
    print(f"--- {label}: crash buffer", flush=True)
    print(adb("logcat", "-d", "-b", "crash")[-8000:], flush=True)
    print(f"--- {label}: app log", flush=True)
    lines = adb("logcat", "-d", "-s", "Dot:*", "AndroidRuntime:*", "DEBUG:*", "libc:*", "lowmemorykiller:*").splitlines()
    print("\n".join(lines[-120:]), flush=True)


def wait_until_read(max_minutes=40):
    """Done when Settings shows no progress line ("Reading your screenshots · N left", the model download)."""
    def open_settings():
        if not in_front():
            launch()
        tap("Settings", exact=True)
        wait(2)

    open_settings()
    # The README shows descriptions and keywords, which are hidden by default.
    if tap("Show description and keywords", exact=True, required=False):
        wait(1)
    started = time.time()
    deadline = started + max_minutes * 60
    reports = 0
    seen_busy = False
    idle = 0
    while time.time() < deadline:
        if not in_front():
            print("!! the app isn't in front", flush=True)
            if reports < 3:
                report_logs("app not in front")
                reports += 1
            open_settings()
        texts = [n.get("text") or "" for n in ui_nodes()]
        if "SETTINGS" not in texts:
            print(f"!! not on Settings (seen: {[t for t in texts if t][:6]})", flush=True)
            sh("input keyevent 4")
            wait(2)
            open_settings()
            continue
        busy = [
            t for t in texts
            if " left" in t or "waiting" in t or "Reading your screenshots" in t
            or t.startswith("Downloading the description model") or t.startswith("Checking the description model")
            or ("Description model:" in t and ("waiting" in t or "retrying" in t))
            or "Getting the text reader" in t
        ]
        print("status:", busy[0] if busy else "idle", flush=True)
        if busy:
            seen_busy = True
            idle = 0
            if any("waiting" in t for t in busy):
                tap("Do it now", exact=True, required=False)
        else:
            idle += 1
            # Twice idle in a row, once work was seen (or long enough that it would have started).
            if idle >= 2 and (seen_busy or time.time() - started > 180):
                return True
        wait(20)
    print("!! not every screenshot was read in time", flush=True)
    return False


def main():
    adb("wait-for-device")
    # No animations: uiautomator can't read a screen that never settles (pulsing dots, loaders).
    for key in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        sh(f"settings put global {key} 0")
    adb("logcat", "-G", "16M")
    sh("cmd uimode night yes")
    demo_status_bar()
    fill_library()

    print("library filled; installing", flush=True)
    adb("install", "-r", APK, check=True, timeout=600)
    for perm in ["READ_MEDIA_IMAGES", "ACCESS_MEDIA_LOCATION", "POST_NOTIFICATIONS"]:
        sh(f"pm grant {PKG} android.permission.{perm}")
    adb("logcat", "-c")
    launch()
    wait(10)
    tap("Get started", required=False)
    wait(5)
    print("launched; app in front:", in_front(), flush=True)

    ok = wait_until_read()
    report_logs("after processing")
    save("settings")
    sh("input keyevent 4")
    wait(3)

    w, h = size()
    ok = ok and in_front()
    save("home")

    # The viewer: the newest screenshot, then its details.
    day = find("Today", exact=True)
    if day is not None:
        x1, y1, x2, y2 = bounds(day)
        sh(f"input tap {w // 6} {y2 + w // 4}")
    else:
        sh(f"input tap {w // 6} {h // 2}")
    wait(4)
    save("viewer")
    if not tap("Details", exact=True, required=False):
        sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.25)} 400")
    wait(3)
    save("details")
    # Further down: the keywords, opened.
    sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.45)} 400")
    wait(2)
    tap("Show keywords", required=False)
    wait(2)
    save("details-keywords")
    sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.9)} 300")
    sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.9)} 300")
    wait(3)

    # The editor.
    if tap("Edit", exact=True, required=False):
        wait(5)
        tap("MARKUP", exact=True, required=False)
        wait(1)
        sh(f"input swipe {int(w * 0.3)} {int(h * 0.4)} {int(w * 0.7)} {int(h * 0.45)} 500")
        wait(2)
        save("edit")
        sh("input keyevent 4")
        wait(2)
    sh("input keyevent 4")
    wait(3)

    # Search.
    tap("Search your screenshots")
    wait(3)
    sh("input text dog")
    wait(1)
    sh("input keyevent 66")
    wait(6)
    save("search")
    if not ok:
        # Don't let the workflow commit screenshots of a half-processed or missing app.
        print("!! screenshots are not good enough to commit", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
