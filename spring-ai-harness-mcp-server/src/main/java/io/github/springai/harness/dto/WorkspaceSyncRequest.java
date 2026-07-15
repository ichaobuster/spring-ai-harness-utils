package io.github.springai.harness.dto;

import java.util.List;

/**
 * 工作空间同步请求体。
 * 用于 agent bootstrap 阶段从云端批量拉取所需文件。
 *
 * @author ichaobuster
 */
public record WorkspaceSyncRequest(
		/** 需要同步的文件/目录路径列表 */
		List<String> paths,
		/** 打包 skills/ 时是否包含全部文件，null 时使用 properties 配置的默认值 */
		Boolean skillFullContent
) {
}
