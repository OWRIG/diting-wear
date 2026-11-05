# 家里 Mac：安装取件程序

> 写给**家里那台 Mac 上的 AI 助手**。前提：已经按 [mac-asr-deploy-guide.md](mac-asr-deploy-guide.md) 部署好本机转写服务，并且 `curl http://127.0.0.1:8765/health` 能返回结果。

## 取件程序是做什么的

`mac/worker.py` 每分钟去服务器信箱看一次，有新录音就：
下载 → 交给本机转写服务（`POST 127.0.0.1:8765/transcribe`）→ 文字存进 `~/diting/diting.db` → 把文字传回信箱（信箱随即删除音频）→ 删除本地临时音频。

**原始录音不会留在这台 Mac 上。**

## 步骤

1. 拿到代码：`git clone https://github.com/OWRIG/diting-wear.git ~/diting-wear`（已经有了就 `git pull`）
2. 写配置文件 `~/.config/diting/worker.env`，权限设为 600。**WORKER_TOKEN 由用户提供**（在另一台 Mac 的 `~/personal/record/.secrets/mailbox.env` 里），不要打印到终端，也不要写进任何 git 仓库：
   ```
   SERVER_URL=https://diting.missyangwx.online
   WORKER_TOKEN=<用户提供>
   ASR_URL=http://127.0.0.1:8765
   ```
3. 先手动跑一轮：`cd ~/diting-wear && uv run --script mac/worker.py --once`
   - 信箱是空的话，什么都不会发生，这是正常的。
   - 有录音的话，会看到 "xxx.opus: 300s 音频，说话 …s，转写用时 …s" 这样的日志。
4. 装成开机自启：`sh mac/install.sh`
5. 确认在运行：`launchctl list | grep diting`，再看日志 `tail -f ~/diting/logs/worker.log`

## 验收

- [ ] `--once` 能跑完，不报错
- [ ] 服务器上的录音被取走以后，`sqlite3 ~/diting/diting.db "select name, substr(text,1,50) from transcripts"` 能看到文字
- [ ] `~/diting` 下面没有残留的 `.opus` 文件
- [ ] 重启电脑后，取件程序会自己启动

## 常见问题

- **401**：WORKER_TOKEN 不对
- **连不上 127.0.0.1:8765**：转写服务没有启动
- **Mac 睡眠时不工作**：这是正常的。醒来后会自动补处理（信箱里的录音最多保留 7 天）
