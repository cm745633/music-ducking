#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
建置前的靜態自檢。在專案根目錄執行：

    python3 tools/selfcheck.py

抓得到的：資源引用對不對得上、XML 能不能解析、Manifest 宣告的元件有沒有原始檔、
括號平不平衡、index.json 的 offset 與 blob 位元組數合不合、關鍵 Gradle 設定在不在。

抓不到的：型別錯誤、Room DAO 驗證錯誤、依賴版本衝突。
那些要靠 GitHub Actions 的建置日誌。
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

errs, oks = [], []
E = errs.append
O = oks.append
PKG = "app/src/main/java/com/narrator/jp"

# 1. assets：index.json 與 blob 的一致性（同時支援單一與多變體索引）
idx = json.load(open("app/src/main/assets/index.json", encoding="utf-8"))
variants = sorted({m.group(1) for k in idx[0] for m in [re.match(r"(.*)_offset$", k)] if m})
if not variants:
    variants = [""]
blob_of = {"": "voices.bin", "raw": "voices_raw.bin",
           "b": "voices_b.bin", "c": "voices_c.bin", "d": "voices_d.bin"}
for v in variants:
    blob = blob_of.get(v)
    if blob is None or not os.path.exists("app/src/main/assets/" + blob):
        E("找不到變體 %r 對應的 blob" % v)
        continue
    size = os.path.getsize("app/src/main/assets/" + blob)
    ko, kl = (v + "_offset" if v else "offset"), (v + "_length" if v else "length")
    off = 0
    for e in idx:
        if e[ko] != off:
            E("%s %s offset 不連續：%d != %d" % (e["id"], blob, e[ko], off))
            break
        off += e[kl]
    if off != size:
        E("%s：offset 總和 %d != 檔案大小 %d" % (blob, off, size))
    else:
        O("%s %d 筆 offset 連續，總長 %d 與檔案相符" % (blob, len(idx), size))
if len({e["id"] for e in idx}) != len(idx):
    E("index.json 有重複的 id")

# 2. XML
xmls = glob.glob("app/src/main/res/**/*.xml", recursive=True) + ["app/src/main/AndroidManifest.xml"]
for f in xmls:
    try:
        ET.parse(f)
    except Exception as ex:
        E("XML 解析失敗 %s: %s" % (f, ex))
O("%d 個 XML 全部可解析" % len(xmls))

# 3. 資源清單
res = {k: set() for k in ("id", "layout", "string", "drawable", "color", "mipmap", "style")}
for f in glob.glob("app/src/main/res/layout/*.xml"):
    res["layout"].add(os.path.basename(f)[:-4])
    for m in re.finditer(r'android:id="@\+id/([A-Za-z0-9_]+)"', open(f, encoding="utf-8").read()):
        res["id"].add(m.group(1))
for f in glob.glob("app/src/main/res/drawable/*.xml"):
    res["drawable"].add(os.path.basename(f)[:-4])
for f in glob.glob("app/src/main/res/mipmap-*/*.xml"):
    res["mipmap"].add(os.path.basename(f)[:-4])
for f in glob.glob("app/src/main/res/values/*.xml"):
    for c in ET.parse(f).getroot():
        if c.tag in res:
            res[c.tag].add(c.get("name"))

kts = glob.glob(PKG + "/*.kt")
for f in kts:
    for m in re.finditer(r'\bR\.(id|layout|string|drawable|color|mipmap|style)\.([A-Za-z0-9_]+)',
                         open(f, encoding="utf-8").read()):
        if m.group(2) not in res[m.group(1)]:
            E("%s 引用不存在的 R.%s.%s" % (os.path.basename(f), m.group(1), m.group(2)))
O("Kotlin 的 R.* 引用全部對得上（%d 個 id / %d 個 layout）" % (len(res["id"]), len(res["layout"])))

for f in xmls:
    for m in re.finditer(r'"@(string|drawable|color|mipmap|style)/([A-Za-z0-9_.]+)"',
                         open(f, encoding="utf-8").read()):
        if m.group(1) == "style" and m.group(2).startswith("Theme.Material3"):
            continue
        if m.group(2) not in res[m.group(1)]:
            E("%s 引用不存在的 @%s/%s" % (os.path.basename(f), m.group(1), m.group(2)))
O("XML 之間的資源引用一致")

# 4. Manifest 元件
man = open("app/src/main/AndroidManifest.xml", encoding="utf-8").read()
classes = {os.path.basename(f)[:-3] for f in kts}
for m in re.finditer(r'android:name="\.([A-Za-z0-9_]+)"', man):
    if m.group(1) not in classes:
        E("AndroidManifest 宣告了 .%s，但找不到對應的 .kt" % m.group(1))
O("AndroidManifest 的元件都有對應原始檔")


# 5. 括號平衡（略過字串與註解）
def strip_kt(s):
    out, i, n = [], 0, len(s)
    while i < n:
        if s[i:i + 3] == '"""':
            j = s.find('"""', i + 3)
            i = (j + 3) if j >= 0 else n
            continue
        if s[i] in '"\'':
            q = s[i]
            i += 1
            while i < n and s[i] != q:
                i += 2 if s[i] == '\\' else 1
            i += 1
            continue
        if s[i:i + 2] == "//":
            j = s.find("\n", i)
            i = j if j >= 0 else n
            continue
        if s[i:i + 2] == "/*":
            j = s.find("*/", i + 2)
            i = (j + 2) if j >= 0 else n
            continue
        out.append(s[i])
        i += 1
    return "".join(out)


for f in kts:
    s = strip_kt(open(f, encoding="utf-8").read())
    for a, b, nm in (("{", "}", "大括號"), ("(", ")", "小括號"), ("[", "]", "中括號")):
        if s.count(a) != s.count(b):
            E("%s %s不平衡 %d vs %d" % (os.path.basename(f), nm, s.count(a), s.count(b)))
O("%d 個 Kotlin 檔括號平衡" % len(kts))

# 6. package 宣告
for f in kts:
    first = open(f, encoding="utf-8").read().lstrip().split("\n")[0].strip()
    if first != "package com.narrator.jp":
        E("%s package 宣告不正確：%s" % (os.path.basename(f), first))
O("所有 Kotlin 檔的 package 一致")

# 7. 關鍵設定
bg = open("app/build.gradle.kts", encoding="utf-8").read()
for need, why in [('noCompress += "bin"', "openFd() 需要 blob 未壓縮"),
                  ("buildConfig = true", "Exporter 用到 BuildConfig.BUILD_DATE"),
                  ("kapt(\"androidx.room:room-compiler", "Room 需要 annotation processor"),
                  ("minSdk = 26", "AudioFocusRequest 需要 API 26")]:
    if need not in bg:
        E("app/build.gradle.kts 缺少 %s（%s）" % (need, why))
wf = glob.glob(".github/workflows/*.yml")
if not wf:
    E("找不到 .github/workflows/*.yml，GitHub 不會自動建置")
else:
    w = open(wf[0], encoding="utf-8").read()
    for need in ["softprops/action-gh-release", "gradle assembleDebug", "java-version: '17'"]:
        if need not in w:
            E("workflow 缺少 %s" % need)
O("build.gradle.kts 與 workflow 的關鍵設定都在")

# 8. 跨檔符號：呼叫自家 object 的成員，該成員必須真的存在
OBJECTS = ["VoiceIndex", "Prefs", "AudioMode", "Reasons", "Flagger", "Buzz",
           "Exporter", "AppDb", "NarratorService", "VoicePlayer", "Picker", "Clip"]
declared = {}
for name in OBJECTS:
    for f in kts:
        src = open(f, encoding="utf-8").read()
        if re.search(r"\b(object|class|interface)\s+" + name + r"\b", src):
            declared[name] = set(
                re.findall(r"^\s*(?:(?:@\w+\s+)|(?:public|private|internal|protected|const|"
                           r"open|abstract|override|suspend|inline|operator|lateinit|"
                           r"external|final|infix|tailrec)\s+)*"
                           r"(?:val|var|fun)\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)", src, re.M)
            ) | set(re.findall(r"^\s*(?:data\s+)?(?:object|class)\s+([A-Za-z_][A-Za-z0-9_]*)", src, re.M))
            break
missing = 0
for f in kts:
    src = open(f, encoding="utf-8").read()
    for m in re.finditer(r"\b(" + "|".join(declared.keys() or ["__none__"]) + r")\.([a-zA-Z_][A-Za-z0-9_]*)", src):
        obj, member = m.group(1), m.group(2)
        if member not in declared[obj]:
            E("%s 呼叫了 %s.%s，但 %s 裡沒有這個成員" % (os.path.basename(f), obj, member, obj))
            missing += 1
if not missing:
    O("跨檔呼叫的 %d 個自家型別，成員全部存在" % len(declared))

print("=== 通過 ===")
for m in oks:
    print("  ✓", m)
print("=== 錯誤 ===" if errs else "=== 無錯誤 ===")
for m in errs:
    print("  ✗", m)
sys.exit(1 if errs else 0)
