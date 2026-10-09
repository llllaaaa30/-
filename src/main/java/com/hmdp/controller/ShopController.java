package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.BloomFilterUtil;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Resource
    private BloomFilterUtil bloomFilterUtil;

    /**
     * 根据id查询商铺信息
     * <p>
     * 布隆过滤器防缓存穿透流程：
     * 1. 将 shopId 通过多个 hash 函数映射到 bitmap 的多个 bit 位
     * 2. 任意一个 bit 为 0 → 一定不存在，直接返回"店铺不存在"
     * 3. 所有 bit 都为 1 → 可能存在，继续走原有缓存+DB查询逻辑
     * （假阳性误判由 ShopServiceImpl 中的缓存空值机制兜底）
     * </p>
     *
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        // ====== 布隆过滤器过滤（防缓存穿透） ======
        // 执行多个 hash 计算 bit 下标，检查 Redis Bitmap 对应位是否都为 1
        // 原理：布隆过滤器判定"一定不存在"是100%准确的（无假阴性）
        if (!bloomFilterUtil.mightContain(id.toString())) {
            // 布隆判定不存在 → 直接返回，不查 Redis 缓存，不查 MySQL
            return Result.fail("店铺不存在");
        }
        // 布隆判定可能存在 → 继续执行原有的缓存+数据库查询逻辑
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * <p>
     * 写入布隆过滤器时机：MySQL 插入成功后，将新 shopId 写入布隆（对应 bit 位置1）。
     * 确保后续通过该ID查询时，布隆不会误判为不存在。
     * </p>
     *
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // MySQL 插入成功后，将新店铺ID写入布隆过滤器
        // 附：MyBatis-Plus 在 save() 后会自动回填主键到 shop.getId()
        if (shop.getId() != null) {
            bloomFilterUtil.add(shop.getId().toString());
        }
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}