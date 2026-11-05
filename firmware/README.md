# 固件（XIAO nRF54L15 Sense）

## 环境
- nRF Connect SDK v3.4.2，装在 `/opt/nordic/ncs`（用 `nrfutil sdk-manager install v3.4.2 --region cn` 安装，必须用国内服务器，否则下载会卡住）
- 烧录：用板载的 CMSIS-DAP 加 pyocd（工具链自带）。openocd 没装，用不到
- 串口：`/dev/cu.usbmodem*`，115200

## 常用命令
```sh
./flash.sh mic-level                      # 编译并烧录某个项目
./env.sh python3 $PWD/serial_read.py 10   # 读 10 秒串口输出
./env.sh pyocd reset -t nrf54l            # 重启板子
```

## 项目
| 目录 | 作用 | 状态 |
|---|---|---|
| blinky | 绿灯快闪（0.1 秒切换一次） | ✅ 已验证 |
| mic-level | PDM 麦克风音量表，从串口输出 | ✅ 已验证（增益偏低，峰值约 -17 dBFS） |
| ble-test | BLE 心率示例，设备名 "RIG DAY LIFE INPUT (VOICE)" | ✅ 已验证，nRF Connect 能连上并收到数据 |
| **recorder** | **录音固件**：PDM 16kHz → 数字增益 ×4 → Opus（定点、16kbps VBR、复杂度 1）→ BLE 通知 | ✅ 0 丢包，约 1.6KB/s，编码 4.7ms/帧 |

## 录音固件 BLE 协议
- 服务 `5a1d0001-7e3c-4b8a-9f00-524947000000`
- 音频特征 `5a1d0002-…`（notify）：每个包 = `[seq 低][seq 高][一帧 Opus 20ms]`
- 手机/电脑订阅后板子才开麦；需要 MTU ≥ ~125（macOS 会协商到 247）
- Mac 测试接收：`uv run --script tools/ble_receiver.py 20 out.wav`（终端 App 要有蓝牙权限）

## libopus
- `lib/opus` 是 v1.5.2 源码，`lib/opus.cmake` 定义了编译清单和宏（FIXED_POINT、OPUS_ARM_INLINE_EDSP）
- 打开 EDSP 后编码耗时从 11ms 降到 4.7ms，复杂度 3 降到 1 后音质听不出区别
