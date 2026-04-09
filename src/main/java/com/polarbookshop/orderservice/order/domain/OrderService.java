package com.polarbookshop.orderservice.order.domain;

import com.polarbookshop.orderservice.book.Book;
import com.polarbookshop.orderservice.book.BookClient;
import com.polarbookshop.orderservice.order.event.OrderAcceptedMessage;
import com.polarbookshop.orderservice.order.event.OrderDispatchedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final BookClient bookClient;
    private final OrderRepository orderRepository;
    private final StreamBridge streamBridge;

    public OrderService(BookClient bookClient,
                        StreamBridge streamBridge,
                        OrderRepository orderRepository) {
        this.bookClient = bookClient;
        this.orderRepository = orderRepository;
        this.streamBridge = streamBridge;
    }

    public Flux<Order> getAllOrders(String userId) {
        return orderRepository.findAllByCreatedBy(userId);
    }

    @Transactional
    public Mono<Order> submitOrder(String isbn, int quantity) {
        return bookClient.getBookByIsbn(isbn)
                .map(book -> buildAcceptedOrder(book, quantity))
                .defaultIfEmpty(buildRejectedOrder(isbn, quantity))
                .flatMap(orderRepository::save) // 保存订单到数据库中
                .doOnNext(this::publishOrderAcceptedEvent); // 如果订单被接受，发布订单已接受事件
    }

    private void publishOrderAcceptedEvent(Order order) {
        // 如果订单没有被接受，不执行任何操作
        if (!order.status().equals(OrderStatus.ACCEPTED)) {
            return;
        }
        // 构建一条消息以通知该订单已被接受
        var orderAcceptedMessage = new OrderAcceptedMessage(order.id());
        log.info("Sending order accepted event with id: {}", order.id());
        // 将消息显示发送到绑定的消息通道中，"acceptOrder-out-0"是消息通道的名称，orderAcceptedMessage是要发送的消息对象
        var result = streamBridge.send("acceptOrder-out-0", orderAcceptedMessage);
        log.info("Result of sending data for order with id {}: {}", order.id(), result);
    }

    public static Order buildAcceptedOrder(Book book, int quantity) {
        return Order.of(book.isbn(), book.title() + " - " + book.author(),
                book.price(), quantity, OrderStatus.ACCEPTED);
    }

    public static Order buildRejectedOrder(String bookIsbn, int quantity) {
        return Order.of(bookIsbn, null, null, quantity, OrderStatus.REJECTED);
    }

    public Flux<Order> consumeOrderDispatchedEvent(Flux<OrderDispatchedMessage> flux) {
        return flux // 接受OrderDispatchedMessage对象组成的反应式流作为输入
                .flatMap(message ->
                        orderRepository.findById(message.orderId())) // 对于发布到流中的每个对象，从数据库中读取相关的订单
                .filter(order -> !order.status().equals(OrderStatus.DISPATCHED)) // 过滤掉状态已经是DISPATCHED的订单
                .map(this::buildDispatchedOrder) // 将订单更新为“dispatched”状态
                .flatMap(orderRepository::save); // 将更新后的订单保存到数据库中
    }

    private Order buildDispatchedOrder(Order existingOrder) {
        return new Order(
                existingOrder.id(),
                existingOrder.bookIsbn(),
                existingOrder.bookName(),
                existingOrder.bookPrice(),
                existingOrder.quantity(),
                OrderStatus.DISPATCHED,
                existingOrder.createdDate(),
                existingOrder.lastModifiedDate(),
                existingOrder.createdBy(),
                existingOrder.lastModifiedBy(),
                existingOrder.version()
        );
    }

}
