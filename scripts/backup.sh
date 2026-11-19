#!/usr/bin/env bash
# Jarvis 的数据库备份，在服务器上由 cron 每天执行一次。
#
# 备份什么：
# - MySQL：全部业务数据（用户、会话、消息、提醒、资料原文、记忆），必须备份；
# - PostgreSQL 的向量：不备份。资料原文在 MySQL 里，向量丢了可以重新生成
#   （把资料状态改回 PROCESSING，后台会自动重新切块、向量化）；
# - Redis：不备份。只有退出登录黑名单和限流计数，丢了影响很小；
# - .env：不在这里备份，它不能和数据放在一起，请在本地另外妥善保存。
#
# 备份放在服务器的 /opt/jarvis-backup，保留最近 7 天；另外要定期下载到本地（异地备份），
# 服务器本身出问题或被攻破时，同一台机器上的备份也会一起没了。

# -e：任何一条命令失败就立即退出；-u：用到未定义的变量就报错；
# -o pipefail：管道里任何一个环节失败，整条管道都算失败（否则 mysqldump 失败了，gzip 成功，也会被当成成功）
set -euo pipefail

BACKUP_DIR=/opt/jarvis-backup
KEEP_DAYS=7
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$BACKUP_DIR/jarvis-mysql-$STAMP.sql.gz"
TMP="$FILE.tmp"

mkdir -p "$BACKUP_DIR"
# 备份里有密码哈希、聊天记录，只有 root 能读
chmod 700 "$BACKUP_DIR"

echo "[$(date '+%F %T')] backup start -> $FILE"

# 在 MySQL 容器里执行 mysqldump，输出经管道压缩后写到宿主机。
# 密码取自容器自己的环境变量 MYSQL_ROOT_PASSWORD，不出现在宿主机的命令行里（ps 能看到命令行参数）。
# --single-transaction：在一个事务里导出，得到某一时刻的一致快照，而且不锁表，备份时网站照常可用；
# --routines --triggers：连同存储过程、触发器一起导出（现在没有，以后有了也不会漏）
# 不加 --databases：导出的文件里不带 CREATE DATABASE、USE jarvis，恢复到哪个库由导入时指定。
# 否则往临时库里做恢复演练时，文件里的 USE jarvis 会把数据写回正式库
docker exec jarvis-mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --default-character-set=utf8mb4 jarvis' \
  | gzip > "$TMP"

# 先写临时文件，检查通过后再改名：中途失败的半截文件不会被当成一份“好的备份”
gzip -t "$TMP"
if [ "$(gunzip -c "$TMP" | head -c 1000 | wc -c)" -lt 1000 ]; then
  echo "[$(date '+%F %T')] backup looks empty, aborting" >&2
  rm -f "$TMP"
  exit 1
fi
mv "$TMP" "$FILE"
chmod 600 "$FILE"

# 删除 7 天前的备份
find "$BACKUP_DIR" -name 'jarvis-mysql-*.sql.gz' -mtime +"$KEEP_DAYS" -delete

echo "[$(date '+%F %T')] backup done, size $(du -h "$FILE" | cut -f1)"
