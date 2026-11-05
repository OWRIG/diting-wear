# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""谛听 · 家里 Mac 的取件程序

循环：信箱里有新录音 → 下载 → 交给本机转写服务 → 把文字传回信箱（信箱随即删除音频）
     → 文字存进本地 SQLite → 删除本地音频（原始录音不留存）

配置文件 ~/.config/diting/worker.env：
    SERVER_URL=https://你的信箱域名
    WORKER_TOKEN=...
    ASR_URL=http://127.0.0.1:8765        # 本机转写服务（见 docs/mac-asr-deploy-guide.md）
    ASR_ENGINE=qwen                      # 可选，传给转写服务
用法：
    uv run --script mac/worker.py          # 一直运行
    uv run --script mac/worker.py --once   # 只处理一轮
"""
import json
import logging
import os
import sqlite3
import sys
import tempfile
import time
from pathlib import Path

import httpx

CONF = Path(os.environ.get("DITING_CONF", "~/.config/diting/worker.env")).expanduser()
HOME = Path(os.environ.get("DITING_HOME", "~/diting")).expanduser()
POLL_SECONDS = 60

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("worker")


def load_conf() -> dict:
    conf = {}
    for line in CONF.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            conf[k.strip()] = v.strip()
    for k in ("SERVER_URL", "WORKER_TOKEN", "ASR_URL"):
        if not conf.get(k):
            sys.exit(f"配置缺少 {k}：{CONF}")
    return conf


def open_db() -> sqlite3.Connection:
    HOME.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(HOME / "diting.db")
    db.execute("""create table if not exists transcripts (
        name text primary key,          -- 录音文件名 YYYYMMDD-HHMMSS.opus（= 开始时间）
        started_at text,                -- ISO 时间
        duration_s real, speech_s real,
        engine text, model text,
        text text, segments text,       -- segments 是 JSON
        created_at text default (datetime('now','localtime')))""")
    return db


def started_at(name: str) -> str:
    s = name.removesuffix(".opus")
    return f"{s[0:4]}-{s[4:6]}-{s[6:8]}T{s[9:11]}:{s[11:13]}:{s[13:15]}"


def process_one(c: dict, mailbox: httpx.Client, asr: httpx.Client, db: sqlite3.Connection, name: str):
    with tempfile.TemporaryDirectory() as tmp:
        audio = Path(tmp) / name
        with mailbox.stream("GET", f"/v1/audio/{name}") as r:
            r.raise_for_status()
            with audio.open("wb") as f:
                for chunk in r.iter_bytes():
                    f.write(chunk)

        t0 = time.time()
        with audio.open("rb") as f:
            r = asr.post("/transcribe", files={"file": (name, f, "audio/ogg")},
                         data={"engine": c.get("ASR_ENGINE", "")} if c.get("ASR_ENGINE") else None)
        r.raise_for_status()
        result = r.json()
        log.info("%s: %.0fs 音频，说话 %.0fs，转写用时 %.1fs，%d 字",
                 name, result.get("duration_s", 0), result.get("speech_s", 0),
                 time.time() - t0, len(result.get("text", "")))
        # 临时目录退出时音频文件随之删除

    payload = {
        "started_at": started_at(name),
        "duration_s": result.get("duration_s"),
        "speech_s": result.get("speech_s"),
        "engine": result.get("engine"),
        "model": result.get("model"),
        "text": result.get("text", ""),
        "segments": result.get("segments", []),
    }
    db.execute("insert or replace into transcripts(name, started_at, duration_s, speech_s, engine, model, text, segments)"
               " values (?,?,?,?,?,?,?,?)",
               (name, payload["started_at"], payload["duration_s"], payload["speech_s"],
                payload["engine"], payload["model"], payload["text"],
                json.dumps(payload["segments"], ensure_ascii=False)))
    db.commit()
    # 先存本地，再回传；回传成功后信箱会删除音频
    mailbox.put(f"/v1/text/{name}", json=payload).raise_for_status()


def run_once(c: dict, db: sqlite3.Connection) -> int:
    headers = {"Authorization": f"Bearer {c['WORKER_TOKEN']}"}
    with httpx.Client(base_url=c["SERVER_URL"], headers=headers, timeout=60) as mailbox, \
         httpx.Client(base_url=c["ASR_URL"], timeout=600, trust_env=False) as asr:
        items = mailbox.get("/v1/audio").raise_for_status().json()["items"]
        done = 0
        for it in items:
            try:
                process_one(c, mailbox, asr, db, it["name"])
                done += 1
            except Exception as e:      # 一段失败不影响其他段，下一轮再试
                log.warning("%s 处理失败：%s", it["name"], e)
        return done


def main():
    c = load_conf()
    db = open_db()
    once = "--once" in sys.argv
    log.info("取件程序启动：信箱 %s，转写服务 %s", c["SERVER_URL"], c["ASR_URL"])
    while True:
        try:
            n = run_once(c, db)
            if n:
                log.info("本轮处理 %d 段", n)
        except Exception as e:
            log.warning("本轮失败：%s", e)
        if once:
            break
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
