package top.ellan.ecobridge.network;

// 🔥 Jackson 3.x 核心导入
import tools.jackson.core.JacksonException; 
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

// Jedis 和其他工具类
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
 * 分布式同步管理器 (Redis Manager v0.10.0 - Jackson 3.x Fully Migrated)
 * 职责：实现高频、跨服的价格演算状态同步。
 */
public class RedisManager {

    private static RedisManager instance;
    private final EcoBridge plugin;
    
    // Jackson 3.x 中 ObjectMapper 是不可变的
    private final ObjectMapper mapper;

    private PooledConnectionProvider provider;
    private volatile JedisPubSub subscriber;

    private final boolean enabled;
    private final String serverId;
    private final String tradeChannel;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final LinkedBlockingDeque<TradePacket> offlineQueue = new LinkedBlockingDeque<>(5000);
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);

    private RedisManager(EcoBridge plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();

        this.enabled = config.getBoolean("redis.enabled", false);
        this.serverId = config.getString("redis.server-id", "unknown_server");
        this.tradeChannel = config.getString("redis.channels.trade", "ecobridge:global_trade");

        // 🔥 Jackson 3.x 推荐使用 JsonMapper Builder
        this.mapper = JsonMapper.builder()
                // 性能优化：禁用尾随令牌检查（Jackson 3 默认开启，但在高频同步中建议关闭以对齐 2.x 性能）
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();

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

        clientConfigBuilder.ssl(config.getBoolean("redis.ssl", false));
        clientConfigBuilder.timeoutMillis(5000); 
        clientConfigBuilder.socketTimeoutMillis(5000); 

        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(4);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setJmxEnabled(false);

        this.provider = new PooledConnectionProvider(address, clientConfigBuilder.build(), poolConfig);
        this.active.set(true);

        LogUtil.info("<green>Redis 通道已打开 (Jackson 3.x 驱动)。ID: " + serverId);
        startSubscriberLoop();
    }

    private void startSubscriberLoop() {
        // 使用 JDK 21+ 虚拟线程处理订阅，防止阻塞主线程
        Thread.ofVirtual().name("EcoBridge-Redis-Sub").start(() -> {
            int retryCount = 0;
            while (active.get() && plugin.isEnabled()) {
                try (Connection connection = provider.getConnection();
                     Jedis jedis = new Jedis(connection)) {

                    retryCount = 0;
                    flushOfflineQueueAsync(); 

                    this.subscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (channel.equals(tradeChannel)) handleTradePacket(message);
                        }
                    };

                    LogUtil.debug("已启动 Redis 全球贸易监听...");
                    jedis.subscribe(subscriber, tradeChannel);

                } catch (Exception e) {
                    if (active.get() && plugin.isEnabled()) {
                        retryCount++;
                        long sleepTime = Math.min(retryCount * 2000L, 20000L);
                        LogUtil.warn("Redis 通信链路中断，将在 " + (sleepTime/1000) + "s 后尝试重连...");
                        try { Thread.sleep(sleepTime); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        });
    }

    public void publishTrade(String productId, double amount) {
        if (!enabled || !active.get()) return;

        TradePacket packet = new TradePacket(serverId, productId, amount, System.currentTimeMillis());
        offerToQueue(packet);
        flushOfflineQueueAsync();
    }

    private void offerToQueue(TradePacket packet) {
        if (!offlineQueue.offer(packet)) {
            offlineQueue.poll();
            offlineQueue.offer(packet);
            if (LogUtil.isDebugEnabled()) LogUtil.debug("Redis 发送缓冲区溢出，已滑动覆盖。");
        }
    }

    private void flushOfflineQueueAsync() {
        if (isFlushing.compareAndSet(false, true)) {
            plugin.getVirtualExecutor().execute(this::flushLoop);
        }
    }

    private void flushLoop() {
        final int BATCH_SIZE = 100;
        final long MAX_FLUSH_TIME_MS = 5000;

        try {
            long startTime = System.currentTimeMillis();
            
            try (Connection connection = provider.getConnection();
                 Jedis jedis = new Jedis(connection)) {
                
                int processed = 0;
                while (!offlineQueue.isEmpty() && active.get()) {
                    TradePacket packet = offlineQueue.peek();
                    if (packet == null) break;

                    try {
                        // ✅ Jackson 3.x 序列化
                        String json = mapper.writeValueAsString(packet);
                        jedis.publish(tradeChannel, json);
                        offlineQueue.poll(); 
                    } catch (JacksonException e) {
                        LogUtil.error("Redis 序列化坏包，已丢弃: " + e.getMessage(), e);
                        offlineQueue.poll(); 
                    }
                    
                    processed++;
                    if (processed >= BATCH_SIZE || 
                       (System.currentTimeMillis() - startTime) > MAX_FLUSH_TIME_MS) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.warn("Redis 批量冲刷中止: " + e.getMessage());
        } finally {
            isFlushing.set(false);
            if (!offlineQueue.isEmpty() && active.get()) {
                flushOfflineQueueAsync();
            }
        }
    }

    private void handleTradePacket(String json) {
        try {
            if (json == null || json.isBlank()) return;
            
            // ✅ Jackson 3.x 反序列化 (JacksonException 是运行时异常)
            TradePacket packet = mapper.readValue(json, TradePacket.class);
            
            if (packet == null || serverId.equals(packet.sourceServer)) return;

            if (PricingManager.getInstance() != null) {
                PricingManager.getInstance().onRemoteTradeReceived(
                    packet.productId, packet.amount, packet.timestamp
                );
            }
        } catch (JacksonException e) {
            LogUtil.warn("收到格式错误的贸易包: " + e.getMessage());
        } catch (Exception e) {
            LogUtil.warn("处理跨服贸易包失败: " + e.getMessage());
        }
    }

    public void shutdown() {
        active.set(false);

        if (!offlineQueue.isEmpty() && provider != null) {
            try (Connection connection = provider.getConnection();
                 Jedis jedis = new Jedis(connection)) {
                LogUtil.info("正在执行 Redis 临终同步，剩余包: " + offlineQueue.size());
                while (!offlineQueue.isEmpty()) {
                    TradePacket p = offlineQueue.poll();
                    if (p != null) {
                        try {
                            jedis.publish(tradeChannel, mapper.writeValueAsString(p));
                        } catch (JacksonException ignored) {} 
                    }
                }
            } catch (Exception ignored) {}
        }

        if (subscriber != null) try { subscriber.unsubscribe(); } catch (Exception ignored) {}
        if (provider != null) provider.close();
    }

    /**
     * 贸易数据包 - 使用 Java Record (JDK 17+)
     * Jackson 3.0 对 Record 的原生支持非常出色，无需额外注解。
     */
    private record TradePacket(
        String sourceServer,
        String productId,
        double amount,
        long timestamp
    ) {}
}
