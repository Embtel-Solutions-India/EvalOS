package com.ie.evalos.chat.live;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Where chat's outbound calls run, off the request thread (Unit 57; final review I6).
 *
 * <p>A message send answered only after one Ably publish per member, and a presence check per
 * member, all in series — seconds on a busy conversation, and a slow Ably would stall the route.
 *
 * <p>ponytail: <strong>fan-out is one thread on purpose</strong>, so events reach Ably in commit
 * order; widen it (per-conversation ordering) or switch to Ably batch publish if volume grows.
 * Push gets two threads: sends are independent. Both are Spring-managed, shut down with the app.
 */
@Configuration
public class ChatExecutors {

	@Bean(name = "chatFanoutExecutor")
	public Executor chatFanoutExecutor() {
		return pool("chat-fanout-", 1);
	}

	@Bean(name = "chatPushExecutor")
	public Executor chatPushExecutor() {
		return pool("chat-push-", 2);
	}

	private static ThreadPoolTaskExecutor pool(String prefix, int threads) {
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setThreadNamePrefix(prefix);
		pool.setCorePoolSize(threads);
		pool.setMaxPoolSize(threads);
		pool.setQueueCapacity(10_000);
		pool.setWaitForTasksToCompleteOnShutdown(true);
		pool.initialize();
		return pool;
	}
}
