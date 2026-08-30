package dev.capstan.execute;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two work queues and one dead-letter exchange.
 *
 * <p>Quorum queues rather than classic mirrored ones: a debit instruction that
 * vanishes because a node restarted is not an acceptable failure mode.
 *
 * <p>Rejected messages go to the DLX, never back onto the queue --
 * {@code default-requeue-rejected: false} in Phase 01's {@code application.yml}
 * does that. A requeue loop against a gateway that is timing out would hammer
 * it with debits, which is the failure this phase exists to prevent.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "capstan.exchange";
    public static final String DLX = "capstan.dlx";
    public static final String DEBIT_QUEUE = "capstan.debit";
    public static final String COMMS_QUEUE = "capstan.comms";
    public static final String DEAD_QUEUE = "capstan.debit.dead";
    public static final String DEAD_KEY = "capstan.debit.dead";

    @Bean
    TopicExchange capstanExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    TopicExchange capstanDlx() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    Queue debitQueue() {
        return QueueBuilder.durable(DEBIT_QUEUE)
                .quorum()
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DEAD_KEY)
                .build();
    }

    /**
     * Comms share the debit queue's claim, re-read and cancellation semantics
     * but not its DLQ story, so they get their own queue feeding the same DLX.
     * A nudge stuck in the dead-letter queue should be visibly a nudge.
     */
    @Bean
    Queue commsQueue() {
        return QueueBuilder.durable(COMMS_QUEUE)
                .quorum()
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DEAD_KEY)
                .build();
    }

    @Bean
    Queue debitDlq() {
        return QueueBuilder.durable(DEAD_QUEUE).quorum().build();
    }

    @Bean
    Binding debitBinding() {
        return BindingBuilder.bind(debitQueue()).to(capstanExchange()).with(DEBIT_QUEUE);
    }

    @Bean
    Binding commsBinding() {
        return BindingBuilder.bind(commsQueue()).to(capstanExchange()).with(COMMS_QUEUE);
    }

    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(debitDlq()).to(capstanDlx()).with(DEAD_KEY);
    }
}
