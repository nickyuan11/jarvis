# Jarvis

A personal AI assistant (AI agent) built with Spring Boot 4 and Spring AI: streaming multi-turn chat, tool calling (reminders, NBA and League of Legends results), and reminder notifications by email.

Development log (in Chinese): [docs/DEVLOG.md](docs/DEVLOG.md)

## Tech stack

Java 21 · Spring Boot 4 · Spring AI · Spring Security + JWT · MyBatis-Plus · MySQL 8.4 · Redis 7 · Docker Compose · DeepSeek

## Getting started

Prerequisites: JDK 21 and Docker Desktop.

1. Start MySQL and Redis:

   ```shell
   cp .env.example .env    # then fill in the two passwords
   docker compose up -d
   docker compose ps       # wait until jarvis-mysql shows "healthy"
   ```

   On first start, MySQL creates the `jarvis` database, a `jarvis` user that can only access it, and all tables from `src/main/resources/db/schema.sql`. MySQL is published on `127.0.0.1:3307`, so it does not clash with a MySQL already installed on port 3306.

2. Copy `src/main/resources/application-local.yml.example` to `application-local.yml` (same folder) and fill in:
   - the database password (same as `MYSQL_PASSWORD` in `.env`)
   - a DeepSeek API key
   - optional: SMTP credentials for email reminders, a PandaScore token for League of Legends tools

3. Open the project in IntelliJ IDEA and run `JarvisApplication`, then visit http://localhost:8080.

Upgrading an existing database: `schema.sql` only runs when the data volume is empty, so run any new scripts in `src/main/resources/db/migration/` by hand.
