package top.ellan.ecobridge.network;

// Jackson 3.x 导入（注意：除了 jackson-annotations，包名已全部从 com.fasterxml.jackson 改为 tools.jackson）
import tools.jackson.core.JacksonException; // JsonProcessingException 在 3.x 中已重命名为 JacksonException
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
// Jedis 和其他工具类导入保持不变
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
 * 分布式同步管理器 (Redis Manager v0.10.0 - Jackson 3.x Migrated)
 * 职责：实现高频、跨服的价格演算状态同步。
 * <p>
 * 优化与变更日志:
 * 1. [Migration] 从 Jackson 2.x 迁移至 Jackson 3.x (JDK 17+基线，包名变更，API不可变)。
 * 2. [Perf] 继续使用 Jackson (databind) 进行高性能序列化。
 */
public class RedisManager {

    private static RedisManager instance;
    private final EcoBridge plugin;
    
    // Jackson 3.x: ObjectMapper 不可变，必须通过 Builder 构建。
    // JsonMapper 是 JSON 格式的专用 Mapper，推荐使用。
    private final ObjectMapper mapper;
    // 注意：在 Jackson 3.x 中，建议使用具体的 JsonMapper/YamlMapper 等替代通用的 ObjectMapper。
    // 但为了最小化代码变更，此处声明为 ObjectMapper 仍可工作。

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

        // Jackson 3.x: ObjectMapper 必须通过 Builder 模式构建和配置。
        this.mapper = JsonMapper.builder()
                // 你可以在此处添加 Jackson 2.x 中通过 setter 方法进行的配置。
                // 例如，禁用尾随令牌检查以匹配 Jackson 2.x 默认行为并提升性能：
                // .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
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

        LogUtil.info("<green>Redis 通道已打开 (Jackson 3.x Core)。ID: " + serverId);
        startSubscriberLoop();
    }

    private void startSubscriberLoop() {
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
        // [Resource Guard] 批处理与时间片限制
        final int BATCH_SIZE = 100;
        final long MAX_FLUSH_TIME_MS = 5000;

        try {
            long startTime = System.currentTimeMillis();
            
            try (redis.clients.jedis.Connection connection = provider.getConnection();
                 redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis(connection)) {
                
                int processed = 0;
                
                while (!offlineQueue.isEmpty() && active.get()) {
                    TradePacket packet = offlineQueue.peek();
                    if (packet == null) break;

                    try {
                        // ✅ [Jackson 3.x] 序列化适配
                        // mapper 已通过 Builder 模式在构造函数中初始化
                        String json = mapper.writeValueAsString(packet);
                        jedis.publish(tradeChannel, json);
                        
                        // 发送成功才移除
                        offlineQueue.poll(); 
                    } catch (JacksonException e) { // 捕获 JacksonException 而非 JsonProcessingException
                        // [Fault Tolerance] 遇到序列化坏包，必须丢弃，否则会卡死队列头部
                        LogUtil.error("Redis 序列化严重错误，丢弃坏包: " + e.getMessage(), e);
                        offlineQueue.poll(); 
                    }
                    
                    // [Resource Guard] 检查配额
                    processed++;
                    if (processed >= BATCH_SIZE || 
                       (System.currentTimeMillis() - startTime) > MAX_FLUSH_TIME_MS) {
                        break; // 主动释放连接
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.warn("Redis 批量冲刷中止: " + e.getMessage());
        } finally {
            isFlushing.set(false);
            // [Safety Fix] CAS 安全调度
            if (!offlineQueue.isEmpty() && active.get()) {
                flushOfflineQueueAsync();
            }
        }
    }

    private void handleTradePacket(String json) {
        try {
            if (json == null || json.isBlank()) return;
            
            // ✅ [Jackson 3.x] 反序列化
            // JacksonException 是 RuntimeException，此处可以捕获更通用的 Exception 或 JacksonException。
            TradePacket packet = mapper.readValue(json, TradePacket.class);
            
            if (packet == null || serverId.equals(packet.sourceServer)) return;

            if (PricingManager.getInstance() != null) {
                PricingManager.getInstance().onRemoteTradeReceived(
                    packet.productId, packet.amount, packet.timestamp
                );
            }
        } catch (JacksonException e) { // 捕获 JacksonException 而非 JsonProcessingException
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
                        } catch (JacksonException ignored) {} // 忽略序列化异常
                    }
                }
            } catch (Exception ignored) {}
        }

        if (subscriber != null) try { subscriber.unsubscribe(); } catch (Exception ignored) {}
        if (provider != null) provider.close();
    }

    // Jackson 3.x 对 Java Record 的支持良好（JDK 17+ 基线）。
    private record TradePacket(
        String sourceServer,
        String productId,
        double amount,
        long timestamp
    ) {}
}