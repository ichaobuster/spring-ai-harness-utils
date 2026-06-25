package io.github.springai.harness.autoconfig;

import io.github.springai.harness.task.AgentTaskConst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.RedissonNode;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.RedissonNodeConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class RedissonAutoConfigurationTest {

	@Test
	@DisplayName("测试 RedisProperties 到 Redisson Config 的参数映射是否正确")
	void testRedissonClientCreation() {
		// 1. 准备测试数据：模拟 Spring Boot 注入的 RedisProperties
		RedisProperties properties = new RedisProperties();
		properties.setHost("example.com");
		properties.setPort(6380);
		properties.setPassword("peaceWalker");
		properties.setDatabase(5);
		properties.setTimeout(Duration.ofMillis(5000));

		RedissonAutoConfiguration redissonConfig = new RedissonAutoConfiguration();

		// 2. 核心：使用 MockedStatic 拦截静态方法 Redisson.create()，防止真实发起网络连接
		try (MockedStatic<Redisson> mockedRedisson = mockStatic(Redisson.class)) {

			// 准备一个假的返回值
			RedissonClient mockClient = mock(RedissonClient.class);

			// 使用 ArgumentCaptor 捕获方法内部组装的 Config 对象
			ArgumentCaptor<Config> configCaptor = ArgumentCaptor.forClass(Config.class);

			// 当调用 Redisson.create() 时，记录传入的参数，并返回假 Client
			mockedRedisson.when(() -> Redisson.create(configCaptor.capture())).thenReturn(mockClient);

			// 3. 执行待测试的方法
			RedissonClient result = redissonConfig.redissonClient(properties);

			// 4. 断言验证
			assertNotNull(result, "返回的 RedissonClient 不应为空");

			// 提取被捕获的 Config 对象
			Config capturedConfig = configCaptor.getValue();
			SingleServerConfig singleServerConfig = capturedConfig.useSingleServer();

			assertNotNull(singleServerConfig, "应正确初始化为单机模式 (SingleServerConfig)");

			// 验证字段映射是否符合预期
			assertEquals("redis://example.com:6380", singleServerConfig.getAddress(), "SSL前缀、Host或Port拼接错误");
			assertEquals("peaceWalker", singleServerConfig.getPassword(), "密码映射错误");
			assertEquals(5, singleServerConfig.getDatabase(), "数据库索引映射错误");
			assertEquals(5000, singleServerConfig.getTimeout(), "超时时间映射错误");
		}
	}

	@Test
	void testRedissonNodeBeanCreation() {
		// 1. Mock 依赖项
		RedissonClient redissonClient = mock(RedissonClient.class);
		BeanFactory beanFactory = mock(BeanFactory.class);
		RedissonNode mockRedissonNode = mock(RedissonNode.class);

		// 模拟基础配置，避免 RedissonNodeConfig 内部报空指针
		Config baseConfig = new Config();
		baseConfig.useSingleServer().setAddress("redis://example.com:6379");
		when(redissonClient.getConfig()).thenReturn(baseConfig);

		RedissonAutoConfiguration redissonConfig = new RedissonAutoConfiguration();

		// 2. 【关键】拦截 RedissonNode 的静态方法 create
		try (MockedStatic<RedissonNode> mockedStaticNode = mockStatic(RedissonNode.class)) {

			// 当调用静态创建方法时，返回我们 Mock 的节点对象
			mockedStaticNode.when(() -> RedissonNode.create(any(RedissonNodeConfig.class), eq(redissonClient)))
					.thenReturn(mockRedissonNode);

			// 用于捕获传入静态方法的配置参数，以便进行断言验证
			ArgumentCaptor<RedissonNodeConfig> configCaptor = ArgumentCaptor.forClass(RedissonNodeConfig.class);

			// 3. 调用被测方法
			RedissonNode resultNode = redissonConfig.redissonNode(redissonClient, beanFactory);

			// 4. 断言与验证
			assertNotNull(resultNode, "生成的 RedissonNode 实例不应为空");

			// 验证 node.start() 是否被触发
			verify(mockRedissonNode, times(1)).start();

			// 验证静态创建方法是否被调用，并捕获当时传入的 config
			mockedStaticNode.verify(() -> RedissonNode.create(configCaptor.capture(), eq(redissonClient)), times(1));

			// 验证配置细节是否符合预期
			RedissonNodeConfig capturedConfig = configCaptor.getValue();
			assertNotNull(capturedConfig);
			assertEquals(beanFactory, capturedConfig.getBeanFactory(), "BeanFactory 应该被正确设置到节点配置中");
			assertEquals(AgentTaskConst.AGENT_TASK_WORKER_COUNT, capturedConfig.getExecutorServiceWorkers().get(AgentTaskConst.AGENT_TASK_EXECUTOR_NAME), "线程池大小应为 " + AgentTaskConst.AGENT_TASK_WORKER_COUNT);
		}
	}

}