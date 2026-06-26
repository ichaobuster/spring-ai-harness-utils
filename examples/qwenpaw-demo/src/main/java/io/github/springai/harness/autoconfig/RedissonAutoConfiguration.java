package io.github.springai.harness.autoconfig;

import io.github.springai.harness.HarnessAgentsProperties;
import io.github.springai.harness.task.AgentTaskConst;
import org.redisson.Redisson;
import org.redisson.RedissonNode;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.RedissonNodeConfig;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * RedissonConfig
 *
 * @author ichaobuster
 */
@Configuration
@ConditionalOnProperty(prefix = HarnessAgentsProperties.CONFIG_PREFIX, name = "tasks.enabled", havingValue = "true", matchIfMissing = false)
public class RedissonAutoConfiguration {

	@Bean(name = "scheduledTaskRedissonClient", destroyMethod = "shutdown")
	public RedissonClient redissonClient(RedisProperties redisProperties) {
		Config config = new Config();

		// 拼接 Redis 地址格式 (注意：Redisson 要求必须以 redis:// 或 rediss:// 开头)
		String prefix = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
		String address = prefix + redisProperties.getHost() + ":" + redisProperties.getPort();

		// 这里以单机模式为例。如果是集群或哨兵模式，需要使用 config.useClusterServers() 或 config.useSentinelServers()
		config.useSingleServer()
				.setAddress(address)
				.setDatabase(redisProperties.getDatabase())
				.setPassword(redisProperties.getPassword())
				// 如果在 yml 配置了 timeout，也可以一并拿过来
				.setTimeout(redisProperties.getTimeout() != null ? (int) redisProperties.getTimeout().toMillis() : 3000);

		return Redisson.create(config);
	}

	@Bean(name = "scheduledTaskRedissonNode", destroyMethod = "shutdown")
	public RedissonNode redissonNode(@Qualifier("scheduledTaskRedissonClient") RedissonClient redissonClient, BeanFactory beanFactory) {
		RedissonNodeConfig nodeConfig = new RedissonNodeConfig(redissonClient.getConfig());

		// 定义 ExecutorService 的名称和并发工作线程数
		// "myDistributedExecutor" 是我们后续提交任务时要用的标识
		nodeConfig.setExecutorServiceWorkers(Collections.singletonMap(AgentTaskConst.AGENT_TASK_EXECUTOR_NAME, AgentTaskConst.AGENT_TASK_WORKER_COUNT));

		// 【关键】将会把 Spring 的 BeanFactory 传给 Redisson
		// 这样 Redisson 在反序列化任务后，才能把 @Autowired 的服务注入到任务类中
		nodeConfig.setBeanFactory(beanFactory);

		RedissonNode node = RedissonNode.create(nodeConfig, redissonClient);
		node.start(); // 启动 Worker 节点监听任务

		return node;
	}
}
