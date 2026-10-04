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
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        xml = sh("cat /sdcard/ui.xml")
        if xml.strip().startswith("<?xml"):
            return list(ET.fromstring(xml).iter("node"))
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


def system_screenshot():
    """A real screenshot, the way the phone takes one (lands in Pictures/Screenshots)."""
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
        for url in [
            "https://en.m.wikipedia.org/wiki/Golden_Retriever",
            "https://en.m.wikipedia.org/wiki/Pizza",
            "https://en.m.wikipedia.org/wiki/Taj_Mahal",
            "https://en.m.wikipedia.org/wiki/Indian_Railways",
        ]:
            sh(f"am start -W -a android.intent.action.VIEW -d {url} com.android.chrome")
            wait(10)
            system_screenshot()
        sh("am force-stop com.android.chrome")
    else:
        print("Chrome not installed; Settings screenshots only")
    sh("input keyevent 3")
    wait(2)


def wait_until_read(max_minutes=20):
    """Settings shows "N of N screenshots searchable" once everything is read."""
    tap("Settings", exact=True)
    wait(2)
    deadline = time.time() + max_minutes * 60
    while time.time() < deadline:
        for n in ui_nodes():
            m = re.search(r"(\d+) of (\d+) screenshots searchable", n.get("text") or "")
            if m:
                print("status:", m.group(0))
                if m.group(1) == m.group(2) and int(m.group(2)) > 0:
                    return True
        tap("Do it now", exact=True, required=False)
        wait(20)
    print("!! not every screenshot was read in time")
    return False


def main():
    adb("wait-for-device")
    sh("settings put global window_animation_scale 0.5")
    sh("cmd uimode night yes")
    demo_status_bar()
    fill_library()

    adb("install", "-r", APK, check=True, timeout=600)
    for perm in ["READ_MEDIA_IMAGES", "ACCESS_MEDIA_LOCATION", "POST_NOTIFICATIONS"]:
        sh(f"pm grant {PKG} android.permission.{perm}")
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    wait(15)
    tap("Get started", required=False)
    wait(5)

    wait_until_read()
    save("settings")
    sh("input keyevent 4")
    wait(3)

    w, h = size()
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
    sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.25)} 400")
    wait(3)
    save("details")
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


if __name__ == "__main__":
    main()
