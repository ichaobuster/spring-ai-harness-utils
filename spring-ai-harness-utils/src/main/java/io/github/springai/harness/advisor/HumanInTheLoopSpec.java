package io.github.springai.harness.advisor;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

public class HumanInTheLoopSpec {
    @Data
    @AllArgsConstructor
    public static class HitlRequest {
        private boolean hitlRequired;
        private String tool;
        private Map<String, Object> args;
        private String toolId;
    }

    @Data
    public static class HitlResponse {
        private HumanInTheLoopSpec.HitlRequest request;
        private HumanInTheLoopSpec.Permission permission;
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