package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.knowledge.KnowledgeSearchService.Hit;

class ReplyCitationsTest {
    private final SourceRange range = new SourceRange(0, 10, 25, 40);

    @Test
    void repeatedSearchesShareNumbersButSeparateRepliesStartOver() {
        ReplyCitations reply = new ReplyCitations();
        Hit fee = new Hit("合同", 7L, 0, 0.8, "违约金", range);
        Hit exception = new Hit("合同", 7L, 1, 0.7, "免责条款", range);
        assertThat(reply.number(fee)).isEqualTo(1);
        assertThat(reply.number(exception)).isEqualTo(2);
        assertThat(reply.number(fee)).isEqualTo(1);
        assertThat(new ReplyCitations().number(exception)).isEqualTo(1);
    }

    @Test
    void onlyCompleteRegisteredMarkersAreSavedWithThePartialReply() {
        ReplyCitations reply = new ReplyCitations();
        reply.number(new Hit("合同", 7L, 0, 0.8, "违约金", range));
        reply.number(new Hit("合同", 7L, 1, 0.7, "免责条款", range));
        var saved = reply.usedIn("[9] [1][1] [999999999999] [2");
        assertThat(saved).containsExactly(new ReplyCitations.Citation(1, 7L, range));
        String json = ReplyCitations.encode(saved);
        assertThat(ReplyCitations.decode(json)).isEqualTo(saved);
        assertThat(ReplyCitations.numbers(null)).isEmpty();
    }
}
