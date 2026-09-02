package com.inspirationi.loop.core.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要文本必须从<b>全部</b> Generation 里取，不能只看第一个。
 * <p>
 * 一次响应常被 provider 拆成多片：推理模型先给思考内容再给正文，工具调用与文本
 * 混排时也会分片。压缩层此前只读 {@code response.getResult()}（第一个 Generation），
 * 首片不含文本时就判定「模型返回空摘要」—— 实测 claude-opus-5 下
 * {@code completionTokens=1975} 而正文为空、{@code finishReason=end_turn}，
 * 4 次重试全部失败并触发熔断，压缩此后彻底不可用。
 * <p>
 * 本测试用「首片空、次片有正文」的响应复现该形状，与具体厂商无关。
 */
class MultiGenerationSummaryTest {

    private static List<Message> historyWithRounds(int rounds) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 0; i < rounds; i++) {
            history.add(new UserMessage("第 " + i + " 轮提问，内容足够长以便被渲染进摘要请求。"));
            history.add(new AssistantMessage("第 " + i + " 轮回答。"));
        }
        return history;
    }

    /** 用给定的若干段文本构造多 Generation 响应（空串表示该片无正文）。 */
    private static ChatModel modelReturning(String... texts) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                List<Generation> gens = new ArrayList<>();
                for (String t : texts) {
                    gens.add(new Generation(new AssistantMessage(t), ChatGenerationMetadata.NULL));
                }
                return new ChatResponse(gens);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }
        };
    }

    /**
     * 首片无正文、次片是真正的摘要 —— 压缩必须成功。
     * <p>
     * 这是修复前失败的那种形状：只读首片会得到空串 → 判定空摘要 → 重试耗尽 → 熔断。
     */
    @Test
    void summaryInLaterGenerationIsStillFound() {
        // 首片空（如推理模型的思考分片），次片才是摘要
        ChatModel model = modelReturning("", "这是真正的摘要正文。");

        List<Message> history = historyWithRounds(20);
        List<Message> compacted = new FullCompact(model).compact(history);

        assertNotNull(compacted,
                "摘要在第二个 Generation 里，压缩应当成功。为 null 说明只读了首片");
        assertTrue(compacted.size() < history.size(),
                "压缩后条数应减少：" + history.size() + " → " + compacted.size());
        // 摘要正文应出现在压缩后的历史里
        assertTrue(compacted.stream().anyMatch(
                        m -> String.valueOf(m.getText()).contains("这是真正的摘要正文")),
                "压缩后的历史应包含从次片取到的摘要");
    }

    /** 多片都有正文时应全部拼接，不能只取一片。 */
    @Test
    void multipleTextGenerationsAreConcatenated() {
        ChatModel model = modelReturning("摘要前半。", "摘要后半。");

        List<Message> compacted = new FullCompact(model).compact(historyWithRounds(20));

        assertNotNull(compacted);
        String all = compacted.stream().map(m -> String.valueOf(m.getText()))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("摘要前半") && all.contains("摘要后半"),
                "多个正文分片都应进入摘要，实际：" + all);
    }

    /** 所有分片都没有正文 —— 这才是真正的「空摘要」，应返回 null。 */
    @Test
    void allBlankGenerationsStillCountAsNoSummary() {
        ChatModel model = modelReturning("", "   ");

        assertNull(new FullCompact(model).compact(historyWithRounds(20)),
                "全部分片都无正文时应返回 null，让编排层按失败处理");
    }

    /** 单片有正文 —— 非推理模型的常见形状，行为与修复前一致。 */
    @Test
    void singleTextGenerationWorksAsBefore() {
        ChatModel model = modelReturning("SUMMARY");

        List<Message> compacted = new FullCompact(model).compact(historyWithRounds(20));

        assertNotNull(compacted, "单片正文应正常压缩");
        assertTrue(compacted.stream().anyMatch(
                m -> String.valueOf(m.getText()).contains("SUMMARY")));
    }
}
