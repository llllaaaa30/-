package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY + "list";
        // 1.从Redis中查询商铺类型缓存
        String typeListJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(typeListJson)) {
            // 3.如果存在，返回缓存数据
            List<ShopType> typeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 4.如果不存在，从数据库中查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 5.不存在，返回错误信息
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }
        // 6.将查询结果写入Redis缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        // 7.返回查询结果
        return Result.ok(typeList);
    }
}