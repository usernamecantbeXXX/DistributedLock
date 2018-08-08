package com.example.demo.lock;

import com.example.demo.utils.RedisUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 悲观锁
 */
public class PessimisticLockTest {

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        initProduct();
        initClient();
        printResult();
        long endTime = System.currentTimeMillis();
        long time = endTime - startTime;
        System.out.println("程序运行时间 ： " + (int) time + "ms");
    }

    /**
     * 初始化商品
     *
     * @date 2017-10-17
     */
    public static void initProduct() {
        int prdNum = 100;//商品个数
        String key = "prdNum";
        String clientList = "clientList";//抢购到商品的顾客列表
        Jedis jedis = RedisUtil.getInstance().getJedis();
        if (jedis.exists(key)) {
            jedis.del(key);
        }
        if (jedis.exists(clientList)) {
            jedis.del(clientList);
        }
        jedis.set(key, String.valueOf(prdNum));//初始化商品
        RedisUtil.returnResource(jedis);
    }

    /**
     * 顾客抢购商品（秒杀操作）
     *
     * @date 2017-10-17
     */
    public static void initClient() {
        ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
        int clientNum = 10000;//模拟顾客数目
        for (int i = 0; i < clientNum; i++) {
            cachedThreadPool.execute(new PessClientThread(i));//启动与顾客数目相等的消费者线程
        }
        cachedThreadPool.shutdown();//关闭线程池
        while (true) {
            if (cachedThreadPool.isTerminated()) {
                System.out.println("所有的消费者线程均结束了");
                break;
            }
            try {
                Thread.sleep(100);
            } catch (JedisConnectionException je){
                System.out.println("java.net.ConnectException: Connection refused: connect");
            } catch (Exception e) {
                // TODO: handle exception
            }
        }
    }

    /**
     * 打印抢购结果
     *
     * @date 2017-10-17
     */
    public static void printResult() {
        Jedis jedis = RedisUtil.getInstance().getJedis();
        Set<String> set = jedis.smembers("clientList");
        int i = 1;
        for (String value : set) {
            System.out.println("第" + i++ + "个抢到商品，" + value + " ");
        }
        RedisUtil.returnResource(jedis);
    }

    /**
     * 消费者线程
     */
    static class PessClientThread implements Runnable {

        String key = "prdNum";//商品主键
        String clientList = "clientList";//抢购到商品的顾客列表
        String clientName;
        Jedis jedis = null;
        RedisBasedDistributedLock redisBasedDistributedLock;

        public PessClientThread(int num) {
            clientName = "编号=" + num;
            init();
        }

        public void init() {
            jedis = RedisUtil.getInstance().getJedis();
            redisBasedDistributedLock = new RedisBasedDistributedLock(jedis, "lock.lock", 5 * 1000);
        }

        @Override
        public void run() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            while (true) {
                if (Integer.parseInt(jedis.get(key)) <= 0) {
                    break;//缓存中没有商品，跳出循环，消费者线程执行完毕
                }
                //缓存中还有商品，取锁，商品数目减一
                System.out.println("顾客:" + clientName + "开始抢商品");
                if (redisBasedDistributedLock.tryLock(3, TimeUnit.SECONDS)) {//等待3秒获取锁，否则返回false(悲观锁：每次拿数据都上锁)
                    int prdNum = Integer.parseInt(jedis.get(key));//再次取得商品缓存数目
                    if (prdNum > 0) {
                        jedis.decr(key);//商品数减一
                        jedis.sadd(clientList, clientName);//将抢购到商品的顾客记录一下
                        System.out.println("恭喜，顾客:" + clientName + "抢到商品");
                    } else {
                        System.out.println("抱歉，库存为0，顾客:" + clientName + "没有抢到商品");
                    }
                    redisBasedDistributedLock.unlock0();//操作完成释放锁
                    break;
                }
            }
            //释放资源
            redisBasedDistributedLock = null;
//            RedisUtil.returnResource(jedis);
            jedis.close();
        }
    }
}