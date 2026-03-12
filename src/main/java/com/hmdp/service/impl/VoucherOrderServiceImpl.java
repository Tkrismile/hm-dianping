package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result setKillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("未开始");
        }
        // 判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("已结束");
        }
        // 判断库存是否充足
        if (voucher.getStock() < 1 ){
            return Result.fail("库存不足");

        }

        Long userId = UserHolder.getUser().getId();
        // inter()返回字符串对象的规范表示。保证唯一性
        // 锁函数的好处是保证先提交事务（数据变更）再释放锁
        // 同时相比于把锁放在方法public 后面的好处是为每个用户定义一把锁，提高效率；以前的是所有用户公用一把锁；
        synchronized (userId.toString().intern()) {
            // 原始方法
            // return createVoucherOrder(voucherId);
            // 会存在事务失效
            // 事务的原理是对当前的类做了动态代理，拿到了类的代理对象，用代理对象做事务
            // 但原始方法调用的是本身的方法，因此代理会失效

            // 解决方法
            // 拿到当前事务的代理对象，执行代理对象中的方法
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("用户已经购买过一次！");
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                // 失败率过大
                // .eq("voucher_id", voucherId).eq("stock", voucher.getStock()) // 乐观锁 where id = ? and stock = ?
                .eq("voucher_id", voucherId).gt("stock", 0) // 乐观锁 where id = ? and stock >0
                .update();
        if (!success){
            return Result.fail("库存不足");
        }

        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        // 订单id
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);


    }
}
