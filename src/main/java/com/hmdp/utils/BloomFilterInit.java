package com.hmdp.utils;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * <h2>布隆过滤器初始化器</h2>
 * <p>
 * 在 Spring Boot 项目启动完成后执行，一次性扫描 shop 表中所有有效店铺ID，
 * 将其批量写入 Redis 布隆过滤器（bitmap 对应位置1）。
 * </p>
 * <p>
 * 实现 {@link CommandLineRunner} 接口，在所有 Bean 初始化完成后、项目完全启动前执行。
 * </p>
 *
 * <h3>写入策略说明</h3>
 * <ul>
 *   <li>全量加载：查询 shop 表所有记录的 id 字段，逐条 add 到布隆</li>
 *   <li>如果 shop 表数据量巨大（百万级），可考虑分页分批写入，避免一次性加载过多数据到内存</li>
 *   <li>启动时 Redis 不可用会导致写入失败，但不会阻塞应用启动，布隆为空时所有请求走正常缓存逻辑</li>
 * </ul>
 *
 * <h3>店铺删除的取舍</h3>
 * <p>
 * 普通 Bitmap 布隆过滤器<b>不支持删除元素</b>。如果调用店铺删除接口删除了某条店铺记录，
 * 布隆中对应的 bit 位仍然为 1，会导致该店铺ID被判定为"可能存在"。
 * 但实际查询时，缓存和数据库都查不到，会写入 Redis 空值兜底，不会出现数据错误。
 * 仅会增加一次无效的缓存+DB查询，影响可控（误判率 1% 以内）。
 * </p>
 *
 * @author 基于黑马点评项目改造
 * @since 2026-10-01
 */
@Slf4j
@Component
public class BloomFilterInit implements CommandLineRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private BloomFilterUtil bloomFilterUtil;

    /**
     * 项目启动后自动执行，将数据库中所有有效店铺ID加载到布隆过滤器
     *
     * @param args 命令行参数
     */
    @Override
    public void run(String... args) {
        log.info("========== 开始初始化布隆过滤器，加载店铺ID到 bloom:shop ==========");
        try {
            // 查询 shop 表中所有记录的 id 列表
            // 注意：这里查全表，数据量很大时应分页处理
            List<Shop> shopList = shopService.list();
            if (shopList == null || shopList.isEmpty()) {
                log.warn("布隆过滤器初始化：shop 表为空，跳过加载");
                return;
            }

            int count = 0;
            for (Shop shop : shopList) {
                if (shop.getId() != null) {
                    // 将店铺ID写入布隆过滤器
                    bloomFilterUtil.add(shop.getId().toString());
                    count++;
                }
            }
            log.info("========== 布隆过滤器初始化完成，共加载 {} 个店铺ID ==========", count);
        } catch (Exception e) {
            // 启动时加载失败不应阻塞应用启动，记录错误日志方便排查
            log.error("布隆过滤器初始化失败，店铺查询仍可正常走缓存+DB逻辑（仅布隆过滤不生效）", e);
        }
    }
}