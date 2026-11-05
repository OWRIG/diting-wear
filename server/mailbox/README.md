# 谛听信箱（服务器端）

只做中转：手机 `PUT /v1/audio/{name}` → Mac `GET /v1/audio`、`GET /v1/audio/{name}` → Mac `PUT /v1/text/{name}`（服务器随即删除音频）→ 手机 `GET /v1/text?since=`。

- 鉴权：`Authorization: Bearer <token>`，手机和 Mac 各用一个 token（`.env` 里的 `PHONE_TOKEN` 和 `WORKER_TOKEN`）
- 文件名格式：`YYYYMMDD-HHMMSS.opus`
- 没被取走的音频 7 天后自动删除，文字 30 天后自动删除
- 部署：`docker compose up -d --build`，并在 Caddy 里加一个站点 `reverse_proxy diting-mailbox:8000`

> v1 只用了 HTTPS 加 token。端到端加密（让服务器也看不到内容）放在下一版。
