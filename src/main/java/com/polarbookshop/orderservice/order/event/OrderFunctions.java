package com.polarbookshop.orderservice.order.event;

import com.polarbookshop.orderservice.order.domain.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

@Configuration
public class OrderFunctions {

    private static final Logger log = LoggerFactory.getLogger(OrderFunctions.class);

    @Bean
    public Consumer<Flux<OrderDispatchedMessage>> dispatchOrder(OrderService orderService) {
        return flux
                -> orderService.consumeOrderDispatchedEvent(flux) // 对于派发的每条消息，它都会更新数据库中相关订单的状态
                .doOnNext(order -> log.info("The order with id {} is dispatched", order.id()))
                .subscribe(); // 订阅反应式流以激活它，如果没有订阅者，流中不会产生数据流
    }
}
