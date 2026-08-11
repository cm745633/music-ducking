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

為什麼要打包成單一檔案：GitHub 網頁上傳一次最多 100 個檔案，
600 個 mp3 得分六批傳。打包後只要拖一次。
App 端用 AssetManager.openFd() 取得 blob 起點，再加上 offset 餵給
MediaPlayer.setDataSource(fd, offset, length)。因此 build.gradle.kts 內
androidResources.noCompress 必須包含 "bin"，否則 openFd() 會失敗。

響度處理採兩段式，不是直接跑 loudnorm 單趟：
  1. 先用 loudnorm 的分析模式量出每條的 integrated loudness
  2. 再以 volume 濾鏡套用「目標 -16 LUFS 減去實測值」的線性增益，接 alimiter 收峰值

原因是 ffmpeg 4.x 的 loudnorm 對這種 2～6 秒、LRA 為 0 的短句，
第一趟回報的 target_offset 會是離譜的值（實測有 +19.39 dB），
把它餵回第二趟會讓該條被反向壓掉將近 20 dB。實測 600 條裡有 48 條中招。
"""

import argparse
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

TARGET_I = -16.0
LIMIT = 0.794          # -2.0 dBFS，留給 mp3 解碼的取樣點間峰值一點餘裕
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


def measure(wav):
    p = subprocess.run(
        ["ffmpeg", "-nostdin", "-i", wav, "-af",
         "loudnorm=I=%s:TP=-1.5:LRA=11:print_format=json" % TARGET_I,
         "-f", "null", "-"],
        capture_output=True, text=True)
    err = p.stderr
    return json.loads(err[err.rindex("{"):err.rindex("}") + 1])


def encode(wav, mp3, measured_i):
    gain = TARGET_I - float(measured_i)
    af = "volume=%.2fdB,alimiter=limit=%s:attack=5:release=60:level=disabled" % (gain, LIMIT)
    subprocess.run(
        ["ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", wav,
         "-af", af, "-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", mp3],
        check=True)
    return gain


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

    print("共 %d 條，開始量測響度……" % len(jobs))
    with ThreadPoolExecutor(a.jobs) as ex:
        stats = list(ex.map(lambda j: measure(j["wav"]), jobs))

    print("轉檔中……")
    with ThreadPoolExecutor(a.jobs) as ex:
        gains = list(ex.map(lambda t: encode(t[0]["wav"], t[0]["mp3"], t[1]["input_i"]),
                            zip(jobs, stats)))

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
        f.write("id,input_lufs,gain_db\n")
        for j, s, g in zip(jobs, stats, gains):
            f.write("%s,%s,%.2f\n" % (j["id"], s["input_i"], g))

    print("完成：%d 條 / voices.bin %.1f MB" % (len(index), offset / 1048576.0))
    print("報表：loudness_report.csv")


if __name__ == "__main__":
    main()
