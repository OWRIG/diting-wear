"""跨录音认人：把转写服务给出的"文件内说话人 + 声纹"映射成全局编号 spk_001、spk_002…

声纹（embedding）只存在本机 SQLite 里，绝不上传。服务器上只有编号、名字和合并关系。
"""
import json
import math
import os
import sqlite3

# 余弦相似度达到这个值才认为是同一个人。要根据家里 Mac 的实测数值调整（见 docs/mac-speaker-guide.md 验收第 3 条）
THRESHOLD = float(os.environ.get("SPEAKER_THRESHOLD", "0.6"))
MAX_WEIGHT = 600.0          # 声纹均值最多按 600 秒语音加权，之后新声音也能逐渐影响它（比如感冒）
MIN_SPEECH_S = 1.5          # 少于这么多语音的声纹不可靠，不参与认人


def init(db: sqlite3.Connection):
    db.execute("""create table if not exists speakers (
        id text primary key,
        centroid text,              -- JSON 数组，L2 归一化
        weight real default 0,      -- 已累计的语音秒数（上限 MAX_WEIGHT）
        merged_into text,           -- 被用户合并到哪个人
        created_at text default (datetime('now','localtime')))""")
    db.commit()


def _norm(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def _cos(a, b):
    return sum(x * y for x, y in zip(a, b))


def _all(db):
    # 被合并的人的声纹也参与比对（同一个人的"另一种声音"），匹配后再沿合并链找到最终的人
    return [(sid, json.loads(c), w) for sid, c, w in
            db.execute("select id, centroid, weight from speakers")]


def _new_id(db) -> str:
    n = db.execute("select count(*) from speakers").fetchone()[0]
    while True:
        n += 1
        sid = f"spk_{n:03d}"
        if not db.execute("select 1 from speakers where id=?", (sid,)).fetchone():
            return sid


def _update(db, sid, emb, speech_s):
    c, w = db.execute("select centroid, weight from speakers where id=?", (sid,)).fetchone()
    c = json.loads(c)
    add = min(speech_s, 60.0)
    new = _norm([x * w + y * add for x, y in zip(c, emb)])
    db.execute("update speakers set centroid=?, weight=? where id=?",
               (json.dumps(new), min(w + add, MAX_WEIGHT), sid))


def resolve(db, sid):
    """沿合并链找到最终的人"""
    seen = set()
    while sid and sid not in seen:
        seen.add(sid)
        row = db.execute("select merged_into from speakers where id=?", (sid,)).fetchone()
        if not row or not row[0]:
            return sid
        sid = row[0]
    return sid


def apply_server_merges(db, server_items: dict):
    """同步手机上做的合并/取消合并。声纹各自保留，只记录合并关系。"""
    for sid, info in server_items.items():
        db.execute("update speakers set merged_into=? where id=?", (info.get("merged_into"), sid))
    db.commit()


def assign(db, result: dict) -> tuple[dict, list[str]]:
    """把文件内编号换成全局编号。返回 (本地编号→全局编号, 新出现的全局编号列表)。"""
    local = [s for s in result.get("speakers") or []
             if s.get("embedding") and (s.get("speech_s") or 0) >= MIN_SPEECH_S]
    known = _all(db)
    pairs = []
    for s in local:
        e = _norm(s["embedding"])
        s["_e"] = e
        for sid, c, _ in known:
            pairs.append((_cos(e, c), s["speaker"], sid))
    # 贪心：相似度从高到低配对，同一个文件里的两个人不会被认成同一个人
    mapping, used = {}, set()
    for sim, lsp, sid in sorted(pairs, reverse=True):
        if sim < THRESHOLD:
            break
        if lsp in mapping or sid in used:
            continue
        mapping[lsp] = sid
        used.add(sid)
    new_ids = []
    for s in local:
        if s["speaker"] in mapping:
            _update(db, mapping[s["speaker"]], s["_e"], s.get("speech_s") or 0)
        else:
            sid = _new_id(db)
            db.execute("insert into speakers(id, centroid, weight) values (?,?,?)",
                       (sid, json.dumps(s["_e"]), min(s.get("speech_s") or 0, 60.0)))
            mapping[s["speaker"]] = sid
            new_ids.append(sid)
    db.commit()
    # 输出最终的人（沿合并链），新出现的人原样返回
    return {k: resolve(db, v) for k, v in mapping.items()}, new_ids
