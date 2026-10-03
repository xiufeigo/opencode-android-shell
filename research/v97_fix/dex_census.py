"""
DEX census for app-debug.apk  (part A of v97 cold-start investigation)

Methodology
-----------
Parses each classes*.dex entry straight out of the APK with a minimal DEX
format reader (no external tooling required):

  header  -> string_ids_size, type_ids_size, proto_ids_size, field_ids_size,
              method_ids_size, class_defs_size, data_size
  string pool + type_ids -> class descriptor for every class_def
  class_data_item (uleb128) -> per-class direct+virtual method count

So "methods" here = *declared* methods summed over class_defs (not the
method_ids reference table, which counts refs, not declarations).  Total
declared methods is the number ART must hold method metadata for, and is
the right proxy for verification/class-loading cost per class.

Error sources: this counts what is *in the dex*, i.e. it cannot see classes
dead-stripped away (none in debug) and it attributes a class to the dex that
physically contains it.  Unused-but-present classes are counted, which is
exactly the quantity of interest for a shrink question.
"""

import io
import struct
import sys
import zipfile
from collections import defaultdict

APK = sys.argv[1] if len(sys.argv) > 1 else (
    r"C:\sensorsdata\main\program\opencode-android-shell\app\build\outputs\apk\debug\app-debug.apk"
)


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def uleb128(b, o):
    result = 0
    shift = 0
    while True:
        byte = b[o]
        o += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, o
        shift += 7


def read_uleb128_padded(b, o):
    """uleb128 in dex is padded to a 4-byte boundary in some pools; for
    class_data the value is plain uleb128 (no padding)."""


def parse_dex(data):
    if data[:4] != b"dex\n":
        return None
    hdr = {
        "file_size": u32(data, 0x20),
        "header_size": u32(data, 0x24),
        "string_ids_size": u32(data, 0x38),
        "string_ids_off": u32(data, 0x3C),
        "type_ids_size": u32(data, 0x40),
        "type_ids_off": u32(data, 0x44),
        "proto_ids_size": u32(data, 0x48),
        "field_ids_size": u32(data, 0x50),
        "method_ids_size": u32(data, 0x54),
        "class_defs_size": u32(data, 0x60),
        "class_defs_off": u32(data, 0x64),
    }

    # --- string pool (skip the uleb128 utf16 length + payload) ---
    strings = []
    n, base = hdr["string_ids_size"], hdr["string_ids_off"]
    for i in range(n):
        off = u32(data, base + 4 * i)
        strlen, p = uleb128(data, off)
        end = data.index(b"\x00", p)
        # utf16 length is in code units; bytes may be 1 or 2 per unit
        raw = data[p:end]
        try:
            if strlen == len(raw):
                s = raw.decode("utf-8", "replace")
            else:
                s = raw[: strlen * 2].decode("utf-16-le", "replace")
        except Exception:
            s = ""
        strings.append(s)

    type_ids_off = hdr["type_ids_off"]
    def type_name(idx):
        return strings[u32(data, type_ids_off + 4 * idx)]

    # --- class_defs -> (descriptor, class_data_off) ---
    classes = []
    cd_base, cd_n = hdr["class_defs_off"], hdr["class_defs_size"]
    for i in range(cd_n):
        o = cd_base + 32 * i
        class_idx = u32(data, o)
        class_data_off = u32(data, o + 24)
        methods = 0
        fields = 0
        if class_data_off != 0:
            p = class_data_off
            sf, p = uleb128(data, p)          # static fields size
            inf, p = uleb128(data, p)         # instance fields size
            dm, p = uleb128(data, p)          # direct methods size
            vm, p = uleb128(data, p)          # virtual methods size
            methods = dm + vm
            fields = sf + inf
            # skip encoded_field / encoded_method items
            for _ in range(sf + inf):
                _, p = uleb128(data, p)       # field_idx_diff
                _, p = uleb128(data, p)       # access_flags
            for _ in range(methods):
                _, p = uleb128(data, p)       # method_idx_diff
                _, p = uleb128(data, p)       # access_flags
                co, p = uleb128(data, p)      # code_off
        classes.append((type_name(class_idx), methods, fields))
    return hdr, classes


def bucket(desc):
    """Map a class descriptor to a coarse ownership bucket."""
    d = desc.lstrip("L").rstrip(";")
    rules = [
        ("bcprov", "org/bouncycastle/"),
        ("jsch", "com/jcraft/jsch/"),
        ("zxing", "com/google/zxing/"),
        ("okhttp+okio", ("okhttp3/", "okio/")),
        ("compose", "androidx/compose/"),
        ("androidx-core/appcompat/mat/act/constraint/webkit",
         ("androidx/core/", "androidx/appcompat/", "com/google/android/material/",
          "androidx/activity/", "androidx/constraintlayout/", "androidx/webkit/")),
        ("androidx-lifecycle/security/profile",
         ("androidx/lifecycle/", "androidx/security/", "androidx/profileinstaller/")),
        ("androidx-navigation", "androidx/navigation/"),
        ("androidx-annotation/startup/profile", "androidx/annotation/"),
        ("kotlin-stdlib", ("kotlin/", "kotlinx/")),
        ("markwon+commonmark", ("io/noties/", "org/commonmark/")),
        ("security-crypto/tink", ("com/google/crypto/",)),
        ("app-opencode", "com/xiufeigo/"),
        ("termux", "com/termux/"),
        ("json/gson/moshi", ("org/json/", "com/google/gson/")),
        ("jsr305/jcip", ("javax/annotation/", "jcip/")),
    ]
    for name, pref in rules:
        if isinstance(pref, tuple):
            if d.startswith(pref):
                return name
        elif d.startswith(pref):
            return name
    return "other"


def main():
    zf = zipfile.ZipFile(APK)
    dex_names = sorted(
        [n for n in zf.namelist() if n.endswith(".dex")],
        key=lambda n: (len(n), n),
    )

    rows = []
    pkg_classes = defaultdict(set)      # bucket -> set(descriptor)
    pkg_methods = defaultdict(int)      # bucket -> declared methods
    total_methods = 0
    total_classes = 0
    total_strings = 0
    total_method_refs = 0

    for name in dex_names:
        data = zf.read(name)
        hdr, classes = parse_dex(data)
        if hdr is None:
            print(f"  !! {name}: not a dex")
            continue
        m = sum(c[1] for c in classes)
        total_methods += m
        total_classes += len(classes)
        total_strings += hdr["string_ids_size"]
        total_method_refs += hdr["method_ids_size"]
        for desc, meth, _f in classes:
            b = bucket(desc)
            pkg_classes[b].add(desc)
            pkg_methods[b] += meth
        rows.append(
            (name, len(data), hdr["file_size"], len(classes), m,
             hdr["method_ids_size"], hdr["string_ids_size"])
        )

    print("=" * 104)
    print("PER-DEX  (zip bytes = compressed in APK, dex bytes = header file_size)")
    print("=" * 104)
    print(f"{'entry':<14}{'zip B':>12}{'dex B':>12}{'classes':>9}{'methods':>9}{'methrefs':>10}{'strings':>9}")
    for r in rows:
        print(f"{r[0]:<14}{r[1]:>12,}{r[2]:>12,}{r[3]:>9,}{r[4]:>9,}{r[5]:>10,}{r[6]:>9,}")
    print(f"{'TOTAL':<14}{sum(r[1] for r in rows):>12,}{sum(r[2] for r in rows):>12,}"
          f"{total_classes:>9,}{total_methods:>9,}{total_method_refs:>10,}{total_strings:>9,}")

    print()
    print("=" * 104)
    print("BY OWNERSHIP BUCKET  (declared methods summed over class_defs)")
    print("=" * 104)
    print(f"{'bucket':<44}{'classes':>8}{'methods':>10}{'%classes':>10}{'%methods':>10}")
    tot_c, tot_m = total_classes, total_methods
    for b, cs in sorted(pkg_classes.items(), key=lambda kv: -pkg_methods[kv[0]]):
        m = pkg_methods[b]
        print(f"{b:<44}{len(cs):>8,}{m:>10,}{len(cs)/tot_c*100:>9.1f}%{m/tot_m*100:>9.1f}%")
    print(f"{'TOTAL':<44}{tot_c:>8,}{tot_m:>10,}{100.0:>9.1f}%{100.0:>9.1f}%")

    print()
    print("=" * 104)
    print("TOP 30 CLASSES BY DECLARED METHOD COUNT")
    print("=" * 104)
    allc = []
    for name in dex_names:
        data = zf.read(name)
        hdr, classes = parse_dex(data)
        if hdr is None:
            continue
        for desc, meth, fld in classes:
            allc.append((meth, desc, name))
    allc.sort(reverse=True)
    for meth, desc, dexname in allc[:30]:
        print(f"{meth:>6}  {desc:<78} [{dexname}]")

    print()
    print("=" * 104)
    print("APK NON-DEX PAYLOAD (largest single entries, and bouncycastle resources)")
    print("=" * 104)
    infos = [i for i in zf.infolist() if not i.filename.endswith(".dex")]
    infos.sort(key=lambda i: -i.file_size)
    for i in infos[:20]:
        print(f"{i.file_size:>12,}  {i.filename}")
    bc_res = [i for i in infos if "bouncycastle" in i.filename]
    if bc_res:
        print(f"\n  bouncycastle non-dex resources: {len(bc_res)} entries, "
              f"{sum(i.file_size for i in bc_res):,} B uncompressed / "
              f"{sum(i.compress_size for i in bc_res):,} B stored")
        for i in sorted(bc_res, key=lambda x: -x.file_size)[:15]:
            print(f"    {i.file_size:>10,}  {i.filename}")


if __name__ == "__main__":
    main()
