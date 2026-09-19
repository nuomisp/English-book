# 可选提醒服务器部署

当前仓库提供可运行服务及部署配置，尚未登录你的服务器或执行部署。需要部署时，再确认 Linux 发行版、SSH 登录方式、可用域名及 HTTPS。服务器无 GPU 也能运行；这里不加载 MOSS 模型，不要求手机把中转站密钥上传。

## 方案和边界

一个 Python 进程、SQLite 文件、HTTPS 反向代理即可。推荐现有服务器用 Docker Compose，默认服务资源上限 192 MB 内存 / 0.5 CPU；这是配置限制，不是已在你的服务器实测的资源数据。不需要安装 Android 构建环境，APK 仍在 GitHub Actions 打包。

手机通过后台任务同步汇总进度并拉取提醒。服务器保证保存计划、限频、过期和幂等性；它没有魅族厂商推送凭据，也不保证应用被强行停止后仍能收到通知。系统可能延迟后台任务。今后若需要更可靠的息屏推送，需要再接合法可用的厂商推送通道并在真机验证。

## Docker Compose 部署

下面是部署阶段在 Linux 服务器执行的步骤，不是已执行记录。先准备 Docker Engine 和 Compose 插件，可按 [Docker 官方安装文档](https://docs.docker.com/engine/install/) 操作。已有反向代理时复用现有代理。

1. 将仓库放在服务器自己的项目目录，进入 `English-book/server`。
2. 复制示例配置并限制权限：

   ```sh
   cp .env.example .env
   chmod 600 .env
   python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
   ```

   把生成的随机值填入 `.env` 的 `REMINDER_TOKEN`，不要保留占位符；程序会拒绝使用占位符启动。此值是手机连接提醒服务器的专用令牌。其他 Docker 默认值保持不变：容器监听 `0.0.0.0`，Compose 仅把端口映射到服务器回环地址，公网不可直连 8787。

3. 如果需要 AI 即时生成文案，一并填写下面三个值；暂时不填也能运行，使用进度相关的本地文案：

   ```dotenv
   AI_BASE_URL=https://你的中转站地址/v1
   AI_API_KEY=在服务器本地填写
   AI_MODEL=你的模型名称
   ```

   `AI_BASE_URL` 是基础地址，程序会追加 `/chat/completions`。必须用 HTTPS，不支持把完整 `/chat/completions` 地址填进基础地址。服务器不自动复制手机密钥。默认每个提醒最多调用一次模型，超时 15 秒便回退本地文案；不做昂贵的多模型链式调用。接口要支持 `model/messages/temperature/max_tokens/stream` 的普通 Chat Completions 请求。

4. 构建并启动服务：

   ```sh
   docker compose up -d --build
   docker compose ps
   curl --fail http://127.0.0.1:8787/health
   ```

   应返回 `{"status":"ok","version":1}`。此时没有手机同步快照，不会生成任何提醒。

5. 域名解析到服务器，用 HTTPS 代理回环端口。假设机器已安装 Caddy，一个站点配置示例为：

   ```caddyfile
   learn.example.com {
       reverse_proxy 127.0.0.1:8787
   }
   ```

   把域名换成自己的。对已有 Caddy 配置只添加本站点，不覆盖其他网站；先执行配置校验再 reload。证书签发通常需要域名解析和 80/443 入站可用，参见 [Caddy HTTPS 文档](https://caddyserver.com/docs/automatic-https)。不要公开 8787，也不要在反代日志记录 `Authorization` 或请求正文。

6. 在手机设置里填写 `https://learn.example.com` 和专用提醒令牌，显式启用服务器提醒模式，先测试连接并同步当天进度。切换成功后关闭本地同一套提醒计划，避免双份提醒。安卓通知权限、应用自启动和电池限制仍需在魅族 21 上验证。

7. 如果服务器设置了 AI 环境变量，在一个真实计划时间验证生成和接收；没有配置 AI 时先验证本地文案路径。再断网/重启服务验证不会补发整天的旧消息。不要为了测试改服务器时钟。

## 不用 Docker：systemd

适合已有 Python 3.12+ 的 Linux。系统需有时区数据库 `tzdata`。无需 pip 安装运行框架。

- 将代码放到 `/opt/english-companion/server`，创建专用不可登录用户 `english-companion`，让它可读取该目录。
- 将环境变量放入 `/etc/english-companion.env`，由 root 持有、权限 `600`。至少设置：

  ```dotenv
  REMINDER_TOKEN=填写新生成的随机令牌
  LISTEN_HOST=127.0.0.1
  PORT=8787
  DATABASE_PATH=/var/lib/english-companion/reminders.sqlite3
  ```

- 将仓库 `server/english-companion.service` 安装为 `/etc/systemd/system/english-companion.service`，确认 `/usr/bin/python3` 确实是 Python 3.12+，再执行：

  ```sh
  sudo systemctl daemon-reload
  sudo systemctl enable --now english-companion
  sudo systemctl status english-companion
  curl --fail http://127.0.0.1:8787/health
  ```

`StateDirectory` 负责建立可写数据目录；应用代码保持只读。HTTPS 反代与 Docker 方案相同。Docker 和 systemd 二选一，不要让两份调度进程共享同一个数据库。

## 更新、备份和停用

- 更新前使用 SQLite 的 `Connection.backup()` 或 SQLite CLI 的 `.backup` 做一致性备份。数据库采用 WAL，运行中不能只复制主 `.sqlite3` 文件而丢掉 WAL。备份不包含环境变量；令牌和 API Key 独立保管。
- Docker 更新使用 `docker compose up -d --build`，命名卷保留计划和统计；不要用 `down -v`，它会删除数据。systemd 更新代码后重启服务即可。
- 先把手机提醒开关关闭并联网同步，可取消未发送的计划；服务器端停用可用 `docker compose stop` 或 `systemctl stop english-companion`，不删除数据。
- 令牌泄露时在服务器重新生成 `REMINDER_TOKEN`、重启服务、更新手机配置。手机的 AI 密钥与这个令牌用途不同。
- `/health` 只证明 HTTP 进程活着；它不验证域名证书、模型余额、手机通知权限或厂商后台策略。排查时分别测试 HTTPS、认证、快照同步、计划时间和手机接收。

## 服务接口与隐私

接口见 [server/README.md](../server/README.md#api-v1)。服务只接受白名单统计字段，拒绝 API Key 和聊天内容。提醒模式以手机时区为准，随机 4 次及 21:30 回顾、09:00—22:00 范围内显示。网络或系统延误可以让提醒少于 5 次，不会用密集补发来凑数。

第一版服务端提醒次数固定为 4 次随机加 1 次回顾。应用其他本地提醒设置如有自定义次数，不能自动改变此服务计划；修改服务器策略应同时更新客户端配置契约。
