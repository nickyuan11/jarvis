# Jarvis 开发日志

一个基于 Spring Boot 4 和 Spring AI 的个人 AI 助手。计划的核心功能是网文阅读助手：上传小说后，可以针对内容提问、总结章节、梳理人物关系（RAG）。

## 当前状态（2026-10-27）

| 模块 | 状态 |
|---|---|
| 多轮对话（DeepSeek）+ 对话记忆 | ✅ 完成 |
| 流式输出（SSE）+ 聊天网页 | ✅ 完成 |
| 会话管理（列表、重命名、删除） | ✅ 完成 |
| 注册登录（Spring Security + JWT） | ✅ 完成 |
| Redis：退出登录、接口限流 | ⏳ 下一步，等待安装 Docker |
| 单元测试 | ⏳ 待做 |
| 网文 RAG（向量检索） | 📅 第 3 周 |
| 工具调用、Docker 部署 | 📅 第 4 周 |

**技术栈**：Java 21 · Spring Boot 4.0.8 · Spring AI 2.0.2 · Spring Security · JJWT 0.13 · MyBatis-Plus 3.5.17 · MySQL 8 · DeepSeek

---

## 2026-10-21（周三）：确定方向

- 梳理求职现状：自学 Java，有 1 年工作经验，很多岗位的经验要求对不上。
- 决定做一个能写进简历、能在面试中讲清楚的完整项目，来弥补经验空档。

## 2026-10-23（周五）：确定项目

- 在几个方向里选定了 **Jarvis 风格的 AI 助手**：结合个人兴趣（网文、篮球、游戏），也贴合当前招聘对 AI 应用开发的需求。
- 确定核心功能为**网文阅读助手（RAG）**，并制定了 4 周计划：
  1. 第 1 周：聊天接口、流式输出、对话存储
  2. 第 2 周：登录、会话管理、Redis
  3. 第 3 周：小说上传 + RAG
  4. 第 4 周：工具调用、Docker、部署

## 2026-10-26（周一）：环境搭建 + 第一次和 Jarvis 对话

**成果**
- 搭好开发环境：JDK 21、MySQL 8、IDEA、Git。
- 创建 Spring Boot 4 项目，完成第一个接口 `GET /api/ping`。
- 设计并建好三张表：`user`、`conversation`、`message`，接入 MyBatis-Plus（自动填充时间、逻辑删除）。
- 接入 DeepSeek，完成 `POST /api/chat`：支持多轮对话记忆，记录每次回复的模型和 token 用量。
- 代码第一次推送到 GitHub。

**设计讨论：message 表**
- 主键用 `bigint`；去掉冗余的 `userId`，通过会话关联用户。
- `role` 用字符串 `user / assistant / system / tool`，和大模型 API 格式一致，可以直接转换，也方便以后扩展工具调用。
- token 拆成 `prompt_tokens` 和 `completion_tokens`，并记录 `model`：输入和输出单价不同、价格随模型变化，存原始数据，费用可以随时算。
- 时间用 `LocalDateTime`，不用旧的 `java.util.Date`。

**踩过的坑**
- **Maven 下载依赖失败**：报错 `Permission denied: getsockopt`。原因是 ExpressVPN 拦截了 Java 程序的网络连接。解决：调整 VPN 设置，并在 `settings.xml` 配置阿里云镜像。
- **调用 DeepSeek 失败**：同样的报错，这次是项目运行用的 JDK 21 被拦截（IDEA 下载依赖用的是它自带的 Java，所以之前没暴露）。解决方法同上。
- **测试时出现 Mockito 动态加载 agent 的警告**：JDK 21 对动态加载 agent 发出的提醒，不影响运行，暂不处理。

**学到的**
- 测试类上的 `@Transactional` 会在每个测试结束后自动回滚，测试数据不会留在库里；但自增 id 不会回退。
- 调用大模型不要放在数据库事务里：响应要好几秒，会长时间占用数据库连接。
- 大模型本身没有记忆，“记住对话”其实是每次把历史消息一起发过去。

## 2026-10-27（周二）：流式输出、会话管理、登录

**成果**
- **流式输出**：新增 `POST /api/chat/stream`，通过 SSE 一段段推送回复；定义了 `meta / delta / done / error` 四种事件。
- **聊天网页**：`index.html`，回复逐段显示，展示模型和 token 用量。
- **会话管理**：左侧会话栏，支持查看历史、继续聊天、重命名、删除。
- **注册登录**：Spring Security + JWT，密码 BCrypt 加密，新增登录注册页；所有数据按登录用户隔离。

**设计要点**
- 流式回复在整个流结束后才一次性存库；用户中途关闭页面时，保存已生成的部分；模型出错时推送 `error` 事件，而不是直接断开连接。
- 数据库操作通过 `publishOn(Schedulers.boundedElastic())` 切到专门的线程池，避免阻塞负责网络 I/O 的线程。
- 前端用 `textContent` 显示 AI 回复，防止 XSS。
- 接口按 RESTful 风格设计（GET 查询、PATCH 部分修改、DELETE 删除，成功返回 204）。
- 每个会话操作都校验归属，防止越权访问（IDOR）；“不存在”和“不属于你”统一返回 404。
- 把“当前用户”集中在 `CurrentUser` 一个地方。接入登录时只改了它，业务代码一行没动。
- 登录失败统一提示“用户名或密码错误”；注册并发时由数据库唯一索引兜底。

**踩过的坑**
- **注册接口返回 400 但没有错误原因**：Spring Boot 4 把 `server.error.include-message` 改名为 `spring.web.error.include-message`，旧配置不报错但不生效。教训：升级大版本后，配置不生效要先查迁移说明。
- **用户名带空格导致注册失败，但提示不清楚**：优化为注册时实时提示规则、提交前前端校验、后端报错写明“不能包含空格、中文”。前端校验只为体验，后端校验才是防线。
- **新文件漏提交**：IDEA 默认不勾选新文件，用 Amend 合并进上一次未推送的提交。
- **推送 GitHub 失败**：ExpressVPN 分流没有包含 Git。已把 `git-remote-https.exe` 等加入分流，但网络仍不稳定，本地提交待推送。

**面试题思考：JWT 如何让用户下线**
- 单个 JWT 签发后无法收回，被偷后在过期前都能用。
- 方案：双 token（短期 Access + 服务端登记的 Refresh）、token 黑名单、用户版本号。
- 三种方案都需要在服务端存少量状态，通常放在 Redis（快、支持自动过期）。

**进行中**
- 安装 Docker Desktop（WSL2 下载较慢，下次继续）。

---

## 下一步

1. 装好 Docker，用 Docker 启动 Redis。
2. 用 Redis 实现退出登录（token 黑名单）和接口限流。
3. 给核心 Service 补单元测试。
4. 网络稳定时推送积压的本地提交。
