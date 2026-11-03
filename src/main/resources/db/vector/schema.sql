-- 向量库（PostgreSQL + pgvector）的建表脚本
-- Docker 第一次启动 jarvis-postgres（数据目录为空）时自动执行，见 compose.yaml
--
-- 表结构是 Spring AI PgVectorStore 约定的格式。Spring AI 2.0 默认不再自动建表，所以在这里建好

-- 扩展：PostgreSQL 的“插件”机制，装上以后才有对应的数据类型和函数
CREATE EXTENSION IF NOT EXISTS vector;        -- pgvector：提供 vector 类型和向量距离运算
CREATE EXTENSION IF NOT EXISTS hstore;        -- Spring AI 文档要求安装
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- 提供 uuid_generate_v4()，生成主键

CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    -- 资料块的原文，检索到后交给大模型
    content   text,
    -- 元数据（JSON）：属于哪个用户、哪份文档、第几块等，检索时用来过滤
    metadata  json,
    -- 向量化结果。1024 要和向量模型输出的维度一致，换模型时要一起改
    embedding vector(1024)
);

-- HNSW 索引：近似最近邻搜索。不建索引时，每次检索都要和所有向量逐一计算距离；
-- 有了它，几百万条也能在毫秒级找到最相近的几条，代价是结果是“近似”的（极少数情况会漏掉一两个）
-- vector_cosine_ops：按余弦距离比较，和 Spring AI 默认的 COSINE_DISTANCE 对应
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx ON vector_store USING HNSW (embedding vector_cosine_ops);
