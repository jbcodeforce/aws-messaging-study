package org.acme.orders.domain;

import org.acme.orders.infra.api.SimulControl;
import org.acme.orders.infra.msg.OrderMessageProducer;
import org.acme.orders.infra.repo.OrderRepository;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class OrderService {
    Logger logger = Logger.getLogger(OrderService.class.getName());
    
    @Inject
    OrderRepository orderRepository;
    @Inject
    OrderMessageProducer producer;
    
    @Transactional
    public Order processOrder(Order newOrder) {
        
        logger.info("processing new order: " + newOrder);
        orderRepository.addOrder(newOrder);
        producer.sendMessage(newOrder);
        return newOrder;
    }


    public void startOrderSimulation(SimulControl control) {
        if (control.delay >0 ) {
            producer.start(control.delay);
        } else if (control.totalMessageToSend > 0) {
            for (int i = 0; i < control.totalMessageToSend; i++) {
                Order o = Order.buildOrder("order_"+i);
                producer.sendMessage(o);
            }
        }
    }
    
}
