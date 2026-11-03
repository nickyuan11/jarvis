package com.nick.jarvis.knowledge;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 向量库冒烟测试：验证“向量化 → 写入 pgvector → 相似度检索”这条链路通不通。
 *
 * 只在 jarvis.vector.smoke-test=true 时运行（在 application-local.yml 里打开），
 * 应用启动后存三句话，用一句“字面不同、意思相近”的话去搜，看排第一的是不是对的那句。
 * 测完会删掉这三条数据。验证通过后把开关关掉，以后接入正式的上传功能时，这个类就可以删了。
 */
@Component
@ConditionalOnProperty(name = "jarvis.vector.smoke-test", havingValue = "true")
public class VectorSmokeTest implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorSmokeTest.class);

    private final VectorStore vectorStore;

    public VectorSmokeTest(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Document> documents = List.of(
                new Document("延长手机电池续航的几个方法：调低屏幕亮度，关闭不常用应用的后台刷新。", Map.of("source", "smoke-test")),
                new Document("红烧肉的做法：五花肉切块焯水，加冰糖小火炒出糖色。", Map.of("source", "smoke-test")),
                new Document("湖人队在主场以 112 比 105 战胜了勇士队。", Map.of("source", "smoke-test")));
        String query = "手机怎么才能不那么快没电";

        // add 会先调用向量模型把每段文字变成向量，再写入 PostgreSQL
        vectorStore.add(documents);
        try {
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(3)
                    // 只在这三条测试数据里搜，不碰别的数据
                    .filterExpression("source == 'smoke-test'")
                    .build());
            log.info("Vector smoke test, query: {}", query);
            for (Document hit : hits) {
                // score 是相似度：越接近 1 越相近
                log.info("  score={} text={}", String.format("%.3f", hit.getScore()), hit.getText());
            }
        } finally {
            // 不管检索成功与否，都把测试数据删掉
            vectorStore.delete(documents.stream().map(Document::getId).toList());
        }
    }
}
