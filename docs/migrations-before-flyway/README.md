# 接入 Flyway 之前的迁移脚本（存档）

2026-11-02 ～ 11-04 期间，表结构变更是手动执行这几个脚本的（`docker cp` + `docker exec`）：

| 脚本 | 内容 |
|---|---|
| `V2__user_email.sql` | 用户表增加邮箱、邮件提醒开关 |
| `V3__email_outbox.sql` | 邮件发件箱表 |
| `V4__knowledge_document.sql` | 知识库资料表 |
| `V5__knowledge_document_deleted_status.sql` | 资料移入回收站时 status 改为 DELETED |

接入 Flyway 时，它们连同最初的建表语句，已经合并进 `src/main/resources/db/migration/V5__baseline_schema.sql`。
这里只作历史记录，**不会再被执行**：Flyway 只扫描 `db/migration` 目录。
