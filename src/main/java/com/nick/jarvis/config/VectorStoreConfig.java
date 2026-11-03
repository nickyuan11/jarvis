package com.nick.jarvis.config;

import javax.sql.DataSource;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 向量库：第二个数据源（PostgreSQL + pgvector）和 Spring AI 的 PgVectorStore。
 *
 * 为什么是 defaultCandidate = false？
 * Spring Boot 只在项目里“没有”DataSource 时才自动配置 MySQL 的数据源。直接再定义一个 DataSource，
 * MySQL 那个就会消失。defaultCandidate = false 表示“这个 Bean 不参与按类型的默认注入”：
 * 自动配置照常创建 MySQL 的数据源，MyBatis-Plus、事务管理器也继续用 MySQL；
 * 只有显式写了 @Qualifier("vector") 的地方，才会拿到 PostgreSQL 的这个。
 *
 * 写法来自 Spring Boot 官方文档的 “Configure Two DataSources”。
 * 注意 Spring Boot 4 里 DataSourceProperties 的包名变了，网上 3.x 的教程照抄会编译不过。
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

    /** 读取 jarvis.vector.datasource.url / username / password */
    @Qualifier("vector")
    @Bean(defaultCandidate = false)
    @ConfigurationProperties("jarvis.vector.datasource")
    public DataSourceProperties vectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 用和 Spring Boot 自动配置相同的逻辑创建连接池；jarvis.vector.datasource.configuration.* 是连接池的高级设置 */
    @Qualifier("vector")
    @Bean(defaultCandidate = false)
    @ConfigurationProperties("jarvis.vector.datasource.configuration")
    public HikariDataSource vectorDataSource(@Qualifier("vector") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 手动创建 PgVectorStore，传入连接 PostgreSQL 的 JdbcTemplate。
     * 距离计算默认用余弦距离、索引默认用 HNSW，和 db/vector/schema.sql 里建的索引对应。
     * initializeSchema(false)：表由建表脚本负责，应用启动时不去改表结构。
     */
    @Bean
    public VectorStore vectorStore(@Qualifier("vector") DataSource vectorDataSource,
                                   EmbeddingModel embeddingModel,
                                   @Value("${jarvis.vector.dimensions}") int dimensions) {
        return PgVectorStore.builder(new JdbcTemplate(vectorDataSource), embeddingModel)
                .dimensions(dimensions)
                .initializeSchema(false)
                .build();
    }
}
