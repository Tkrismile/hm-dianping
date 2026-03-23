package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@EnableAspectJAutoProxy(exposeProxy = true)
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 线程池
    private static  final  ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程任务
    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 s1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            // GROUP g1 s1
                            Consumer.from("g1", "s1"),
                            // COUNT 1 BLOCK 2000
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
                            // STREAMS streams.order >
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息
                    MapRecord<String, Object, Object> record = list.get(0); //String 是消息id
                    Map<Object, Object> values = record.getValue();
                    // VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, VoucherOrder.class, true);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3. 如果有消息，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4，ACK确认 ASCK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    handlePendingList();
                    log.error("处理订单异常", e);
                }
            }

        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 s1 COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            // GROUP g1 s1
                            Consumer.from("g1", "s1"),
                            // COUNT 1
                            StreamReadOptions.empty().count(1),
                            // STREAMS streams.order >
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 如果获取失败，说明pending-list没有消息，结束下一次循环
                        break;
                    }
                    // 3.解析消息
                    MapRecord<String, Object, Object> record = list.get(0); //String 是消息id
                    Map<Object, Object> values = record.getValue();
                    // VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, VoucherOrder.class, true);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3. 如果有消息，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4，ACK确认 ASCK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }

        }

//    // 创建阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    // @PostConstruct 当前类初始化之后执行
//    @PostConstruct
//    private void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//
//    // 线程任务
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1.获取队列中的订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//
//        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 获取用户
            Long userId = voucherOrder.getId();
            // 创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            // 获取锁
            boolean isLock = lock.tryLock();
            // 判断是否获取锁成功
            if (!isLock){
                // 失败
                log.error("不允许重复下单");
                return ;
            }
            try {
                // 一人一单
                proxy.createVoucherOrder(voucherOrder);
            }finally {
                lock.unlock();
            }
            }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        // int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count());
        if (count > 0){
            log.error("用户已经购买过一次！");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                // 失败率过大
                // .eq("voucher_id", voucherId).eq("stock", voucher.getStock()) // 乐观锁 where id = ? and stock = ?
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // 乐观锁 where id = ? and stock >0
                .update();
        if (!success){
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }


    // 提前加载脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT= new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillStream.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private IVoucherOrderService proxy;


    @Override
    public Result setKillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), // key,没有key所以传入空集合
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        // 2。判断结果是为0
        int r = result.intValue();
        // 2.1 不为0 代表没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足!" : "不能重复下单");
        }
        // 2.2 为0.有购买资格，把下单信息保存到阻塞队列
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

//    @Override
//    public Result setKillVoucher(Long voucherId) {
//
//        // 获取用户id
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(), // key,没有key所以传入空集合
//                voucherId.toString(), userId.toString()
//        );
//        // 2。判断结果是为0
//        int r = result.intValue();
//        // 2.1 不为0 代表没有购买资格
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2 为0.有购买资格，把下单信息保存到阻塞队列
//        // 6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        // 订单id
//        voucherOrder.setId(orderId);
//        // 用户id
//        voucherOrder.setUserId(userId);
//        // 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 放入阻塞队列
//        orderTasks.add(voucherOrder);
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        return Result.ok(orderId);
//    }



//    @Override
//    public Result setKillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 判断是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("未开始");
//        }
//        // 判断是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("已结束");
//        }
//        // 判断库存是否充足
//        if (voucher.getStock() < 1 ){
//            return Result.fail("库存不足");
//
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // inter()返回字符串对象的规范表示。保证唯一性
//        // 锁函数的好处是保证先提交事务（数据变更）再释放锁
//        // 同时相比于把锁放在方法public 后面的好处是为每个用户定义一把锁，提高效率；以前的是所有用户公用一把锁；
////        synchronized (userId.toString().intern()) {
////            // 原始方法
////            // return createVoucherOrder(voucherId);
////            // 会存在事务失效
////            // 事务的原理是对当前的类做了动态代理，拿到了类的代理对象，用代理对象做事务
////            // 但原始方法调用的是本身的方法，因此代理会失效
////
////            // 解决方法
////            // 拿到当前事务的代理对象，执行代理对象中的方法
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        // RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        //boolean isLock = lock.tryLock(1200);
//        boolean isLock = lock.tryLock(1200);
//        // 判断是否获取锁成功
//        if (!isLock){
//            // 失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            // 一人一单
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }
//
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
//        // int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());
//        if (count > 0){
//            return Result.fail("用户已经购买过一次！");
//        }
//        // 5.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                // 失败率过大
//                // .eq("voucher_id", voucherId).eq("stock", voucher.getStock()) // 乐观锁 where id = ? and stock = ?
//                .eq("voucher_id", voucherId).gt("stock", 0) // 乐观锁 where id = ? and stock >0
//                .update();
//        if (!success){
//            return Result.fail("库存不足");
//        }
//
//        // 6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        // 订单id
//        voucherOrder.setId(orderId);
//        // 用户id
//        voucherOrder.setUserId(userId);
//        // 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        // 返回订单id
//        return Result.ok(orderId);
//
//
//    }
}
