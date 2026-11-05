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
    return {"audio": file.filename, "duration_s": 300.0, "speech_s": 42.0, "engine": "fake",
            "model": "fake", "elapsed_s": 0.01,
            "segments": [{"start": 1.0, "end": 3.5, "text": f"（测试）收到 {len(data)} 字节"}],
            "text": f"（测试）收到 {len(data)} 字节"}

@app.get("/health")
def health():
    return {"ok": True}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8765)
