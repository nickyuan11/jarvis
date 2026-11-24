package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.mapper.MessageMapper;

class CitationControllerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final CitationController controller = new CitationController(messages, documents);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocument.class);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(3L, null));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unavailableOrUnregisteredCitationDoesNotReadDocument() {
        assertUnavailable();
        String otherCitation = ReplyCitations.encode(List.of(new ReplyCitations.Citation(2, 8, new SourceRange(0, 0, 1, 2))));
        when(messages.ownedCitations(12L, 3L)).thenReturn(otherCitation);
        assertUnavailable();
        verifyNoInteractions(documents);
    }

    @Test
    void unavailableDocumentCanBePreviewedAgainAfterRestore() {
        var source = new ReplyCitations.Citation(1, 8, new SourceRange(0, 2, 4, 6));
        when(messages.ownedCitations(12L, 3L)).thenReturn(ReplyCitations.encode(List.of(source)));
        var doc = new KnowledgeDocument();
        doc.setFilename("合同");
        doc.setContent("甲乙丙丁戊己");
        when(documents.selectOne(any())).thenReturn(null, doc, null);
        assertUnavailable();
        var result = controller.preview(12L, 1);
        assertThat(result.getBody()).isEqualTo(new CitationController.Preview("合同", "甲乙", "丙丁", "戊己"));
        assertThat(result.getHeaders().getCacheControl()).contains("no-store");
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> controller.preview(12L, 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
