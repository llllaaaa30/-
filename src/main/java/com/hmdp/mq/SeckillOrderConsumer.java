package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 秒杀订单RocketMQ消费者
 * 从RocketMQ消息队列中消费秒杀订单消息，完成异步下单
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "seckill-order-topic",
        consumerGroup = "seckill_consumer_group"
)
public class SeckillOrderConsumer implements RocketMQListener<VoucherOrder> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(VoucherOrder voucherOrder) {
        try {
            log.info("收到秒杀订单消息，orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            voucherOrderService.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理秒杀订单消息异常，orderId={}", voucherOrder.getId(), e);
        }
    }
}