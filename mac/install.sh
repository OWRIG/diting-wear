#!/bin/sh
# 把取件程序装成开机自启的后台服务（launchd）。在家里那台 Mac 上运行：sh mac/install.sh
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
UV="$(command -v uv)"
CONF="$HOME/.config/diting/worker.env"
PLIST="$HOME/Library/LaunchAgents/io.github.owrig.diting.worker.plist"
[ -n "$UV" ] || { echo "先安装 uv：brew install uv"; exit 1; }
[ -f "$CONF" ] || { echo "缺少配置文件 $CONF（见 docs/mac-worker-setup.md）"; exit 1; }
mkdir -p "$HOME/diting/logs" "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.github.owrig.diting.worker</string>
  <key>ProgramArguments</key><array>
    <string>$UV</string><string>run</string><string>--script</string><string>$REPO/mac/worker.py</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$HOME/diting/logs/worker.log</string>
  <key>StandardErrorPath</key><string>$HOME/diting/logs/worker.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "已安装并启动。日志：tail -f ~/diting/logs/worker.log"
