# 英语搭档提醒服务

这是一个可选的、单用户的提醒收件箱服务。它使用 Python 3.12+ 标准库和 SQLite，适合先放在 2 核 / 2 GB 的小服务器上。无需 Redis、数据库服务、GPU 或模型权重。

服务每 30 秒检查持久化的提醒计划，按手机同步的时区生成每天 4 次随机提醒和 21:30 回顾。可以调用服务器单独配置的 OpenAI 兼容接口生成傲娇文案；未配置或请求失败时，使用结合真实进度的本地文案。这里**没有部署 MOSS-TTS**。

这不是魅族厂商推送服务：安卓客户端拉取收件箱后显示系统通知。省电策略、Doze、断网、强行停止应用均可能推迟或阻止接收。21:30 是服务器计划时间，不是承诺手机精确到达的时间。不要同时启用客户端本地计划和服务器计划，否则会重复提醒。

## 本地运行

Linux（仓库根目录）：

```sh
export REMINDER_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
python3 server/app.py
```

默认仅监听 `127.0.0.1:8787`。`/health` 可用于健康检查；其他路径均要求 `Authorization: Bearer <REMINDER_TOKEN>`。启动失败时若提示缺少时区库，Linux 安装系统 `tzdata`；Windows 本地测试可安装 Python 的 `tzdata` 包。

`.env` 文件由 Docker Compose 加载，直接运行 `python app.py` 不会自动读取它。不要把令牌或 API Key 写到命令行参数、提交到 GitHub 或发到聊天里。

## 验证

```sh
python3 -W error::ResourceWarning -m unittest discover -s server -v
```

测试覆盖 API 认证与字段验证、随机时刻和间隔、时区、计划持久化、并发去重、错过提醒不补发、达成目标取消、午夜进度重置、关闭时取消、重复确认和过期清理。测试不访问模型提供商。

## 行为约定

- 默认 09:00—21:00 之间随机 4 次，彼此至少间隔 90 分钟；21:30 固定回顾。这为回顾留出至少 30 分钟，全部通知 22:00 前失效。
- 今天首次收到有效学习快照后启动计划。随机时刻写入 SQLite，重启或重复同步不会重新抽签。
- 超过计划时间 15 分钟仍未生成的消息直接过期，不在服务器恢复时连发积压提醒。
- 随机消息最长有效 60 分钟；晚间回顾有效至 22:00。收件箱最多返回最新一条，旧未读消息会合并丢弃。
- 达成当天学习分钟目标后，取消随机提醒，保留总结。取消过程中正在生成的消息也不会重新发布。
- 没有当天快照时，不把昨天的已学分钟、生词数当成今天的进度。超过 72 小时没有新快照则暂停提醒。
- 用户关闭提醒要同步 `remindersEnabled:false`；重新启用仅恢复未来提醒。手机断网时无法立即告诉服务器，但客户端应自行停止显示。
- 本服务是一个用户、一份学习快照、一个访问令牌；不适用于多个账号或多个互相独立的学习者。

## API v1

完整契约和部署步骤见 [服务器部署文档](../docs/server-deployment.md)。公开健康检查：

```http
GET /health
```

返回 `200 {"status":"ok","version":1}`，不披露配置和学习数据。

```http
PUT /v1/snapshot
Authorization: Bearer <token>
Content-Type: application/json

{
  "localDate": "2026-09-16",
  "timezone": "Asia/Shanghai",
  "studiedMinutes": 35,
  "targetMinutes": 120,
  "newWords": 12,
  "reviewedWords": 20,
  "dueWords": 8,
  "weakWords": ["although"],
  "remindersEnabled": true
}
```

`localDate` 必须是发送时 `timezone` 对应的当天日期，不要照抄示例日期。除 `weakWords` 外字段都必填，未知字段拒绝；请求上限 16 KiB。`timezone` 为 IANA 标识。整数范围：`studiedMinutes` 0—1440，`targetMinutes` 15—600，词数 0—10000。`weakWords` 可省略或传空数组，最多 10 条，只接受英文字母、空格、连字符、撇号，每条最多 40 字符。不接受聊天历史、手机 API Key 等字段。成功返回 `200 {"ok":true}`。重复发送只覆盖同一学习快照，不创建额外计划。

```http
GET /v1/inbox
Authorization: Bearer <token>
```

```json
{
  "reminders": [{
    "id": "5bde1d06-01dd-45be-a12c-501d3a874906",
    "kind": "recap",
    "title": "英语搭档 · 晚间回顾",
    "body": "今天学了 120 分钟。还不错嘛……明天继续。",
    "scheduledAt": "2026-09-16T13:30:00+00:00",
    "expiresAt": "2026-09-16T14:00:00+00:00"
  }]
}
```

无消息时数组为空。`kind` 为 `random` 或 `recap`；时间是 ISO 8601 UTC，可按 `Instant` 解析。客户端应持久化已显示的消息 ID，重试只确认、不要重复通知。接收后仍检查本地静音时间和学习进度；手机刚完成学习但同步尚未成功时，以手机的最新记录为准。

```http
POST /v1/reminders/5bde1d06-01dd-45be-a12c-501d3a874906/ack
Authorization: Bearer <token>
```

无请求体，成功 `204`；重复确认 `204`；不存在的 ID `404`。客户端在本地保存通知 ID 并显示/决定跳过后确认，可容忍重复拉取。没有“确认等于学完”的含义。

## 保存的数据

SQLite 仅保存最新一次统计快照、最多 10 个可选薄弱词、提醒计划和已生成消息。提醒记录保留 30 天。不会接收或保存手机上的 API Key、完整词库、聊天记录、音频。模型服务调用仅发送这份精简统计；服务器的模型 API Key 通过环境变量单独提供。默认应用日志不含请求正文、头、令牌或模型返回内容。

数据库仍包含个人学习信息，服务器管理员应限制备份和目录权限。撤销访问时更换 `REMINDER_TOKEN` 并重启服务，再在手机填写新令牌。
