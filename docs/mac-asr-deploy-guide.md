# 家里 Mac：本地语音转写服务部署指导

> 这份文档是写给**家里那台 Mac 上的 AI 助手**的执行说明。用户是硬件和开发新手，遇到需要他决定或操作的事，用简单的中文问他。

## 0. 背景（先读完再动手）

用户在做一个开源的全天语音记录器：
- 身上戴的设备录音，压缩成 Opus 后用蓝牙传给安卓手机；
- 手机把音频加密后传到一台国内服务器，服务器只当"信箱"用；
- **这台 Mac** 从信箱拉取音频，在本地转成文字，再把文字传回去，每晚生成一份总结。

**这次的任务只有一件**：在这台 Mac 上把**本地语音转写服务**搭好，测出速度和效果。和服务器对接、生成总结都是后面的事，这次**不做**。

机器配置：Apple M5，**16GB 内存**，macOS，网络在中国大陆。

## 1. 硬性规则

1. **音频不准离开这台 Mac**：不调用任何云端转写 API，不把音频上传到任何地方。只有下载模型时可以联网。
2. HTTP 服务**只监听 `127.0.0.1`**，不能对外网或局域网开放。
3. 不用 `sudo`。系统级的依赖只用 Homebrew 装，Python 依赖全部装在项目自己的虚拟环境里（用 `uv`）。
4. 模型统一放在 `~/models/`，代码放在 `~/voice-asr/`（项目名以后会改，先用这个）。
5. 下载单个超过 5GB 的东西之前，先问用户。
6. 不要删除或修改用户的其他文件。
7. 某个库的用法不确定时，**去读它的 README 或源码确认**，不要凭记忆写。哪一步没做成，就如实写进报告，不要假装成功了。

## 2. 网络（中国大陆）

- Hugging Face 用镜像：`export HF_ENDPOINT=https://hf-mirror.com`
- 镜像也不行的话，去 ModelScope（`modelscope download --model <id> --local_dir ...`）。Qwen 官方模型在 ModelScope 上都有。
- pip 或 uv 下载慢的话，可以用清华源：`UV_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple`
- GitHub 下载卡住的话，优先换成 PyPI、HF 镜像或 ModelScope 上的同一份东西。

## 3. 要搭建的内容

### 3.1 基础环境
- `brew install ffmpeg uv`（已经装了就跳过）
- `~/voice-asr/` 用 `uv init` 初始化，Python 版本选 3.12

### 3.2 音频输入
统一先用 ffmpeg 转成 **16kHz、单声道、16-bit PCM**，再交给模型。要能处理的格式：`.wav`、`.ogg`/`.opus`（以后手机会传 Ogg Opus）、`.m4a`、`.mp3`。

### 3.3 VAD 切分（必须做）
用 **Silero VAD** 把长音频切成说话片段，sherpa-onnx 里就带这个模型。要求：
- 去掉静音片段。全天录音大部分都是静音，这样能省很多算力，也能避免模型对着静音"幻觉"出文字。
- 每个片段 **不超过 30 秒**。超过的，在片段内部找停顿处切开。
- 记录每个片段在原音频里的开始和结束时间。

### 3.4 引擎 A（主力）：Qwen3-ASR，跑在 MLX 上
目前中文识别效果最好。下面几个 MLX 版本，**选第一个能跑通的**，并在报告里写明选了哪个、为什么：
1. `mlx-audio`：模型列表里有 `mlx-community/Qwen3-ASR-1.7B-8bit`
2. `mlx-qwen3-asr`（PyPI，源码在 github.com/moona3k/mlx-qwen3-asr）：支持 0.6B 和 1.7B
3. `qwen3-asr-mlx`（PyPI，只支持 1.7B）

**0.6B 和 1.7B 都要测**，最后由报告里的数据决定用哪个。16GB 内存跑 1.7B 的 8bit 版应该没问题，但要实测峰值内存。

### 3.5 引擎 B（备用）：SenseVoice，跑在 sherpa-onnx 上
- 用 PyPI 上的 `sherpa-onnx` 包
- 模型用 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09`。如果有更新的版本，用最新的。
- 用法参考 k2-fsa.github.io/sherpa/onnx/sense-voice 上的 Python API 文档
- 打开标点和逆文本正则化（ITN，把"一百二十"转成"120"这类）

### 3.6 统一接口

**命令行**：
```sh
uv run voice-asr transcribe <音频文件> --engine qwen|sensevoice [--model 0.6b|1.7b] [--out result.json]
```

**HTTP 服务**（FastAPI，只监听 `127.0.0.1:8765`）：
- `POST /transcribe`：上传音频文件，可选参数 `engine`，返回下面格式的 JSON
- `GET /health`：返回当前加载的模型和状态
- 模型启动时加载一次，之后常驻内存，不要每次请求都重新加载

**输出 JSON 格式**（以后对接时依赖这个格式，不要随意改）：
```json
{
  "audio": "文件名",
  "duration_s": 312.4,
  "speech_s": 98.1,
  "engine": "qwen3-asr",
  "model": "Qwen3-ASR-1.7B-8bit",
  "elapsed_s": 12.3,
  "segments": [
    {"start": 3.20, "end": 9.85, "text": "今天下午三点开会。"}
  ],
  "text": "所有片段的文字拼在一起"
}
```

### 3.7 测试数据
用户会给你几段测试音频，用 AirDrop 传过来，放到 `~/voice-asr/testdata/`：
- `test2.wav`、`test3-optimized.wav`：设备录的，各 20 秒左右，是 Opus 16kbps 解码后的音质
- 再请用户**用手机录一段 5–10 分钟的日常说话**：最好是自然地聊天，中间夹几个英文词，有停顿、有安静的时候。如果用户愿意，请他再给一份这段话的大致原文，用来算字错率（CER）。

### 3.8 基准测试
每种组合都在所有测试音频上跑一遍：Qwen3-ASR 0.6B、Qwen3-ASR 1.7B、SenseVoice。记录：
- **RTF**（实时率）= 处理耗时 ÷ 音频时长，分别按**整段音频时长**和 **VAD 之后的说话时长**各算一次
- **峰值内存**（用 `/usr/bin/time -l` 看 maximum resident set size）
- **CER**（有原文的话才算）
- **静音和噪声测试**：拿一段 1 分钟的纯静音或环境噪声去跑，看模型会不会编出文字（幻觉）
- 每种组合挑一段输出原文，放进报告里，方便用户直接对比效果

### 3.9 （可选）开机自启
基准测试做完后，如果用户同意，用 **launchd** 让 HTTP 服务开机自动启动：
- plist 放在 `~/Library/LaunchAgents/`
- 日志写到 `~/voice-asr/logs/`
- 服务挂掉后能自动重启

## 4. 验收标准

- [ ] `uv run voice-asr transcribe testdata/test2.wav --engine qwen` 能输出正确格式的 JSON，文字基本正确
- [ ] `--engine sensevoice` 也一样
- [ ] `curl -F file=@testdata/test2.wav http://127.0.0.1:8765/transcribe` 能返回结果
- [ ] 纯静音音频不会编出文字，或者幻觉明显少于不做 VAD 时
- [ ] 1.7B 模型的峰值内存低于 6GB，同时用户正常用电脑不卡
- [ ] 写好 `~/voice-asr/REPORT.md`（格式见第 5 节）

## 5. 报告格式（REPORT.md）

```markdown
## 环境
macOS 版本 / Python 版本 / 各个库的版本 / 用了哪个 Qwen3-ASR 的 MLX 版本，为什么选它

## 结果
| 引擎 | 模型 | RTF(整段) | RTF(说话部分) | 峰值内存 | CER | 静音有没有幻觉 |
|---|---|---|---|---|---|---|

## 效果样例
（同一段音频，三种组合各自的输出原文）

## 推荐
推荐默认用哪个，理由是什么

## 遇到的问题
（没做成的、需要用户决定的事）
```

把报告的内容发给用户，用户会转给负责整个项目的另一个 AI。
