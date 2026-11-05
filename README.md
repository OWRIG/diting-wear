# 谛听 · Diting Wear

> 谛听，地藏菩萨座下的神兽，伏地而听，能辨万物之声。

一个开源的、可以戴一整天的轻量录音器：把你一天说过的话变成文字和总结。**原始录音不留存，只保留文字。**

## 工作方式

```
佩戴设备 ──BLE/Opus──▶ 安卓手机 ──加密上传──▶ 服务器（只当信箱用）◀──拉取── 家里的 Mac（本地转写和总结）
```

- **设备**：Seeed XIAO nRF54L15 Sense 加一块小锂电池，目标重量 ≤ 20g
- **音频**：16kHz，Opus 约 16kbps，实测每小时约 6MB
- **转写**：在自己的 Mac 上本地运行（Qwen3-ASR / SenseVoice），音频不经过任何第三方云服务

## 目录

| 目录 | 内容 |
|---|---|
| `firmware/` | 设备固件（Zephyr / nRF Connect SDK），见 [firmware/README.md](firmware/README.md) |
| `android/` | 安卓 App（Kotlin + Compose）：蓝牙接收音频，每 5 分钟存一个 Ogg Opus 文件 |
| `server/mailbox/` | 服务器信箱（FastAPI + Docker），只做中转 |
| `mac/` | 家里 Mac 的取件程序：取录音 → 本地转写 → 回传文字，见 [docs/mac-worker-setup.md](docs/mac-worker-setup.md) |
| `tools/` | 电脑端工具，比如 BLE 接收测试脚本 |
| `docs/` | 部署和开发文档 |
| `shopping-list.md` | 硬件购物清单 |

## 进度

- [x] 硬件验证：麦克风、BLE、烧录
- [x] 录音固件 v1：麦克风 → Opus → BLE，0 丢包
- [x] 电池供电（402020）和电压检测
- [ ] 续航测试
- [ ] 静音检测（VAD），断连时存进 Flash 缓存
- [x] 安卓 App：接收、分段存储、波形播放、自动上传
- [x] 服务器信箱（HTTPS）
- [x] Mac 取件程序
- [ ] Mac 本地转写服务（部署中）
- [ ] 手机显示转写文字
- [ ] 每晚总结
- [ ] 端到端加密
- [ ] 外壳

## 第三方代码

- `firmware/lib/opus`：[libopus](https://opus-codec.org/) v1.5.2，BSD 许可证，见 `firmware/lib/opus/COPYING`

## 许可证

[MIT](LICENSE)。第三方代码保留各自的许可证。从 Zephyr 示例改写的测试程序（blinky、ble-test）沿用 Apache-2.0。
