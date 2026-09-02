package com.inspirationi.loop.core.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 从摘要响应中取出可用文本 —— 汇总全部 {@code Generation} 里的正文。
 * <p>
 * <b>为什么不能只读 {@code getResult()}</b>：它只返回第一个 Generation，而一次
 * 响应常被 provider 拆成多片（推理模型先思考再答、工具调用与文本混排等）。首片
 * 不含文本时，压缩层会误判成「模型返回空摘要」，4 次重试全部失败并触发熔断；
 * 熔断是永久的，此后该会话再不压缩，上下文一路涨到被上游拒绝。
 * <p>
 * 实测于 claude-opus-5：{@code completionTokens=1975} 而
 * {@code getResult().getOutput().getText()} 为空、{@code finishReason=end_turn}
 * （正常完成，非截断）—— 内容确实生成了，只是不在首片。此处按「取所有正文」处理，
 * 不依赖任何厂商私有的分片标记，因此对 Anthropic / OpenAI 等实现同样成立。
 * <p>
 * 一个可复用的排查经验：遇到「内容去哪了」，先把 {@code finishReason} 与
 * output metadata 的键名打出来，不要猜响应结构。
 */
final class SummaryText {

    private static final Logger log = LoggerFactory.getLogger(SummaryText.class);

    private SummaryText() {
    }

    /**
     * 取出响应中可作摘要使用的文本。
     *
     * @param response 摘要请求的响应，可为 null
     * @param label    调用方标识（用于日志区分 Full / SessionMemory 层）
     * @return 摘要文本（多个正文分片以换行拼接）；没有任何正文时返回 {@code null}
     */
    static String extract(ChatResponse response, String label) {
        if (response == null || response.getResults() == null) {
            return null;
        }

        /*
         * 遍历全部 Generation 并拼接其中的非空文本，而不是只看 getResult()
         * （它返回的是第一个）。
         *
         * 一次响应可能被 provider 拆成多个 Generation：推理模型会先给出思考内容
         * 再给正文，工具调用与文本混排时也会分片。此前只读 getResult() 拿到的是
         * 首个分片 —— 若它不含文本（如推理模型的思考分片），压缩层就判定「模型
         * 返回空摘要」，4 次重试全失败并最终熔断；而熔断是永久的，此后该会话再不
         * 压缩，上下文一路涨到被上游拒绝。
         *
         * 不去识别「哪种分片是思考」：那要依赖各 provider 的私有 metadata 字段
         * （Anthropic 用 signature、OpenAI 又是另一套），把厂商细节写进压缩层。
         * 无文本的分片在这里自然被跳过，按「取所有正文」处理对各家都成立。
         */
        StringBuilder sb = new StringBuilder();
        for (var generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            String text = generation.getOutput().getText();
            if (text != null && !text.isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(text);
            }
        }

        if (!sb.isEmpty()) {
            return sb.toString();
        }

        // 所有 generation 都没有正文 —— 记下响应结构，便于判断是模型没产出还是
        // provider 的映射又变了形状（这类问题此前只能靠猜）
        var first = response.getResult();
        log.warn("{} compact: no usable text in any of {} generation(s). finishReason={}, "
                        + "firstOutputMetadataKeys={}, usage={}",
                label,
                response.getResults().size(),
                first != null && first.getMetadata() != null
                        ? first.getMetadata().getFinishReason() : "?",
                first != null && first.getOutput() != null
                        && first.getOutput().getMetadata() != null
                        ? first.getOutput().getMetadata().keySet() : "null",
                response.getMetadata() != null ? response.getMetadata().getUsage() : "null");
        return null;
    }

}
