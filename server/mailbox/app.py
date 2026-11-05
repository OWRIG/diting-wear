"""谛听信箱：手机把音频投进来，家里的 Mac 取走转写，再把文字投回来，手机取走文字。

服务器只做中转，不做任何处理。音频被 Mac 取走并确认后立刻删除；
超过 AUDIO_TTL_DAYS 天没被取走的音频也会自动删除。
"""
import json
import os
import re
import secrets
import time
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse

DATA = Path(os.environ.get("DATA_DIR", "/data"))
INBOX = DATA / "inbox"      # 待转写的音频
OUTBOX = DATA / "outbox"    # 转写好的文字
PHONE_TOKEN = os.environ["PHONE_TOKEN"]
WORKER_TOKEN = os.environ["WORKER_TOKEN"]
MAX_AUDIO_BYTES = 20 * 1024 * 1024
AUDIO_TTL_DAYS = float(os.environ.get("AUDIO_TTL_DAYS", "7"))
TEXT_TTL_DAYS = float(os.environ.get("TEXT_TTL_DAYS", "30"))
NAME_RE = re.compile(r"^\d{8}-\d{6}\.opus$")
SPK_RE = re.compile(r"^spk_\d{3,6}$")
SPEAKERS = DATA / "speakers.json"   # 只有编号、名字、合并关系、首次出现信息；声纹只存在家里 Mac 上

for d in (INBOX, OUTBOX):
    d.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="diting-mailbox", docs_url=None, redoc_url=None, openapi_url=None)


def _auth(expected: str):
    def check(authorization: str = Header(default="")):
        token = authorization.removeprefix("Bearer ").strip()
        if not secrets.compare_digest(token.encode(), expected.encode()):
            raise HTTPException(401, "bad token")
    return check


phone = Depends(_auth(PHONE_TOKEN))
worker = Depends(_auth(WORKER_TOKEN))


def _either(authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip().encode()
    if not (secrets.compare_digest(token, PHONE_TOKEN.encode())
            or secrets.compare_digest(token, WORKER_TOKEN.encode())):
        raise HTTPException(401, "bad token")


anyone = Depends(_either)


def _name(name: str) -> str:
    if not NAME_RE.match(name):
        raise HTTPException(400, "bad name")
    return name


def _cleanup():
    now = time.time()
    for d, ttl in ((INBOX, AUDIO_TTL_DAYS), (OUTBOX, TEXT_TTL_DAYS)):
        for f in d.iterdir():
            if now - f.stat().st_mtime > ttl * 86400:
                f.unlink(missing_ok=True)


@app.get("/v1/health")
def health():
    return {"ok": True, "inbox": sum(1 for _ in INBOX.glob("*.opus"))}


# ---------- 手机 ----------

@app.put("/v1/audio/{name}", dependencies=[phone])
async def put_audio(name: str, request: Request):
    """上传一段音频。同名重复上传是安全的（会覆盖）。"""
    name = _name(name)
    body = await request.body()
    if not body or len(body) > MAX_AUDIO_BYTES:
        raise HTTPException(413, "empty or too large")
    if (OUTBOX / f"{name}.json").exists():
        return {"ok": True, "status": "already transcribed"}
    tmp = INBOX / f".{name}.tmp"
    tmp.write_bytes(body)
    tmp.replace(INBOX / name)          # 原子操作，Mac 不会读到写了一半的文件
    _cleanup()
    return {"ok": True, "size": len(body)}


@app.get("/v1/text", dependencies=[phone])
def list_text(since: str = Query(default="")):
    """取回 since 之后（按文件名排序）的所有转写结果。"""
    out = []
    for f in sorted(OUTBOX.glob("*.json")):
        if f.stem > since:
            out.append(json.loads(f.read_text()))
    return {"items": out}


# ---------- 家里的 Mac ----------

@app.get("/v1/audio", dependencies=[worker])
def list_audio():
    return {"items": [{"name": f.name, "size": f.stat().st_size}
                      for f in sorted(INBOX.glob("*.opus"))]}


@app.get("/v1/audio/{name}", dependencies=[worker])
def get_audio(name: str):
    f = INBOX / _name(name)
    if not f.exists():
        raise HTTPException(404)
    return FileResponse(f, media_type="audio/ogg")


@app.put("/v1/text/{name}", dependencies=[worker])
async def put_text(name: str, request: Request):
    """Mac 上传转写结果（JSON），成功后服务器删除对应音频。"""
    name = _name(name)
    data = await request.json()
    data["name"] = name
    tmp = OUTBOX / f".{name}.json.tmp"
    tmp.write_text(json.dumps(data, ensure_ascii=False))
    tmp.replace(OUTBOX / f"{name}.json")
    (INBOX / name).unlink(missing_ok=True)
    return {"ok": True}


# ---------- 说话人名单（手机和 Mac 都能读） ----------

def _load_speakers() -> dict:
    return json.loads(SPEAKERS.read_text()) if SPEAKERS.exists() else {}


def _save_speakers(d: dict):
    tmp = DATA / ".speakers.json.tmp"
    tmp.write_text(json.dumps(d, ensure_ascii=False, indent=1))
    tmp.replace(SPEAKERS)


@app.get("/v1/speakers", dependencies=[anyone])
def list_speakers():
    return {"items": _load_speakers()}


@app.put("/v1/speakers/{sid}", dependencies=[worker])
async def worker_upsert_speaker(sid: str, request: Request):
    """Mac 登记新说话人或更新首次出现信息。不会覆盖用户起的名字和合并关系。"""
    if not SPK_RE.match(sid):
        raise HTTPException(400, "bad id")
    body = await request.json()
    d = _load_speakers()
    cur = d.get(sid, {})
    for k in ("first_seen", "sample", "sample_name", "sample_start"):
        if k in body and k not in cur:
            cur[k] = body[k]
    cur.setdefault("name", None)
    cur.setdefault("merged_into", None)
    d[sid] = cur
    _save_speakers(d)
    return {"ok": True, "speaker": cur}


@app.patch("/v1/speakers/{sid}", dependencies=[phone])
async def phone_edit_speaker(sid: str, request: Request):
    """手机起名字 {"name": "老王"} 或合并 {"merged_into": "spk_001"}（null 表示取消合并）。"""
    d = _load_speakers()
    if sid not in d:
        raise HTTPException(404)
    body = await request.json()
    if "name" in body:
        name = (body["name"] or "").strip()[:40]
        d[sid]["name"] = name or None
    if "merged_into" in body:
        tgt = body["merged_into"]
        if tgt is not None:
            if tgt not in d or tgt == sid:
                raise HTTPException(400, "bad merge target")
            # 防止合并成环：沿着目标的合并链走，不能回到自己
            seen, cur = {sid}, tgt
            while cur:
                if cur in seen:
                    raise HTTPException(400, "merge cycle")
                seen.add(cur)
                cur = d.get(cur, {}).get("merged_into")
        d[sid]["merged_into"] = tgt
    _save_speakers(d)
    return {"ok": True, "speaker": d[sid]}
