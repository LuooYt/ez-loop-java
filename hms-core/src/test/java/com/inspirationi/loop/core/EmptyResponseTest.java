package com.inspirationi.loop.core;

import com.inspirationi.loop.tool.Tool;
import com.inspirationi.loop.tool.ToolContext;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型返回空内容时不得静默结束。
 * <p>
 * 实测（4B 本地模型）出现过：用户发「继续」，模型既没有工具调用也没有任何文本，
 * 循环正常退出、返回空串。界面于是只剩一个「空闲」状态 —— 用户看到的是「点了发送，
 * 什么都没发生」，既不知道是否失败，也不知道下一步怎么办。
 */
class EmptyResponseTest {

    private static ToolRegistry noopRegistry() {
        ToolRegistry r = new ToolRegistry();
        r.register(new Tool() {
            @Override public String name() { return "Noop"; }
            @Override public String description() { return "does nothing"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public String execute(Map<String, Object> in, ToolContext c) { return "ok"; }
        });
        return r;
    }

    private static AgentLoop loopWith(ChatModel model) {
        return new AgentLoop(model, noopRegistry(), ToolContext.defaultContext(),
                "SYSTEM", new TokenTracker(), 5);
    }

    private static ChatModel replying(AssistantMessage message) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(message, ChatGenerationMetadata.NULL)));
            }
            @Override public ChatOptions getOptions() { return null; }
        };
    }

    /** 末轮空回复：结果必须非空，并告诉用户可以重试。 */
    @Test
    void emptyReplyIsNotReturnedAsEmptyString() {
        AgentLoop loop = loopWith(replying(new AssistantMessage("")));

        String result = loop.run("继续");

        assertFalse(result == null || result.isBlank(),
                "空回复被原样返回，前端只会显示「空闲」，用户无从判断发生了什么");
        assertTrue(result.contains("没有") || result.contains("重试"),
                "空回复的提示应说明情况并给出下一步，实际: " + result);
    }

    /** 只有空白字符同样视为空回复。 */
    @Test
    void whitespaceOnlyReplyIsTreatedAsEmpty() {
        AgentLoop loop = loopWith(replying(new AssistantMessage("   \n\t ")));

        assertFalse(loop.run("继续").isBlank(), "纯空白回复同样应给出说明");
    }

    /** 正常回复不受影响 —— 保护既有行为。 */
    @Test
    void normalReplyIsReturnedVerbatim() {
        AgentLoop loop = loopWith(replying(new AssistantMessage("正常回答")));

        assertTrue(loop.run("go").equals("正常回答"));
    }

    /**
     * 空助手消息不得进入历史。
     * <p>
     * 留一条空 assistant 在历史里，之后每次请求上游都会判为非法消息而 400 ——
     * 会话从此再也发不出消息，而用户看到的只是「一直失败」。
     */
    @Test
    void emptyAssistantIsNotStoredInHistory() {
        List<Prompt> prompts = new java.util.ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(""), ChatGenerationMetadata.NULL)));
            }
            @Override public ChatOptions getOptions() { return null; }
        };
        AgentLoop loop = loopWith(model);

        loop.run("继续");
        loop.run("继续");

        List<Message> history = loop.copyMessageHistory();
        assertTrue(history.stream().noneMatch(m -> m instanceof AssistantMessage),
                "历史里不应出现空助手消息，实际：" + history);

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).getInstructions().stream()
                        .noneMatch(m -> m instanceof AssistantMessage),
                "第二次请求的 prompt 里不应带上空助手消息");
    }
}
