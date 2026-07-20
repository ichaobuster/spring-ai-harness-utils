package io.github.springai.harness.toolgateway.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 工具目录数据源加载接口。
 * 可以通过从 JSON 配置文件加载，或者后续替换为数据库加载。
 *
 * @author ichaobuster
 */
public interface ToolCatalogProvider {

	/**
	 * 加载所有的工具定义（包括未启用的）。
	 *
	 * @return 所有工具定义的列表
	 */
	List<ToolDefinition> loadAll();

	/**
	 * 根据工具名称查找工具。
	 *
	 * @param toolName 工具名称
	 * @return 工具定义包
	 */
	Optional<ToolDefinition> findByName(String toolName);
}
