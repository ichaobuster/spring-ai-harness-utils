package io.github.springai.harness.advisor;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

public class HumanInTheLoopSpec {
    @Data
    @AllArgsConstructor
    static final class HitlRequest {
        private final boolean hitlRequired;
        private final String tool;
        private final Map<String, Object> args;
        private final String toolCallId;
    }

    @Data
    public static final class HitlResponse {
        private final HumanInTheLoopSpec.HitlRequest request;
        private final HumanInTheLoopSpec.Permission permission;
        private boolean toolCalled = false;

        public HitlResponse(HumanInTheLoopSpec.HitlRequest request, HumanInTheLoopSpec.Permission permission) {
            this.request = request;
            this.permission = permission;
        }

    }

    public enum Permission {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }

}