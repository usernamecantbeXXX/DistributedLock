package com.example.demo.lock;

import com.example.demo.utils.RedisUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//import javax.transaction.Transaction;

/**
 * 乐观锁实现秒杀系统
 */
public class OptimisticLockTest {

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
            jedis.del(key);//如果存在旧商品数，则删除
        }
        if (jedis.exists(clientList)) {
            jedis.del(clientList);//如果存在旧的客户列表则删除
        }
        jedis.set(key, String.valueOf(prdNum));//初始化商品()
//        RedisUtil.returnResource(jedis);//这个是不是要返回资源给redis池？？？

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 顾客抢购商品（秒杀操作）
     *
     * @date 2017-10-17
     */
    public static void initClient() {
        ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
        int clientNum = 10000;//模拟顾客数目
        for (int i = 0; i < clientNum; i++) {//一万个客户来抢100个商品，1w条线程，
            cachedThreadPool.execute(new ClientThread(i));//启动与顾客数目相等的消费者线程
        }
        cachedThreadPool.shutdown();//关闭线程池
        while (true) {
            if (cachedThreadPool.isTerminated()) {
                System.out.println("所有的消费者线程均结束了");
                break;
            }
            try {
                Thread.sleep(100);
            } catch (JedisConnectionException je) {
                System.out.println("java.net.ConnectException: Connection refused: connect");
            } catch (Exception e) {
                // TODO: handle exception
            }
        }//关闭线程池，然后就让线程池去跑线程，不断的0.1s直到线程池结束
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
//        RedisUtil.returnResource(jedis);
    }


    /**
     * 内部类：模拟消费者线程
     */
    static class ClientThread implements Runnable {

        Jedis jedis = null;
        String key = "prdNum";//商品数key
        String clientList = "clientList";//抢购到商品的顾客集合主键
        String clientName;//第一个客户，第二个客户。。。

        public ClientThread(int num) {
            clientName = "编号=" + num;
        }

//      1.multi，开启Redis的事务，置客户端为事务态。
//      2.exec，提交事务，执行从multi到此命令前的命令队列，置客户端为非事务态。
//      3.discard，取消事务，置客户端为非事务态。
//      4.watch,监视键值对，作用是如果事务提交exec时发现监视的键值对发生变化，事务将被取消。
        @Override
        public void run() {
            try {
                Thread.sleep((int) Math.random() * 5000);//随机睡眠一下0.5——1s
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            while (true) {
                jedis = RedisUtil.getInstance().getJedis();
                System.out.println("顾客：" + clientName + "开始抢购商品");
                try {

                    //<p> 这个watch的是商品数目吧</p>
                    jedis.watch(key);//监视商品键值对，作用时如果事务提交exec时发现监视的键值对发生变化，事务将被取消 key="prdNum"
                    int prdNum = Integer.parseInt(jedis.get(key));//当前商品个数
                    if (prdNum > 0) {
                        Transaction transaction = jedis.multi();//开启redis事务
                        transaction.set(key, String.valueOf(prdNum - 1));//商品数量减一
                        List<Object> result = ((redis.clients.jedis.Transaction) transaction).exec();//提交事务(乐观锁：提交事务的时候才会去检查key有没有被修改)
                        if (result == null || result.isEmpty()) {
                            System.out.println("很抱歉，顾客:" + clientName + "没有抢到商品");// 可能是watch-key被外部修改，或者是数据操作被驳回
                        } else {
                            jedis.sadd(clientList, clientName);//抢到商品的话记录一下
                            System.out.println("恭喜，顾客:" + clientName + "抢到商品");
                            break;
                        }
                    } else {
                        System.out.println("很抱歉，库存为0，顾客:" + clientName + "没有抢到商品");
                        break;
                    }
                } catch (JedisConnectionException je) {
                    System.out.println("java.net.ConnectException: Connection refused: connect");
                } catch (Exception e) {
                    // TODO: handle exception
                    System.out.println("dasfadsfas");
                } finally {
                    jedis.unwatch();
//                    RedisUtil.returnResource(jedis);
//                    jedis.close();
                }
            }

        }
    }

}