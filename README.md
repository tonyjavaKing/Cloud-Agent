# Cloud Agent 助手工作台

一个面向单用户的浏览器工作台：用户可以创建多个会话、持续发送任务、上传材料，并查看 Agent CLI 的执行进度和成果文件。每个会话使用独立工作目录，页面关闭后重新打开仍可在当前进程中继续访问会话。

## 技术栈

- 后端：Java 21、Spring Boot 3.4、Spring Web
- 前端：原生 HTML/CSS/JavaScript，使用浏览器原生 `EventSource` 接收 SSE 流
- Agent：服务端通过 `ProcessBuilder` 调用 `codex exec`、`claude` 或自定义 CLI
- 运行：Docker 多阶段构建（Maven + Eclipse Temurin 21）
- 文件：会话目录挂载到 `data/`，无需外部数据库即可演示

## 快速启动（演示模式）

演示模式不需要模型凭据，会用确定性的本地 Agent 流程生成 `task-result.md`，适合先验证界面和提交材料。

```bash
cp .env.example .env
docker compose up --build
```

打开 <http://localhost:3000>，点击“新建会话”，发送任务。你可以在一个会话中连续追问，也可以新建另一个会话；上传的文件和生成的成果会显示在右侧“成果文件”。

不使用 Docker 时，需要 Java 21 和 Maven：`mvn spring-boot:run`。

## 接入真实 Agent CLI

正式运行时把 `AGENT_MODE` 改为 `cli`，并确保 CLI 已安装在容器内或通过自定义镜像提供。默认调用 Codex：

```dotenv
AGENT_MODE=cli
AGENT_CLI=codex
OPENAI_API_KEY=由运行环境注入，不要提交到仓库
OPENAI_BASE_URL=可选的兼容 API 地址
OPENAI_MODEL=可选模型名
```

应用会以当前会话工作目录作为 CLI 的工作目录，并把 stdout 按 SSE 增量转发到浏览器。Claude 使用 `AGENT_CLI=claude`；其他 CLI 可将命令名填入 `AGENT_CLI`。

不要把真实 API Key、CLI 登录缓存或 `.env` 提交到公开仓库。考试提供的临时 API 仅用于应用内 Agent 运行、联调和测试。

## 功能与接口

- `POST /api/sessions`：创建会话
- `GET /api/sessions`、`GET /api/sessions/{id}`：会话列表与详情
- `POST /api/sessions/{id}/messages`：提交任务并异步启动 Agent
- `GET /api/sessions/{id}/events`：SSE 快照、消息、增量输出、状态和成果事件
- `POST /api/sessions/{id}/upload`：上传材料到会话工作目录
- `GET /api/sessions/{id}/files/{path}`：下载成果文件
- `GET /api/health`：运行模式和 CLI 健康检查

## 演示建议与验证证据

1. 代码开发任务：新建“代码任务”会话，输入“创建一个 Java Hello World 工具并写 README”，展示执行中状态和生成文件。
2. 日常工作任务：新建“材料整理”会话，上传 CSV/Markdown，输入“分析材料并输出摘要报告”，展示独立会话和成果下载。
3. 在任一会话中继续追问，切换到另一个会话，再切回来，证明会话隔离。
4. 关闭并重新打开浏览器页面，确认会话列表仍可见（服务进程未重启时）。
5. 记录 `GET /api/health`、Docker 构建日志、页面中的流式输出和成果文件作为交付验证证据。

## 已知限制

- 当前是单用户应用，未实现账号、权限和并发配额。
- 会话元数据保存在进程内存；挂载的 `data/` 保存工作文件。重启服务后会话列表会清空，但已生成的文件仍保留。
- 真实 CLI 模式依赖运行环境提供 CLI 和认证；演示模式用于界面联调，不代表模型调用已成功。
- SSE 连接断开后浏览器会自动重连并获取当前快照，不做历史事件回放。
