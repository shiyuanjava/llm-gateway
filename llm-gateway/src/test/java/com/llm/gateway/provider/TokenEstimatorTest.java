package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.knuddels.jtokkit.api.EncodingType;
import com.llm.gateway.api.dto.ChatMessage;

class TokenEstimatorTest {

    @Test
    void emptyAndNullTextCountZero() {
        assertEquals(0, TokenEstimator.estimate((String) null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void countsEnglishWithRealBpe() {
        // tiktoken 黄金样例："hello world" 在 cl100k 与 o200k 下均为 2 token
        assertEquals(2, TokenEstimator.estimate("gpt-4o", "hello world"));
        assertEquals(2, TokenEstimator.estimate("gpt-3.5-turbo", "hello world"));
    }

    @Test
    void countsChineseReasonably() {
        // 中文按真实 BPE 计数；区间断言避免绑死 jtokkit 词表版本
        int tokens = TokenEstimator.estimate("claude-opus-4-8", "你好，世界！这是一次分词测试。");
        assertTrue(tokens >= 5 && tokens <= 30, "实际: " + tokens);
    }

    @Test
    void selectsEncodingByModel() {
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-3.5-turbo"));
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-4"));
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-4-turbo"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("gpt-4o"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("gpt-4.1-mini"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("gpt-4.5-preview"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("claude-opus-4-8"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("deepseek-v4-pro"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor(null));
    }

    @Test
    void messagesAddPerMessageOverhead() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello world"));
        // 2（内容）+ 4（每条消息的对话格式开销）
        assertEquals(6, TokenEstimator.estimate("gpt-4o", messages));
    }

    @Test
    void emptyMessagesAndNullContentAreHandled() {
        // 空列表计 0
        assertEquals(0, TokenEstimator.estimate("gpt-4o", List.of()));
        // content 为 null 的消息仍计每条 4 的格式开销
        assertEquals(4, TokenEstimator.estimate("gpt-4o", List.of(new ChatMessage(null, null))));
    }
}
