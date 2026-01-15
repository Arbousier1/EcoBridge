package top.ellan.ecobridge.network;

import com.google.gson.Gson;
import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.providers.PooledConnectionProvider;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分布式同步管理器 (Redis Manager v3.4 - Reliable Messaging)
 * 职责：实现跨服物理演算状态同步。
 * <p>
 * 修复记录 (v3.4):
 * 1. [Buffer] 增加 offlineQueue 本地缓冲，解决断线期间消息丢失问题。
 * 2. [Replay] 实现断线重连后的消息自动重放机制。
 * 3. [Ordering] 保证消息发送的 FIFO 顺序。
 */
public class RedisManager {

    private static RedisManager instance;
    private final EcoBridge plugin;
    private final Gson gson = new Gson();
    
    // [Jedis 7.x] 核心连接提供者
    private PooledConnectionProvider provider;
    private volatile JedisPubSub subscriber;
    
    // 配置参数
    private final boolean enabled;
    private final String serverId;
    private final String tradeChannel;
    
    // 状态控制
    private final AtomicBoolean active = new AtomicBoolean(false);

    // [新增] 离线消息缓冲队列 (容量 5000，防止内存溢出)
    private final LinkedBlockingDeque<TradePacket> offlineQueue = new LinkedBlockingDeque<>(5000);
    // [新增] 冲刷状态锁，防止并发重放
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);

    private RedisManager(EcoBridge plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();
        
        this.enabled = config.getBoolean("redis.enabled", false);
        this.serverId = config.getString("redis.server-id", "unknown_server");
        this.tradeChannel = config.getString("redis.channels.trade", "ecobridge:global_trade");

        if (enabled) {
            try {
                connect();
            } catch (Exception e) {
                LogUtil.error("Redis 初始化失败，跨服同步功能已禁用。", e);
            }
        }
    }

    public static void init(EcoBridge plugin) {
        instance = new RedisManager(plugin);
    }

    public static RedisManager getInstance() {
        return instance;
    }

    private void connect() {
        var config = plugin.getConfig();
        
        String host = config.getString("redis.host", "127.0.0.1");
        int port = config.getInt("redis.port", 6379);
        
        HostAndPort address = new HostAndPort(host, port);
        
        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder();
        
        String user = config.getString("redis.user", "");
        String password = config.getString("redis.password", "");
        
        if (user != null && !user.isBlank()) clientConfigBuilder.user(user);
        if (password != null && !password.isBlank()) clientConfigBuilder.password(password);

        boolean ssl = config.getBoolean("redis.ssl", false);
        clientConfigBuilder.ssl(ssl);
        
        clientConfigBuilder.timeoutMillis(2000);
        clientConfigBuilder.socketTimeoutMillis(0);
        
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setJmxEnabled(false);

        this.provider = new PooledConnectionProvider(address, clientConfigBuilder.build(), poolConfig);
        this.active.set(true);
        
        LogUtil.info("<green>Redis 连接池已建立 (PooledConnectionProvider Mode) [SSL: " + ssl + "]");

        startSubscriberLoop();
    }

    /**
     * 启动订阅循环
     */
    private void startSubscriberLoop() {
        Thread.ofVirtual().name("EcoBridge-Redis-Sub").start(() -> {
            int retryCount = 0;
            
            while (active.get() && plugin.isEnabled()) {
                try (Connection connection = provider.getConnection();
                     Jedis jedis = new Jedis(connection)) {
                    
                    retryCount = 0;
                    
                    // [关键修复] 连接成功建立后，立即触发一次离线消息重放
                    flushOfflineQueueAsync();
                    
                    this.subscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (channel.equals(tradeChannel)) {
                                handleTradePacket(message);
                            }
                        }
                    };
                    
                    LogUtil.debug("正在订阅频道: " + tradeChannel);
                    jedis.subscribe(subscriber, tradeChannel);
                    
                } catch (Exception e) {
                    if (subscriber != null) {
                        try { subscriber.unsubscribe(); } catch (Throwable ignored) {}
                    }

                    if (active.get() && plugin.isEnabled()) {
                        if (e instanceof InterruptedException) {
                            break;
                        }

                        retryCount++;
                        long sleepTime = Math.min(retryCount * 3000L, 30000L);
                        LogUtil.warn("Redis 订阅断开 (" + e.getClass().getSimpleName() + ")，" + (sleepTime/1000) + "秒后重试...");
                        
                        try { 
                            Thread.sleep(sleepTime); 
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break; 
                        }
                    }
                }
            }
        });
    }

    /**
     * [对外 API] 广播本地交易事件
     * 具备缓冲和重试机制
     */
    public void publishTrade(String productId, double amount) {
        if (!enabled || !active.get()) return;

        TradePacket packet = new TradePacket(serverId, productId, amount, System.currentTimeMillis());

        // 如果队列里已经有积压消息，为了保证顺序，直接加入队列，不插队发送
        if (!offlineQueue.isEmpty()) {
            offerToQueue(packet);
            flushOfflineQueueAsync(); // 尝试触发冲刷
            return;
        }

        plugin.getVirtualExecutor().execute(() -> {
            try (Connection connection = provider.getConnection();
                 Jedis jedis = new Jedis(connection)) {
                
                String json = gson.toJson(packet);
                jedis.publish(tradeChannel, json);
                
            } catch (Exception e) {
                LogUtil.warn("Redis 发布失败，已转入本地缓冲 [" + productId + "]: " + e.getMessage());
                offerToQueue(packet);
                // 此时不立即 flush，因为大概率网络有问题，等待重连或下一次触发
            }
        });
    }

    /**
     * 安全地添加消息到队列，处理满队列情况
     */
    private void offerToQueue(TradePacket packet) {
        if (!offlineQueue.offer(packet)) {
            // 队列已满，丢弃最旧的消息以腾出空间 (Ring Buffer 策略)
            offlineQueue.poll();
            offlineQueue.offer(packet);
            if (LogUtil.isDebugEnabled()) {
                LogUtil.debug("Redis 离线队列已满，丢弃最旧消息。");
            }
        }
    }

    /**
     * 异步冲刷离线队列
     */
    private void flushOfflineQueueAsync() {
        // CAS 锁，防止多个线程同时执行冲刷
        if (isFlushing.compareAndSet(false, true)) {
            plugin.getVirtualExecutor().execute(this::flushLoop);
        }
    }

    /**
     * 冲刷循环逻辑
     */
    private void flushLoop() {
        try {
            while (!offlineQueue.isEmpty() && active.get()) {
                // Peek 查看队头，但不取出（确保发送成功才移除）
                TradePacket packet = offlineQueue.peek();
                if (packet == null) break;

                try (Connection connection = provider.getConnection();
                     Jedis jedis = new Jedis(connection)) {
                    
                    String json = gson.toJson(packet);
                    jedis.publish(tradeChannel, json);
                    
                    // 发送成功，移除队头
                    offlineQueue.poll();
                    
                    if (LogUtil.isDebugEnabled()) {
                        LogUtil.debug("已重放离线消息: " + packet.productId);
                    }

                } catch (Exception e) {
                    // 发送失败，停止本次冲刷，等待网络恢复
                    // 消息保留在队头，下次重试
                    LogUtil.debug("重放失败，停止冲刷: " + e.getMessage());
                    break;
                }
            }
        } finally {
            isFlushing.set(false);
            
            // 双重检查：如果在冲刷结束瞬间又有新消息入队，再次尝试触发
            if (!offlineQueue.isEmpty()) {
                flushOfflineQueueAsync();
            }
        }
    }

    private void handleTradePacket(String json) {
        try {
            if (json == null || json.isBlank()) return;

            TradePacket packet = gson.fromJson(json, TradePacket.class);
            if (packet == null || packet.sourceServer == null) return;
            if (this.serverId.equals(packet.sourceServer)) return;

            if (PricingManager.getInstance() != null) {
                PricingManager.getInstance().onRemoteTradeReceived(
                    packet.productId, 
                    packet.amount, 
                    packet.timestamp
                );
            }

            if (LogUtil.isDebugEnabled()) {
                LogUtil.debug("同步远程交易: " + packet.productId + " x" + packet.amount + " from " + packet.sourceServer);
            }
        } catch (Exception e) {
            LogUtil.warn("Redis 消息解析异常: " + e.getMessage());
        }
    }

    public void shutdown() {
        active.set(false);
        
        if (subscriber != null && subscriber.isSubscribed()) {
            try {
                subscriber.unsubscribe();
            } catch (Exception ignored) {}
        }
        
        if (provider != null) {
            provider.close();
            LogUtil.info("Redis 连接池已释放。");
        }
    }

    private record TradePacket(
        String sourceServer, 
        String productId, 
        double amount, 
        long timestamp
    ) {}
}