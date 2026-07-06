package io.github.springai.harness.autoconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author ichaobuster
 */
@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class AliyunOssProperties {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
}
