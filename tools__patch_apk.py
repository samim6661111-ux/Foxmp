#!/usr/bin/env python3
"""Inject FoxMP into the GTA V APK.   usage: patch_apk.py in.apk out.apk --tools DIR --libfoxmp libfoxmp.so --android-jar android.jar
DIR must contain: apktool.jar (v2.9+) and Android build-tools (d8.jar via $ANDROID_HOME/build-tools/*/lib, zipalign, apksigner) found automatically.
Steps: apktool d -r  ->  javac+d8 our client  ->  hook GTAVActivity.onCreate  ->  copy libfoxmp.so  ->  apktool b  ->  zipalign + sign."""
import argparse, glob, os, re, shutil, subprocess, sys, tempfile

HOOK = "    invoke-static {p0}, Lcom/gtavsource/android/FoxMp;->start(Landroid/app/Activity;)V\n"

def patch_activity(text):
    """insert the hook before the first return-void of GTAVActivity.onCreate(Bundle). returns new text or None."""
    if "FoxMp;->start" in text:
        return text
    m = re.search(r"\.method [^\n]*onCreate\(Landroid/os/Bundle;\)V\n", text)
    if not m: return None
    end = text.find(".end method", m.end())
    body = text[m.end():end]
    if "    return-void" not in body: return None
    body = body.replace("    return-void", HOOK + "    return-void")   # every exit path; FoxMp.start is idempotent
    return text[:m.end()] + body + text[end:]

def _method(text, header_re):
    m = re.search(header_re, text)
    if not m: return None
    end = text.find(".end method", m.end())
    return m.start(), end + len(".end method")

def patch_pages(text):
    """GtavPages.smali: register an 11th page 'Multiplayer' (index 10) in titles(), ORDER and render()."""
    if "FoxMenu;->build" in text:
        return text
    # 1) titles(): 10 -> 11 entries
    a, b = _method(text, r"\.method public static titles\(\)\[Ljava/lang/String;\n")
    body = text[a:b]
    body = body.replace(".registers 10", ".registers 11", 1)
    old = "    filled-new-array/range {v0 .. v9}, [Ljava/lang/String;"
    if old not in body: return None
    body = body.replace(old, '    const-string v10, "Multiplayer"\n\n    filled-new-array/range {v0 .. v10}, [Ljava/lang/String;')
    text = text[:a] + body + text[b:]
    # 2) ORDER array: size 11, new page shown after 'Trainer'
    a, b = _method(text, r"\.method static constructor <clinit>\(\)V\n")
    body = text[a:b]
    if "    const/16 v0, 0xa\n" not in body or "        0x5\n        0x6\n" not in body: return None
    body = body.replace("    const/16 v0, 0xa\n", "    const/16 v0, 0xb\n", 1).replace("        0x5\n        0x6\n", "        0x5\n        0xa\n        0x6\n", 1)
    text = text[:a] + body + text[b:]
    # 3) render(): page 10 is built by FoxMenu and handled like DXVK/Graphics (mirrored into the HTML launcher)
    a, b = _method(text, r"\.method public static render\(Lcom/gtavsource/android/GameMenuActivity;Landroid/widget/LinearLayout;I\)Z\n")
    body = text[a:b]
    old = "    const/4 v5, 0x0\n\n    const/16 v0, 0x8\n"
    if old not in body: return None
    new = ("    const/4 v5, 0x0\n\n    const/16 v0, 0xa\n\n    if-ne p2, v0, :fox_skip\n\n"
           "    invoke-static {p0, p1}, Lcom/gtavsource/android/FoxMenu;->build(Lcom/gtavsource/android/GameMenuActivity;Landroid/widget/LinearLayout;)V\n\n"
           "    const/4 v5, 0x1\n\n    :fox_skip\n    const/16 v0, 0x8\n")
    body = body.replace(old, new, 1)
    return text[:a] + body + text[b:]

def run(cmd, **kw):
    print("+", " ".join(cmd)); subprocess.check_call(cmd, **kw)

def find_bt():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    c = sorted(glob.glob(os.path.join(home, "build-tools", "*")))
    if not c: sys.exit("Android build-tools not found (set ANDROID_HOME)")
    return c[-1]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("apk"); ap.add_argument("out"); ap.add_argument("--tools", required=True)
    ap.add_argument("--libfoxmp"); ap.add_argument("--android-jar", required=True); ap.add_argument("--keystore")
    a = ap.parse_args(); root = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); bt = find_bt()
    apktool = os.path.join(a.tools, "apktool.jar"); tmp = tempfile.mkdtemp(prefix="foxmp_"); work = os.path.join(tmp, "app")
    # 1) our java -> dex -> smali
    cls = os.path.join(tmp, "cls"); os.makedirs(cls)
    stubs = os.path.join(tmp, "stubs"); os.makedirs(stubs)
    run(["javac", "-source", "8", "-target", "8", "-nowarn", "-cp", a.android_jar, "-d", stubs] + glob.glob(os.path.join(root, "client_stubs/**/*.java"), recursive=True))
    run(["javac", "-source", "8", "-target", "8", "-nowarn", "-cp", a.android_jar + os.pathsep + stubs, "-d", cls] + glob.glob(os.path.join(root, "client/**/*.java"), recursive=True))
    classes = [os.path.join(dp, f) for dp, _, fs in os.walk(cls) for f in fs if f.endswith(".class")]
    dexdir = os.path.join(tmp, "dex"); os.makedirs(dexdir)
    run(["java", "-cp", os.path.join(bt, "lib", "d8.jar"), "com.android.tools.r8.D8", "--min-api", "26", "--lib", a.android_jar, "--classpath", stubs, "--output", dexdir] + classes)
    # 2) decode apk (no resource decoding -> robust, manifest stays untouched)
    run(["java", "-jar", apktool, "d", "-r", "-f", "-o", work, a.apk])
    smali_dirs = sorted(glob.glob(os.path.join(work, "smali*")))
    target = None
    for d in smali_dirs:
        p = os.path.join(d, "com/gtavsource/android/GTAVActivity.smali")
        if os.path.exists(p): target = p
    if not target: sys.exit("GTAVActivity.smali not found")
    t = open(target, encoding="utf-8").read(); n = patch_activity(t)
    if n is None: sys.exit("could not find onCreate/return-void in GTAVActivity")
    open(target, "w", encoding="utf-8").write(n)
    pages = os.path.join(os.path.dirname(target), "GtavPages.smali")
    pt = open(pages, encoding="utf-8").read(); pn = patch_pages(pt)
    if pn is None: sys.exit("GtavPages layout differs from the reference build - cannot add the Multiplayer page")
    open(pages, "w", encoding="utf-8").write(pn)
    # 4) native lib
    if a.libfoxmp:
        dst = os.path.join(work, "lib", "arm64-v8a"); os.makedirs(dst, exist_ok=True); shutil.copy(a.libfoxmp, os.path.join(dst, "libfoxmp.so"))
    # 5) build, align, sign
    built = os.path.join(tmp, "built.apk"); run(["java", "-jar", apktool, "b", "-o", built, work])
    # our dex goes in as the next classesN.dex (no smali round trip needed)
    import zipfile
    unsigned = os.path.join(tmp, "unsigned.apk")
    with zipfile.ZipFile(built) as zi, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zo:
        nums = [int(m.group(1) or 1) for n in zi.namelist() for m in [re.fullmatch(r"classes(\d*)\.dex", n)] if m]
        nxt = max(nums) + 1
        for it in zi.infolist():
            data = zi.read(it.filename)
            zo.writestr(it, data, compress_type=zipfile.ZIP_STORED if it.filename.endswith((".so", ".arsc")) or it.filename.startswith("lib/") else zipfile.ZIP_DEFLATED)
        zo.write(os.path.join(dexdir, "classes.dex"), "classes%d.dex" % nxt)
    aligned = os.path.join(tmp, "aligned.apk"); run([os.path.join(bt, "zipalign"), "-f", "-p", "4", unsigned, aligned])
    ks = a.keystore or os.path.join(tmp, "debug.jks")
    if not os.path.exists(ks):
        run(["keytool", "-genkeypair", "-keystore", ks, "-storepass", "foxmp123", "-keypass", "foxmp123", "-alias", "fox", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=FoxMP"])
    run([os.path.join(bt, "apksigner"), "sign", "--ks", ks, "--ks-pass", "pass:foxmp123", "--key-pass", "pass:foxmp123", "--out", a.out, aligned])
    print("DONE ->", a.out)

if __name__ == "__main__": main()
