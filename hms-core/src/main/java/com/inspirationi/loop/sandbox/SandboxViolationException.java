package com.inspirationi.loop.sandbox;

/** 沙箱路径校验失败。消息会直接回传给模型。 */
public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) {
        super(message);
    }
}
