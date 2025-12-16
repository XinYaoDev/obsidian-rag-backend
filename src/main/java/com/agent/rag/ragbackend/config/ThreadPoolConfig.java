package com.agent.rag.ragbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    /**
     * 专门用于数据库异步入库的线程池
     * Bean 名称 "dbExecutor" 对应 @Qualifier("dbExecutor")
     */
    @Bean("dbExecutor")
    public Executor dbExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 1. 核心线程数：系统空闲时保留的线程数
        // 既然是 IO 密集型操作（写数据库），可以设置得稍大一些，例如 CPU核数 * 2
        executor.setCorePoolSize(4);

        // 2. 最大线程数：当队列满时，最多能创建多少个线程
        // 注意：这个数字最好不要超过你数据库连接池（HikariCP）的最大连接数（默认通常是10）
        // 否则线程创建出来了，也抢不到数据库连接，白白等待
        executor.setMaxPoolSize(10);

        // 3. 队列容量：核心线程都在忙时，新任务在队列中等待
        // 设置一个合理的值，防止内存溢出
        executor.setQueueCapacity(200);

        // 4. 线程前缀：方便在日志和监控工具（如 JConsole, VisualVM）中区分
        executor.setThreadNamePrefix("async-db-save-");

        // 5. 拒绝策略：当队列满了且线程达到最大值，新任务怎么办？
        // CallerRunsPolicy: 由调用者线程（这里就是处理 SSE 的那个线程）自己去执行入库。
        // 好处：保证数据不丢失（虽然会稍微阻塞一下流式推送到前端的速度，但在高负载下是一种自我保护）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 6. 优雅关闭：应用关闭时，是否等待任务执行完
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 初始化
        executor.initialize();
        return executor;
    }
}
