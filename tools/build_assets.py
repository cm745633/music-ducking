#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
從 VOICEVOX 相容引擎直出的 wav 重建 App 的語音資產。

重新合成問題句之後跑這個腳本，會產出兩個檔案，覆蓋到
app/src/main/assets/ 底下，再推上 GitHub 就會自動建置新的 APK。

    python3 tools/build_assets.py --src "/path/to/AivisSpeech Sound" \
                                  --out app/src/main/assets

--src 目錄的結構必須是（與初次合成時相同）：

    <src>/コハク/synth_コハク.py      # 內含 SPEAKER / DOMAIN / LINES
    <src>/コハク/out_コハク/コハク_001.wav
    ...

產出：
    voices.bin    600 段 mp3 首尾相接的單一檔案
    index.json    每條的 id / file / character / text / domain / offset / length
    loudness_report.csv

為什麼要打包成單一檔案：GitHub 網頁上傳一次最多 100 個檔案，
600 個 mp3 得分六批傳。打包後只要拖一次。
App 端用 AssetManager.openFd() 取得 blob 起點，再加上 offset 餵給
MediaPlayer.setDataSource(fd, offset, length)。因此 build.gradle.kts 內
androidResources.noCompress 必須包含 "bin"，否則 openFd() 會失敗。

──────────────────────────────────────────────────────────
響度處理鏈（v1.1）

    volume=<-16 減實測 LUFS>dB      # 先把每條拉齊到 -16 LUFS
    acompressor 3:1                 # 降低波峰因數，騰出提升的空間
    ── 量測一次 ──
    volume=<-10.5 減實測>dB          # 拉到目標
    asoftclip=type=tanh             # 軟削，再擠 1～2 dB 而不產生硬削波
    aresample=176400 → alimiter → aresample=44100   # 4 倍超取樣的真峰值限制

alimiter 只看取樣點峰值，但 64 kbps mp3 編碼本身會製造額外過衝。
不做超取樣的話，實測有 37 條真峰值衝到 0 dBTP 以上，會在解碼端削波。

最後還有一輪修正：編碼完再量一次真峰值，凡是超過 -0.5 dBTP 的，
把限制器天花板往下降 1 dB 重編，最多降到 -7 dBFS。實測 600 條裡有
103 條需要這輪修正。

不要改回單趟 loudnorm。ffmpeg 4.x 的 loudnorm 對這種 2～6 秒、
input_lra 為 0 的短句，第一趟回報的 target_offset 會是離譜的值
（實測有 +19.39 dB），餵回第二趟會把該條反向壓掉將近 20 dB。
"""

import argparse
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

NORM_I = -16.0          # 壓縮前的統一基準
TARGET_I = -10.5        # 壓縮後的目標
TP_CEILING = -0.5       # 編碼後可接受的真峰值上限（dBTP）
CEILS = [0.708, 0.631, 0.562, 0.501, 0.447]   # -3 ~ -7 dBFS
COMP = "acompressor=threshold=-18dB:ratio=3:attack=5:release=120:knee=6:makeup=1"

ROMA = {
    "コハク": "kohaku",
    "まお": "mao",
    "まい": "mai",
    "るな": "runa",
    "中2": "chuni",
    "花音": "kanon",
}


def read_synth(path):
    src = open(path, encoding="utf-8").read()
    speaker = eval(re.search(r"^SPEAKER = (.*)$", src, re.M).group(1))
    domain = eval(re.search(r"^DOMAIN = (.*)$", src, re.M).group(1))
    lines = eval("[" + re.search(r"^LINES = \[(.*?)^\]", src, re.M | re.S).group(1) + "]")
    return speaker, domain, lines


def lufs(path):
    """回傳 (integrated LUFS, true peak dBTP)。"""
    p = subprocess.run(
        ["ffmpeg", "-nostdin", "-i", path, "-af",
         "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"],
        capture_output=True, text=True)
    s = p.stderr
    d = json.loads(s[s.rindex("{"):s.rindex("}") + 1])
    return float(d["input_i"]), float(d["input_tp"])


def limiter(ceil):
    return ("aresample=176400,"
            "alimiter=limit=%s:attack=5:release=60:level=disabled,"
            "aresample=44100" % ceil)


def process(job):
    tmp = job["mp3"] + ".stage1.wav"
    src_i, _ = lufs(job["wav"])
    subprocess.run(
        ["ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", job["wav"],
         "-af", "volume=%.2fdB,%s" % (NORM_I - src_i, COMP),
         "-ac", "1", "-c:a", "pcm_s16le", tmp],
        check=True)
    mid_i, _ = lufs(tmp)

    out_i = out_tp = None
    used = CEILS[0]
    for ceil in CEILS:
        subprocess.run(
            ["ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", tmp,
             "-af", "volume=%.2fdB,asoftclip=type=tanh,%s" % (TARGET_I - mid_i, limiter(ceil)),
             "-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", job["mp3"]],
            check=True)
        out_i, out_tp = lufs(job["mp3"])
        used = ceil
        if out_tp <= TP_CEILING:
            break

    os.remove(tmp)
    return src_i, out_i, out_tp, used


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="含 6 個角色資料夾的目錄")
    ap.add_argument("--out", default="app/src/main/assets")
    ap.add_argument("--tmp", default=".assets_tmp")
    ap.add_argument("--jobs", type=int, default=3)
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    os.makedirs(a.tmp, exist_ok=True)

    jobs = []
    for jp, roma in ROMA.items():
        synth = os.path.join(a.src, jp, "synth_%s.py" % jp)
        if not os.path.exists(synth):
            sys.exit("找不到 %s" % synth)
        speaker, domain, lines = read_synth(synth)
        for i, text in enumerate(lines, 1):
            wav = os.path.join(a.src, jp, "out_%s" % jp, "%s_%03d.wav" % (jp, i))
            if not os.path.exists(wav):
                sys.exit("找不到 %s" % wav)
            jobs.append({
                "id": "%s_%03d" % (roma, i),
                "file": "%s_%03d.mp3" % (roma, i),
                "character": speaker,
                "text": text,
                "domain": domain,
                "wav": wav,
                "mp3": os.path.join(a.tmp, "%s_%03d.mp3" % (roma, i)),
            })

    print("共 %d 條，開始處理（量測 → 壓縮 → 提升 → 限制 → 修正）……" % len(jobs))
    with ThreadPoolExecutor(a.jobs) as ex:
        results = list(ex.map(process, jobs))

    print("打包 voices.bin……")
    index = []
    offset = 0
    with open(os.path.join(a.out, "voices.bin"), "wb") as bf:
        for j in jobs:
            data = open(j["mp3"], "rb").read()
            bf.write(data)
            index.append({
                "id": j["id"], "file": j["file"], "character": j["character"],
                "text": j["text"], "domain": j["domain"],
                "offset": offset, "length": len(data),
            })
            offset += len(data)

    with open(os.path.join(a.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    with open("loudness_report.csv", "w", encoding="utf-8") as f:
        f.write("id,character,source_lufs,out_lufs,out_dbtp,limiter_ceiling\n")
        for j, (si, oi, ot, ceil) in zip(jobs, results):
            f.write("%s,%s,%.2f,%.2f,%.2f,%s\n" % (j["id"], j["character"], si, oi, ot, ceil))

    outs = [r[1] for r in results]
    tps = [r[2] for r in results]
    outs.sort()
    print("完成：%d 條 / voices.bin %.1f MiB" % (len(index), offset / 1048576.0))
    print("  響度中位 %.2f LUFS、全距 %.2f dB" % (outs[len(outs) // 2], outs[-1] - outs[0]))
    print("  最大真峰值 %.2f dBTP、超過 0 的條數 %d" % (max(tps), sum(1 for t in tps if t > 0)))
    print("報表：loudness_report.csv")


if __name__ == "__main__":
    main()
