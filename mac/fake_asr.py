# /// script
# requires-python = ">=3.11"
# dependencies = ["fastapi", "uvicorn", "python-multipart"]
# ///
"""假的转写服务，只用来测试取件流程：返回固定文字。接口和 docs/mac-asr-deploy-guide.md 一致。"""
from fastapi import FastAPI, File, Form, UploadFile
import uvicorn

app = FastAPI()

@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), engine: str = Form("")):
    data = await file.read()
    import random
    rnd = random.Random(file.filename)
    def voice(seed):   # 同一个 seed = 同一个人，加一点随机扰动
        r = random.Random(seed); base = [r.gauss(0, 1) for _ in range(192)]
        return [x + rnd.gauss(0, 0.3) for x in base]
    # 文件名秒数为偶数：只有"甲"；奇数：甲和乙对话
    two = int(file.filename[13:15]) % 2 == 1
    segs = [{"start": 1.0, "end": 3.5, "text": "甲说的第一句话", "speaker": 0}]
    spk = [{"speaker": 0, "speech_s": 20.0, "embedding": voice("甲")}]
    if two:
        segs.append({"start": 4.0, "end": 6.0, "text": "乙回答了一句比较长的话", "speaker": 1})
        spk.append({"speaker": 1, "speech_s": 8.0, "embedding": voice("乙")})
    return {"audio": file.filename, "duration_s": 300.0, "speech_s": 42.0, "engine": "fake",
            "model": "fake", "elapsed_s": 0.01, "segments": segs, "speakers": spk,
            "text": "".join(s["text"] for s in segs)}

@app.get("/health")
def health():
    return {"ok": True}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8765)
