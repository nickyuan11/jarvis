package com.nick.jarvis.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.mapper.UserMemoryMapper;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private UserMemoryMapper mapper;

    private MemoryService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserMemory.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryService(mapper);
    }

    private static UserMemory memory(long id, long userId, String content, String type) {
        UserMemory m = new UserMemory();
        m.setId(id);
        m.setUserId(userId);
        m.setContent(content);
        m.setType(type);
        m.setUpdateTime(LocalDateTime.of(2026, 11, 13, 15, 0));
        return m;
    }

    @Test
    void remembersANewFact() {
        when(mapper.selectCount(any())).thenReturn(0L);

        service.remember(1L, "  对花生过敏 ", "fact", UserMemory.SOURCE_EXPLICIT, 7L);

        ArgumentCaptor<UserMemory> saved = ArgumentCaptor.forClass(UserMemory.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("对花生过敏");
        assertThat(saved.getValue().getType()).isEqualTo(UserMemory.TYPE_FACT);
        assertThat(saved.getValue().getConversationId()).isEqualTo(7L);
    }

    @Test
    void refusesSensitiveInformationEvenWhenAsked() {
        assertThatThrownBy(() -> service.remember(1L, "我的银行卡号是 6222021234567890123", "FACT",
                UserMemory.SOURCE_EXPLICIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感信息");
        // 敏感信息在查库之前就被挡住了
        verifyNoInteractions(mapper);
    }

    @Test
    void refusesTooLongContent() {
        assertThatThrownBy(() -> service.remember(1L, "字".repeat(MemoryService.MAX_LENGTH + 1), "FACT",
                UserMemory.SOURCE_EXPLICIT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotSaveTheSameMemoryTwice() {
        UserMemory existing = memory(3L, 1L, "对花生过敏", UserMemory.TYPE_FACT);
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.remember(1L, "对花生过敏", "FACT", UserMemory.SOURCE_EXPLICIT, null)).isSameAs(existing);
        verify(mapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void refusesWhenFull() {
        when(mapper.selectCount(any())).thenReturn((long) MemoryService.MAX_MEMORIES);

        assertThatThrownBy(() -> service.remember(1L, "喜欢篮球", "FACT", UserMemory.SOURCE_EXPLICIT, null))
                .hasMessageContaining("已满");
    }

    @Test
    void cannotForgetSomeoneElsesMemory() {
        when(mapper.selectById(3L)).thenReturn(memory(3L, 2L, "对花生过敏", UserMemory.TYPE_FACT));

        assertThatThrownBy(() -> service.forget(1L, 3L)).isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void promptSectionSeparatesPreferencesFromFacts() {
        when(mapper.selectList(any())).thenReturn(List.of(
                memory(5L, 1L, "希望回答简短", UserMemory.TYPE_PREFERENCE),
                memory(3L, 1L, "对花生过敏", UserMemory.TYPE_FACT)));

        String section = service.promptSection(1L, java.time.ZoneOffset.UTC);

        assertThat(section)
                .contains("每次回答都要遵守", "- #5 希望回答简短（记录于 2026-11-13）")
                .contains("相关时参考", "- #3 对花生过敏（记录于 2026-11-13）");
        // 偏好写在事实前面
        assertThat(section.indexOf("希望回答简短")).isLessThan(section.indexOf("对花生过敏"));
    }

    @Test
    void noMemoriesMeansNothingIsAddedToThePrompt() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(service.promptSection(1L, java.time.ZoneOffset.UTC)).isEmpty();
    }
}
