package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.support.JarvisTime;

@ExtendWith(MockitoExtension.class)
class DocumentProcessorTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 15, 0);

    @Mock
    private KnowledgeDocumentMapper mapper;
    @Mock
    private VectorStore vectorStore;

    private DocumentProcessor processor;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocument.class);
    }

    @BeforeEach
    void setUp() {
        processor = new DocumentProcessor(mapper, vectorStore, new JarvisTime(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE)));
    }

    private static KnowledgeDocument doc(int attempts, String content) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L);
        doc.setUserId(1L);
        doc.setFilename("notes.md");
        doc.setStatus(KnowledgeDocument.STATUS_PROCESSING);
        doc.setAttempts(attempts);
        doc.setNextAttemptAt(NOW.minusMinutes(1));
        doc.setContent(content);
        return doc;
    }

    /** 25 个 400 字的段落：每段都会单独成一块，一共 25 块 */
    private static String twentyFiveChunks() {
        return String.join("\n\n", java.util.Collections.nCopies(25, "字".repeat(400)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Collection<Object>> updateValues(int expectedCalls) {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(expectedCalls)).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .map(w -> {
                    AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) w;
                    // WHERE 条件（eq、isNull 等）是懒加载的：生成 SQL 时才登记参数，set 的参数则是立刻登记。
                    // Mapper 是模拟的，不会生成 SQL，所以这里手动生成一次，WHERE 里的值才会出现
                    wrapper.getSqlSegment();
                    return wrapper.getParamNameValuePairs().values();
                })
                .toList();
    }

    @Test
    void doesNothingWhenSomeoneElseClaimedIt() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThat(processor.process(doc(0, null))).isEqualTo(DocumentProcessor.Result.SKIPPED);

        verifyNoInteractions(vectorStore);
        verify(mapper, never()).selectById(any());
    }

    @Test
    void deletesOldChunksFirstThenAddsInBatches() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(doc(0, twentyFiveChunks()));

        assertThat(processor.process(doc(0, null))).isEqualTo(DocumentProcessor.Result.READY);

        // 先清掉旧块（幂等），再分 3 批写入：10 + 10 + 5
        InOrder order = inOrder(vectorStore);
        order.verify(vectorStore).delete(any(Filter.Expression.class));
        order.verify(vectorStore, times(3)).add(anyList());
        assertThat(updateValues(2).get(1)).contains(KnowledgeDocument.STATUS_READY, 25);
    }

    @Test
    void failureSchedulesARetry() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(doc(0, "一段内容"));
        doThrow(new RuntimeException("429 Too Many Requests")).when(vectorStore).add(anyList());

        assertThat(processor.process(doc(0, null))).isEqualTo(DocumentProcessor.Result.FAILED);

        // 第 1 次失败：2 分钟后重试，还不算彻底失败
        Collection<Object> failure = updateValues(2).get(1);
        assertThat(failure).contains(NOW.plusMinutes(2));
        assertThat(failure).doesNotContain(KnowledgeDocument.STATUS_FAILED);
    }

    @Test
    void lastAttemptMarksTheDocumentFailed() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(doc(DocumentProcessor.MAX_ATTEMPTS - 1, "一段内容"));
        doThrow(new RuntimeException("401 InvalidApiKey")).when(vectorStore).add(anyList());

        processor.process(doc(DocumentProcessor.MAX_ATTEMPTS - 1, null));

        assertThat(updateValues(2).get(1)).contains(KnowledgeDocument.STATUS_FAILED);
    }

    @Test
    void cleansUpChunksWhenTheDocumentWasDeletedWhileProcessing() {
        // 抢占成功，但标记 READY 时影响 0 行：处理期间用户把它删了
        when(mapper.update(isNull(), any())).thenReturn(1, 0);
        when(mapper.selectById(7L)).thenReturn(doc(0, "一段内容"));
        KnowledgeDocument deleted = doc(1, null);
        deleted.setDeletedAt(NOW);
        when(mapper.selectOne(any())).thenReturn(deleted);

        assertThat(processor.process(doc(0, null))).isEqualTo(DocumentProcessor.Result.SKIPPED);

        // 处理开始时删一次旧块，发现被删除后再删一次刚写进去的块
        verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
    }
}
