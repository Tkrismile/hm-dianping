package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.apache.ibatis.annotations.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        // 1.从redis中查询
        List<String> shop = stringRedisTemplate.opsForList().range(CACHE_SHOP_KEY, 0, -1);
        // 2.存在返回
        if (CollectionUtil.isNotEmpty(shop)) {
            if (StrUtil.isBlank(shop.get(0))) {
                return Result.fail("商品信息为空");
            }
            List<ShopType> typeList = new ArrayList<>();

            for (String jsonString : shop) {
                ShopType shopType = JSONUtil.toBean(jsonString, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        // 3.不能存在从mysql中取并存入redis
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (CollectionUtils.isEmpty(typeList)) {
            // 添加空对象到redis，解决缓存穿透
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_KEY, CollectionUtil.newArrayList(""));
            stringRedisTemplate.expire(CACHE_SHOP_KEY,CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return Result.fail("商品分类信息为空！");
        }
        // 3.2 数据库中存在,转换List<ShopType> -> List<String> 类型
        List<String> shopTypeList = new ArrayList<>();
        for (ShopType shopType : typeList) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(jsonStr);
        }
        //写入
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_KEY, shopTypeList);

        // 4.返回
        return Result.ok(typeList);
    }
}