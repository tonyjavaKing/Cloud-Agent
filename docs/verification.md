# 验证记录

日期：2026-11-21
环境：本地 Java 21 + Maven 3.9.9
模式：`demo`

## 自动检查

- [x] `mvn -q -DskipTests clean package` 成功，生成 Spring Boot jar
- [x] `GET /api/health` 返回 `{"ok":true,"agentMode":"demo","agentCli":"codex"}`
- [x] 创建会话成功，返回 UUID
- [x] SSE 连接成功并收到 `snapshot`
- [x] 发送任务后收到 `message`、`delta`、`status=completed`
- [x] 演示 Agent 生成 `task-result.md`
- [x] `GET /api/sessions/{id}/files/task-result.md` 返回 HTTP 200

## 人工演示

- [x] 代码开发任务：在独立会话中提交任务并查看流式执行
- [x] 日常工作任务：上传材料后提交整理任务
- [x] 后续追问：同一会话继续发送消息
- [x] 多会话切换：会话工作目录相互隔离
- [x] 页面关闭后重新打开：服务未重启时会话列表仍可见

Docker 配置已提供于项目根目录；若本机 Docker Desktop 未运行，先启动 Docker 再执行 `docker compose up --build`。不要将任何密钥或 CLI 登录缓存提交到公开仓库。
