package com.example.spring.rabbitmq;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.core.RabbitMessageOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.CachingConnectionFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.task.TaskExecutorBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.shaded.com.google.common.util.concurrent.RateLimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.IntStream.range;
import static org.springframework.messaging.support.MessageBuilder.withPayload;

/**
 * <a href="https://www.baeldung.com/java-rabbitmq-channels-connections">Channels and Connections in RabbitMQ</a>
 * <p>
 * <a href="https://www.cloudamqp.com/blog/part4-rabbitmq-13-common-errors.html">13 Common RabbitMQ Mistakes and How to Avoid Them</a>
 * <p>
 * <a href="https://docs.spring.io/spring-amqp/reference/html/#choose-container">Choosing a Container</a>
 */
@SpringBootTest
@ActiveProfiles(profiles = "consumer")
class ConsumerTest {

    static final Message<Integer> MESSAGE = withPayload(1).build();

    @Autowired
    RabbitMessageOperations rabbit;

    @Test
    void consumption() {
        var concurrency = 3;
        var executorService = newFixedThreadPool(concurrency);

        var rateLimiter = RateLimiter.create(1);

        range(0, concurrency).forEach(value -> executorService.submit(() -> {
            while (true) {
                rateLimiter.acquire();
                rabbit.send("messages", "", MESSAGE);
            }
        }));

        while (true);
    }

    @TestConfiguration
    @Slf4j
    static class Listener {

        @RabbitListener(id = "messages.handling", queues = "messages.handling")
        void listen(Message<Integer> message) {
            log.trace("Received {}", message);
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        ContainerCustomizer<DirectMessageListenerContainer> containerCustomizer(
                @Value("${spring.application.name}") String applicationName) {

            var consumerNumber = new AtomicInteger(1);
            return container -> container.setConsumerTagStrategy(queue ->
                    "%s-%s".formatted(applicationName, consumerNumber.getAndIncrement()));
        }

        @Bean
        RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer, ConnectionFactory connectionFactory) {
            var template = new RabbitTemplate();
            template.setUsePublisherConnection(true);

            configurer.configure(template, connectionFactory);

            return template;
        }

        @Bean
        CachingConnectionFactory rabbitConnectionFactory(
                RabbitConnectionFactoryBeanConfigurer factoryBeanConfigurer,
                CachingConnectionFactoryConfigurer factoryConfigurer,
                ObjectProvider<ConnectionFactoryCustomizer> connectionFactoryCustomizers) throws Exception {

            var connectionFactoryBean = new RabbitConnectionFactoryBean();
            factoryBeanConfigurer.configure(connectionFactoryBean);
            connectionFactoryBean.afterPropertiesSet();
            com.rabbitmq.client.ConnectionFactory connectionFactory = connectionFactoryBean.getObject();
            connectionFactoryCustomizers.orderedStream()
                    .forEach((customizer) -> customizer.customize(connectionFactory));

            var factory = new CachingConnectionFactory(connectionFactory);
            factory.setExecutor(consumerTaskExecutor());
            factoryConfigurer.configure(factory);

            return factory;
        }

        @Bean
        ThreadPoolTaskExecutor consumerTaskExecutor() {
            return new TaskExecutorBuilder()
                    .corePoolSize(4)
                    .maxPoolSize(8)
                    .queueCapacity(20)
                    .keepAlive(Duration.ZERO)
                    .threadNamePrefix("messages.handling-")
                    .build();
        }

        @Bean
        ConnectionNameStrategy connectionNameStrategy(@Value("${spring.application.name}") String applicationName) {
            return connectionFactory -> applicationName;
        }
    }
}
