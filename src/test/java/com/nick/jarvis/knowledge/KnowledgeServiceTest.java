package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.support.JarvisTime;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 15, 0);

    @Mock
    private KnowledgeDocumentMapper mapper;
    @Mock
    private DocumentProcessor processor;
    @Mock
    private ApplicationEventPublisher events;

    private KnowledgeService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocument.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(mapper, processor, events,
                new JarvisTime(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE)));
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    /** 取出每次 update 的 SET 和 WHERE 里用到的参数值 */
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

    private static void assertStatus(Throwable e, int status) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                r -> assertThat(r.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void rejectsUnsupportedFileTypes() {
        assertThatThrownBy(() -> service.upload(1L, file("资料.pdf", "abc".getBytes())))
                .satisfies(e -> assertStatus(e, 400));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsFilesOverOneMegabyte() {
        byte[] big = new byte[(int) KnowledgeService.MAX_FILE_BYTES + 1];
        Arrays.fill(big, (byte) 'a');

        assertThatThrownBy(() -> service.upload(1L, file("big.txt", big)))
                .satisfies(e -> assertStatus(e, 413));
    }

    @Test
    void rejectsBinaryFilesRenamedToTxt() {
        assertThatThrownBy(() -> service.upload(1L, file("photo.txt", new byte[] {'a', 0, 'b'})))
                .satisfies(e -> assertStatus(e, 400));
    }

    @Test
    void duplicateInTheTrashSuggestsRestoring() {
        when(mapper.selectCount(any())).thenReturn(0L);
        KnowledgeDocument existing = new KnowledgeDocument();
        existing.setFilename("notes.md");
        existing.setDeletedAt(NOW.minusDays(1));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.upload(1L, file("notes.md", "你好".getBytes(StandardCharsets.UTF_8))))
                .satisfies(e -> assertStatus(e, 409))
                .hasMessageContaining("回收站");
    }

    @Test
    void uploadSavesTheDocumentAndQueuesProcessing() {
        when(mapper.selectCount(any())).thenReturn(0L);

        service.upload(1L, file("notes.md", "你好".getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(KnowledgeDocument.STATUS_PROCESSING);
        assertThat(saved.getValue().getContent()).isEqualTo("你好");
        assertThat(saved.getValue().getContentHash()).hasSize(64);
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(NOW);
        // 上传接口不等处理完：只发一个事件，后台去处理
        verify(events).publishEvent(any(DocumentQueuedEvent.class));
        verifyNoInteractions(processor);
    }

    @Test
    void deleteMarksMySqlFirstThenDeletesChunks() {
        when(mapper.selectOne(any())).thenReturn(new KnowledgeDocument());
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.delete(1L, 7L);

        // 顺序很重要：先改真相来源（MySQL），再删 PostgreSQL 里的块
        InOrder order = inOrder(mapper, processor);
        order.verify(mapper).update(isNull(), any());
        order.verify(processor).deleteChunks(7L);
    }

    @Test
    void deleteMarksTheDocumentDeletedAndClearsTheChunkCount() {
        when(mapper.selectOne(any())).thenReturn(new KnowledgeDocument());
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.delete(1L, 7L);

        // status 和 deleted_at 在同一条 UPDATE 里：不会出现“进了回收站却还是 READY”
        assertThat(updateValues(1).get(0)).contains(KnowledgeDocument.STATUS_DELETED, 0, NOW);
    }

    @Test
    void restoreRequeuesTheDocumentForProcessing() {
        when(mapper.selectOne(any())).thenReturn(new KnowledgeDocument());
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.restore(1L, 7L);

        // 改回 PROCESSING，条件里要求原来是 DELETED；然后发事件，后台重新切块、向量化
        assertThat(updateValues(1).get(0)).contains(KnowledgeDocument.STATUS_PROCESSING, KnowledgeDocument.STATUS_DELETED);
        verify(events).publishEvent(new DocumentQueuedEvent(7L));
    }

    @Test
    void chunkDeletionFailureDoesNotFailTheDelete() {
        when(mapper.selectOne(any())).thenReturn(new KnowledgeDocument());
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new RuntimeException("PostgreSQL is down")).when(processor).deleteChunks(7L);

        // 残留的块检索不到，到期清理时会再删，所以删除照样算成功
        assertThatCode(() -> service.delete(1L, 7L)).doesNotThrowAnyException();
    }

    @Test
    void deletingSomeoneElsesDocumentReturns404() {
        // selectOne 带着 user_id 条件查不到
        assertThatThrownBy(() -> service.delete(1L, 7L)).satisfies(e -> assertStatus(e, 404));
    }

    @Test
    void filenameKeepsOnlyTheLastPathSegment() {
        assertThat(KnowledgeService.cleanFilename("C:\\Users\\nick\\notes.txt")).isEqualTo("notes.txt");
    }
}
