package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.knowledge.KnowledgeSearchService.SearchResult;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchServiceTest {

    private static final long USER_ID = 9L;

    @Mock
    private KnowledgeDocumentMapper mapper;
    @Mock
    private VectorStore vectorStore;

    private KnowledgeSearchService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocument.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeSearchService(mapper, vectorStore, 5, 0.4);
    }

    private static KnowledgeDocument ready(long id, String filename) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setUserId(USER_ID);
        doc.setFilename(filename);
        doc.setStatus(KnowledgeDocument.STATUS_READY);
        return doc;
    }

    /** 取出交给向量库的检索请求，把过滤条件转成字符串，方便检查里面有哪些 id */
    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    @Test
    void noReadyDocumentsSkipsTheVectorSearch() {
        when(mapper.selectList(any())).thenReturn(List.of());

        SearchResult result = service.search(USER_ID, "押金", null);

        assertThat(result.searchedDocuments()).isZero();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void searchIsFilteredByUserAndReadyDocumentsInsideTheQuery() {
        when(mapper.selectList(any())).thenReturn(List.of(ready(101L, "DEVLOG.md"), ready(202L, "租房合同.md")));
        // 元数据从 JSON 读回来时数字可能是 Integer
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("押金为两个月房租").metadata(Map.of("documentId", 202, "chunkIndex", 3)).score(0.82).build()));

        SearchResult result = service.search(USER_ID, "租房合同 押金", null);

        SearchRequest request = capturedRequest();
        String filter = request.getFilterExpression().toString();
        // 用户隔离：过滤条件在检索查询“里面”，而不是查完再过滤
        assertThat(filter).contains("userId", "documentId", "101", "202");
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.4);
        // 文件名用 MySQL 里的，chunkIndex 从元数据里取
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.filename()).isEqualTo("租房合同.md");
            assertThat(hit.chunkIndex()).isEqualTo(3);
        });
    }

    @Test
    void legacyVectorIsLocatedInOriginalWithoutReEmbedding() {
        KnowledgeDocument doc = ready(202L, "合同.md");
        doc.setContent("  违约金一个月\r\n\r\n提前通知可免除  ");
        when(mapper.selectList(any())).thenReturn(List.of(doc));
        when(mapper.selectById(202L)).thenReturn(doc);
        String text = "违约金一个月\n\n提前通知可免除";
        Document hit = Document.builder().text(text).metadata(Map.of("documentId", 202, "chunkIndex", 0)).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit, hit));

        var range = service.search(USER_ID, "退租", null).hits().get(0).range();
        assertThat(doc.getContent().substring(range.start(), range.end())).isEqualTo(doc.getContent().strip());
        verify(mapper).selectById(202L);
    }

    @Test
    void documentNameNarrowsTheSearchToMatchingDocuments() {
        when(mapper.selectList(any())).thenReturn(List.of(ready(101L, "DEVLOG.md"), ready(202L, "2025租房合同-最终版.md")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        SearchResult result = service.search(USER_ID, "押金", "租房合同");

        // 按“包含”匹配：模型说“租房合同”，也能匹配上完整的文件名
        assertThat(capturedRequest().getFilterExpression().toString()).contains("202").doesNotContain("101");
        assertThat(result.searchedDocuments()).isEqualTo(1);
        assertThat(result.note()).isNull();
    }

    @Test
    void unknownDocumentNameFallsBackToAllDocuments() {
        when(mapper.selectList(any())).thenReturn(List.of(ready(101L, "DEVLOG.md"), ready(202L, "租房合同.md")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        SearchResult result = service.search(USER_ID, "血压", "体检报告");

        // 名字对不上时不能直接说“没有”，退回到搜全部，并告诉模型发生了什么
        assertThat(capturedRequest().getFilterExpression().toString()).contains("101", "202");
        assertThat(result.note()).contains("体检报告");
    }
}
