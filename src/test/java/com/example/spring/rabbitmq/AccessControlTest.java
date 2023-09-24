package com.example.spring.rabbitmq;

import com.playtika.testcontainer.rabbitmq.RabbitMQProperties;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.stream.Stream;

import static com.rabbitmq.client.AMQP.ACCESS_REFUSED;
import static com.rabbitmq.client.AMQP.NOT_ALLOWED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.amqp.core.MessageBuilder.withBody;

/**
 * <a href="https://www.rabbitmq.com/access-control.html">Access Control</a>
 */
@SpringBootTest
@ActiveProfiles(profiles = "access-control")
class AccessControlTest {

    static final Message MESSAGE = withBody("1".getBytes(UTF_8)).build();

    @Autowired
    RabbitMQProperties properties;

    /**
     * A first level of access control is enforced at connection establishment,
     * with the server checking whether the user has any permissions to access the virtual hosts,
     * and rejecting the connection attempt otherwise.
     * <p>
     * Resources access is not controlled by user tags. Currently only management UI is controlled by them.
     */
    @Nested
    @DisplayName("Connection")
    class ConnectionTest {

        @Test
        void isForbidden() {
            var rabbit = newRabbitOperations("admin", "rabbitmq");

            assertThatExceptionIsThrownBy(() -> rabbit.getConnectionFactory().createConnection())
                    .extracting(AMQP.Connection.Close.class::cast)
                    .extracting(AMQP.Connection.Close::getReplyCode)
                    .isEqualTo(NOT_ALLOWED);
        }
    }

    /**
     * A second level of access control is enforced when configure, write or read operations are performed on resources.
     * <p>
     * In order to perform an operation on a resource the user must have been granted the appropriate permissions for it.
     */
    @Nested
    @DisplayName("Operation on resource")
    class OperationOnResourceTest {

        @Nested
        @DisplayName("configure")
        class ConfigureTest {

            @Nested
            @DisplayName("exchange.delete")
            class ExchangeDeleteTest {

                @ParameterizedTest
                @MethodSource("isForbiddenArgumentsProvider")
                void isForbidden(String username, String password, String exchangeName) {
                    var rabbit = newRabbitOperations(username, password);

                    assertThatExceptionIsThrownBy(() -> rabbit.execute(channel -> channel.exchangeDelete(exchangeName)))
                            .extracting(AMQP.Channel.Close.class::cast)
                            .extracting(AMQP.Channel.Close::getReplyCode)
                            .isEqualTo(ACCESS_REFUSED);
                }

                static Stream<Arguments> isForbiddenArgumentsProvider() {
                    return Stream.of(
                            arguments("producer", "rabbitmq", "commands"),
                            arguments("producer", "rabbitmq", "commands.dlx"),
                            arguments("producer", "rabbitmq", "events"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx"),

                            arguments("consumer-a", "rabbitmq", "commands"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx"),
                            arguments("consumer-a", "rabbitmq", "events"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx"),

                            arguments("consumer-b", "rabbitmq", "commands"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx"),
                            arguments("consumer-b", "rabbitmq", "events"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx"));
                }
            }

            @Nested
            @DisplayName("exchange.delete")
            class ExchangeBindTest {

                @ParameterizedTest
                @MethodSource("isForbiddenArgumentsProvider")
                void isForbidden(String username, String password, String source, String destination) {
                    var rabbit = newRabbitOperations(username, password);

                    assertThatExceptionIsThrownBy(() -> rabbit.execute(channel -> channel.exchangeBind(destination, source, "")))
                            .extracting(AMQP.Channel.Close.class::cast)
                            .extracting(AMQP.Channel.Close::getReplyCode)
                            .isEqualTo(ACCESS_REFUSED);
                }

                static Stream<Arguments> isForbiddenArgumentsProvider() {
                    return Stream.of(
                            arguments("producer", "rabbitmq", "commands", "commands.dlx"),
                            arguments("producer", "rabbitmq", "commands", "events"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-a.dlx"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-b.dlx"),
                            arguments("producer", "rabbitmq", "commands.dlx", "commands"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-a.dlx"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-b.dlx"),
                            arguments("producer", "rabbitmq", "events", "commands"),
                            arguments("producer", "rabbitmq", "events", "commands.dlx"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-a.dlx"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-b.dlx"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "commands"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "commands.dlx"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "events"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlx"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "commands"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "commands.dlx"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "events"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlx"),

                            arguments("consumer-a", "rabbitmq", "commands", "commands.dlx"),
                            arguments("consumer-a", "rabbitmq", "commands", "events"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-a.dlx"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-b.dlx"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "commands"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-a.dlx"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-b.dlx"),
                            arguments("consumer-a", "rabbitmq", "events", "commands"),
                            arguments("consumer-a", "rabbitmq", "events", "commands.dlx"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-a.dlx"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-b.dlx"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "commands"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "commands.dlx"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "events"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlx"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "commands"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "commands.dlx"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "events"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlx"),

                            arguments("consumer-b", "rabbitmq", "commands", "commands.dlx"),
                            arguments("consumer-b", "rabbitmq", "commands", "events"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-a.dlx"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-b.dlx"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "commands"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-a.dlx"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-b.dlx"),
                            arguments("consumer-b", "rabbitmq", "events", "commands"),
                            arguments("consumer-b", "rabbitmq", "events", "commands.dlx"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-a.dlx"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-b.dlx"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "commands"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "commands.dlx"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "events"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlx"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "commands"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "commands.dlx"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "events"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlx"));
                }
            }

            @Nested
            @DisplayName("queue.delete")
            class QueueDeleteTest {

                @ParameterizedTest
                @MethodSource("isForbiddenArgumentsProvider")
                void isForbidden(String username, String password, String queueName) {
                    var rabbit = newRabbitOperations(username, password);

                    assertThatExceptionIsThrownBy(() -> rabbit.execute(channel -> channel.queueDelete(queueName)))
                            .extracting(AMQP.Channel.Close.class::cast)
                            .extracting(AMQP.Channel.Close::getReplyCode)
                            .isEqualTo(ACCESS_REFUSED);
                }

                static Stream<Arguments> isForbiddenArgumentsProvider() {
                    return Stream.of(
                            arguments("producer", "rabbitmq", "commands.handling"),
                            arguments("producer", "rabbitmq", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-b"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlq"),

                            arguments("consumer-a", "rabbitmq", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlq"),

                            arguments("consumer-b", "rabbitmq", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlq"));
                }
            }

            @Nested
            @DisplayName("queue.bind")
            class QueueBindTest {

                @ParameterizedTest
                @MethodSource("isForbiddenArgumentsProvider")
                void isForbidden(String username, String password, String exchange, String queue) {
                    var rabbit = newRabbitOperations(username, password);

                    assertThatExceptionIsThrownBy(() -> rabbit.execute(channel -> channel.queueBind(queue, exchange, "")))
                            .extracting(AMQP.Channel.Close.class::cast)
                            .extracting(AMQP.Channel.Close::getReplyCode)
                            .isEqualTo(ACCESS_REFUSED);
                }

                static Stream<Arguments> isForbiddenArgumentsProvider() {
                    return Stream.of(
                            arguments("producer", "rabbitmq", "commands.dlx", "commands.handling"),
                            arguments("producer", "rabbitmq", "events", "commands.handling"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "commands.handling"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "commands.handling"),
                            arguments("producer", "rabbitmq", "commands", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "events", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-a.dlq"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-a.dlq"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-a.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlq"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-b"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-b"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b"),
                            arguments("producer", "rabbitmq", "events.consumer-b.dlx", "events.consumer-b"),
                            arguments("producer", "rabbitmq", "commands", "events.consumer-b.dlq"),
                            arguments("producer", "rabbitmq", "commands.dlx", "events.consumer-b.dlq"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-b.dlq"),
                            arguments("producer", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlq"),

                            arguments("consumer-a", "rabbitmq", "commands.dlx", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "events", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "commands", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "events", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-a.dlq"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-a.dlq"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-a.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlq"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-b"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-b"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx", "events.consumer-b"),
                            arguments("consumer-a", "rabbitmq", "commands", "events.consumer-b.dlq"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "events.consumer-b.dlq"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-b.dlq"),
                            arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlq"),

                            arguments("consumer-b", "rabbitmq", "commands.dlx", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "events", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "commands", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "events", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-a.dlq"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-a.dlq"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-a.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "events.consumer-a.dlq"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-b"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-b"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx", "events.consumer-b"),
                            arguments("consumer-b", "rabbitmq", "commands", "events.consumer-b.dlq"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "events.consumer-b.dlq"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-b.dlq"),
                            arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx", "events.consumer-b.dlq"));
                }
            }

            @Nested
            @DisplayName("queue.unbind")
            class QueueUnbindTest {

                @ParameterizedTest
                @MethodSource("isForbiddenArgumentsProvider")
                void isForbidden(String username, String password, String exchange, String queue) {
                    var rabbit = newRabbitOperations(username, password);

                    assertThatExceptionIsThrownBy(() -> rabbit.execute(channel -> channel.queueUnbind(queue, exchange, "")))
                            .extracting(AMQP.Channel.Close.class::cast)
                            .extracting(AMQP.Channel.Close::getReplyCode)
                            .isEqualTo(ACCESS_REFUSED);
                }

                static Stream<Arguments> isForbiddenArgumentsProvider() {
                    return Stream.of(
                            arguments("producer", "rabbitmq", "commands", "commands.handling"),
                            arguments("producer", "rabbitmq", "commands.dlx", "commands.handling.dlq"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-a"),
                            arguments("producer", "rabbitmq", "events", "events.consumer-b"),

                            arguments("consumer-a", "rabbitmq", "commands", "commands.handling"),
                            arguments("consumer-a", "rabbitmq", "commands.dlx", "commands.handling.dlq"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-a"),
                            arguments("consumer-a", "rabbitmq", "events", "events.consumer-b"),

                            arguments("consumer-b", "rabbitmq", "commands", "commands.handling"),
                            arguments("consumer-b", "rabbitmq", "commands.dlx", "commands.handling.dlq"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-a"),
                            arguments("consumer-b", "rabbitmq", "events", "events.consumer-b"));
                }
            }
        }

        @Nested
        @DisplayName("read")
        class ReadTest {

            @ParameterizedTest
            @MethodSource("isAllowedArgumentsProvider")
            void isAllowed(String username, String password, String queueName) {
                var rabbit = newRabbitOperations(username, password);

                assertThatNoException().isThrownBy(() -> rabbit.receive(queueName));
            }

            static Stream<Arguments> isAllowedArgumentsProvider() {
                return Stream.of(
                        arguments("producer", "rabbitmq", "commands.handling"),
                        arguments("consumer-a", "rabbitmq", "events.consumer-a"),
                        arguments("consumer-b", "rabbitmq", "events.consumer-b"));
            }

            @ParameterizedTest
            @MethodSource("isForbiddenArgumentsProvider")
            void isForbidden(String username, String password, String queueName) {
                var rabbit = newRabbitOperations(username, password);

                assertThatExceptionIsThrownBy(() -> rabbit.receive(queueName))
                        .extracting(AMQP.Channel.Close.class::cast)
                        .extracting(AMQP.Channel.Close::getReplyCode)
                        .isEqualTo(ACCESS_REFUSED);
            }

            static Stream<Arguments> isForbiddenArgumentsProvider() {
                return Stream.of(
                        arguments("producer", "rabbitmq", "commands.handling.dlq"),
                        arguments("producer", "rabbitmq", "events.consumer-a"),
                        arguments("producer", "rabbitmq", "events.consumer-a.dlq"),
                        arguments("producer", "rabbitmq", "events.consumer-b"),
                        arguments("producer", "rabbitmq", "events.consumer-b.dlq"),

                        arguments("consumer-a", "rabbitmq", "events.consumer-a.dlq"),
                        arguments("consumer-a", "rabbitmq", "commands.handling"),
                        arguments("consumer-a", "rabbitmq", "commands.handling.dlq"),
                        arguments("consumer-a", "rabbitmq", "events.consumer-b"),
                        arguments("consumer-a", "rabbitmq", "events.consumer-b.dlq"),

                        arguments("consumer-b", "rabbitmq", "events.consumer-b.dlq"),
                        arguments("consumer-b", "rabbitmq", "commands.handling"),
                        arguments("consumer-b", "rabbitmq", "commands.handling.dlq"),
                        arguments("consumer-b", "rabbitmq", "events.consumer-a"),
                        arguments("consumer-b", "rabbitmq", "events.consumer-a.dlq"));
            }
        }

        @Nested
        @DisplayName("write")
        class WriteTest {

            @ParameterizedTest
            @MethodSource("isAllowedArgumentsProvider")
            void isAllowed(String username, String password, String exchangeName) {
                var rabbit = newRabbitOperations(username, password);

                assertThatNoException().isThrownBy(() -> rabbit.send(exchangeName, "", MESSAGE));
            }

            static Stream<Arguments> isAllowedArgumentsProvider() {
                return Stream.of(
                        arguments("producer", "rabbitmq", "events"),
                        arguments("consumer-a", "rabbitmq", "commands"),
                        arguments("consumer-b", "rabbitmq", "commands"));
            }

            @ParameterizedTest
            @MethodSource("isForbiddenArgumentsProvider")
            void isForbidden(String username, String password, String exchangeName) {
                var rabbit = newRabbitOperations(username, password);

                assertThatExceptionIsThrownBy(() -> rabbit.send(exchangeName, "", MESSAGE))
                        .extracting(AMQP.Channel.Close.class::cast)
                        .extracting(AMQP.Channel.Close::getReplyCode)
                        .isEqualTo(ACCESS_REFUSED);
            }

            static Stream<Arguments> isForbiddenArgumentsProvider() {
                return Stream.of(
                        arguments("producer", "rabbitmq", "commands"),
                        arguments("producer", "rabbitmq", "commands.dlx"),
                        arguments("producer", "rabbitmq", "events.consumer-a.dlx"),
                        arguments("producer", "rabbitmq", "events.consumer-b.dlx"),

                        arguments("consumer-a", "rabbitmq", "commands.dlx"),
                        arguments("consumer-a", "rabbitmq", "events"),
                        arguments("consumer-a", "rabbitmq", "events.consumer-a.dlx"),
                        arguments("consumer-a", "rabbitmq", "events.consumer-b.dlx"),

                        arguments("consumer-b", "rabbitmq", "commands.dlx"),
                        arguments("consumer-b", "rabbitmq", "events"),
                        arguments("consumer-b", "rabbitmq", "events.consumer-a.dlx"),
                        arguments("consumer-b", "rabbitmq", "events.consumer-b.dlx"));
            }
        }
    }

    RabbitOperations newRabbitOperations(String username, String password) {
        var template = new RabbitTemplate(newConnectionFactory(username, password));
        template.setChannelTransacted(true);

        return template;
    }

    CachingConnectionFactory newConnectionFactory(String username, String password) {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(properties.getHost());
        connectionFactory.setPort(properties.getPort());
        connectionFactory.setVirtualHost(properties.getVhost());
        connectionFactory.setUsername(username);
        connectionFactory.setPassword(password);

        return new CachingConnectionFactory(connectionFactory);
    }

    AbstractObjectAssert<?, Method> assertThatExceptionIsThrownBy(ThrowableAssert.ThrowingCallable throwingCallable) {
        return assertThatExceptionOfType(AmqpIOException.class)
                .isThrownBy(throwingCallable)
                .havingRootCause()
                .asInstanceOf(type(ShutdownSignalException.class))
                .extracting(ShutdownSignalException::getReason);
    }
}
