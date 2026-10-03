"""
Compiles every Regex(...) / Pattern.compile(...) in the Kotlin sources with ICU, the regex engine
Android uses. The JVM engine the unit tests run on accepts patterns ICU rejects at runtime (for
example a set starting with "[:", which ICU parses as a POSIX class), so this catches them in CI.

Usage: python3 tools/check_android_regex.py   (needs the system ICU library, e.g. libicu74)
"""
import ctypes, glob, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
lib = sorted(glob.glob("/usr/lib/*/libicui18n.so.[0-9]*") + glob.glob("/usr/lib/libicui18n.so.[0-9]*"))
if not lib:
    sys.exit("ICU library not found (install libicu)")
major = re.search(r"libicui18n\.so\.(\d+)", lib[-1]).group(1)
icu = ctypes.CDLL(lib[-1])
uc = ctypes.CDLL(lib[-1].replace("libicui18n", "libicuuc"))
uregex_open = getattr(icu, f"uregex_open_{major}")
uregex_open.restype = ctypes.c_void_p
uregex_close = getattr(icu, f"uregex_close_{major}")
u_errorName = getattr(uc, f"u_errorName_{major}")
u_errorName.restype = ctypes.c_char_p

class UParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32), ("preContext", ctypes.c_uint16 * 16), ("postContext", ctypes.c_uint16 * 16)]

def icu_compile(pattern, flags):
    data = pattern.encode("utf-16-le")
    buf = (ctypes.c_uint16 * (len(data) // 2)).from_buffer_copy(data) if data else (ctypes.c_uint16 * 1)()
    pe = UParseError(); status = ctypes.c_int(0)
    h = uregex_open(buf, len(data) // 2, flags, ctypes.byref(pe), ctypes.byref(status))
    if h: uregex_close(ctypes.c_void_p(h))
    return status.value, pe.offset

def balanced(src, i):
    d = 0; j = i
    while j < len(src):
        c = src[j]
        if src.startswith('"""', j):
            j = src.index('"""', j + 3) + 3; continue
        if c == '"':
            k = j + 1
            while src[k] != '"':
                k += 2 if src[k] == '\\' else 1
            j = k + 1; continue
        if c == '(': d += 1
        elif c == ')':
            d -= 1
            if d == 0: return src[i:j + 1]
        j += 1

def unescape(s):
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t', '\\': '\\', '"': '"', '$': '$'}.get(m.group(1), m.group(0)), s)

consts = {"MONTH_RX": "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)",
          "phrase": "last month"}
files = [f for d in ["core", "app-screenshots", "app-gallery"] for f in glob.glob(f"{ROOT}/{d}/**/src/main/**/*.kt", recursive=True)]
total = bad = 0
for f in files:
    src = open(f).read()
    for m in re.finditer(r'\b(Regex|Pattern\.compile|toRegex)\(', src):
        call = balanced(src, m.end() - 1)
        if call is None: continue
        parts = re.findall(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', call, re.S)
        if not parts: continue
        pattern = "".join(raw if raw else unescape(esc) for raw, esc in parts)
        pattern = re.sub(r'\$\{?(\w+)\}?', lambda mm: consts.get(mm.group(1), mm.group(0)), pattern)
        flags = 2 if ("IGNORE_CASE" in call or "CASE_INSENSITIVE" in call) else 0
        total += 1
        status, off = icu_compile(pattern, flags)
        if status > 0:
            bad += 1
            line = src.count("\n", 0, m.start()) + 1
            print(f"FAIL {f.split('/')[-1]}:{line} {u_errorName(status).decode()} at {off}: {pattern[:120]}")
print(f"ICU {major}: {total} patterns compiled, {bad} failed")
sys.exit(1 if bad or total == 0 else 0)
