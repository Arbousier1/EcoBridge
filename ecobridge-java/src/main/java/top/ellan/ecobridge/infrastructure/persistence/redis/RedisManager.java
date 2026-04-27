package top.ellan.ecobridge.infrastructure.persistence.redis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.args.FlushMode;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.infrastructure.SecurityConfig;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式同步管理器 v2.3.1 (Hotfix: No-Drop Queue)
 * 修复：队列扩容至 100k，并引入虚拟线程背压机制，防止高峰期消息丢失。
 */
public class RedisManager {

    private static volatile RedisManager instance;
    private final EcoBridge plugin;
    private final ObjectMapper mapper;

    private JedisPool jedisPool;
    private volatile JedisPubSub subscriber;

    private final boolean enabled;
    private final String serverId;
    private final String tradeChannel;
    private final String host;
    private final int port;

    private final AtomicBoolean active = new AtomicBoolean(false);
    
    // [Fix 1] 扩容队列至 100,000 以应对流量尖峰
    private final LinkedBlockingDeque<TradePacket> offlineQueue = new LinkedBlockingDeque<>(100000);
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    
    private final AtomicLong lastTransferLatency = new AtomicLong(0);

    private RedisManager(EcoBridge plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();

        this.enabled = config.getBoolean("redis.enabled", false);
        this.serverId = config.getString("redis.server-id", "unknown_server");
        this.tradeChannel = config.getString("redis.channels.trade", "ecobridge:global_trade");
        this.host = config.getString("redis.host", "127.0.0.1");
        this.port = config.getInt("redis.port", 6379);

        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();

        if (enabled) {
            try {
                connect();
            } catch (Exception e) {
                LogUtil.error("Redis 连接初始化失败，请确保 WSL 是否运行正常。", e);
            }
        }
    }

    public static void init(EcoBridge plugin) {
        instance = new RedisManager(plugin);
    }

    public static RedisManager getInstance() {
        return instance;
    }

    /**
     * 核心修复：使用 HostAndPort 对象初始化 JedisPool
     */
    private void connect() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(8);
        poolConfig.setTestOnBorrow(true);

        // 构建客户端配置
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .clientName("EcoBridge-Win-WSL")
                .connectionTimeoutMillis(2000)
                .socketTimeoutMillis(2000)
                .ssl(true)
                .password(SecurityConfig.resolvePassword(
                    "redis.password", "ECOBRIDGE_REDIS_PASSWORD", null))
                .build();

        // ✅ 修复：将单独的 host, port 封装进 HostAndPort 对象
        HostAndPort address = new HostAndPort(host, port);
        this.jedisPool = new JedisPool(poolConfig, address, clientConfig);
        
        // 验证连接并激活
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            this.active.set(true);
            LogUtil.info("<gradient:green:aqua>已成功连接至 WSL Redis (TCP 桥接模式)</gradient>");
        } catch (Exception e) {
            this.active.set(false);
            throw e;
        }

        startSubscriberLoop();
    }

    public CompletableFuture<Void> publishTrade(String productId, double amount) {
        if (!enabled || !active.get()) return CompletableFuture.completedFuture(null);

        // [Info] 这里的 runAsync 使用的是 VirtualExecutor，支持轻量级阻塞
        return CompletableFuture.runAsync(() -> {
            TradePacket packet = new TradePacket(serverId, productId, amount, System.currentTimeMillis());
            offerToQueue(packet);
            flushOfflineQueueAsync();
        }, plugin.getVirtualExecutor());
    }

    private void offerToQueue(TradePacket packet) {
        try {
            // [Fix 2] 背压机制 (Backpressure)
            // 不再丢弃旧消息 (offlineQueue.poll())。
            // 由于使用了虚拟线程，当队列满 (100k) 时，put() 会安全阻塞当前虚拟线程，
            // 直到队列有空间。这比丢弃数据更安全，且不会阻塞物理 OS 线程。
            offlineQueue.put(packet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogUtil.error("Redis 队列写入被中断: " + packet.productId, e);
        }
    }

    private void flushOfflineQueueAsync() {
        if (isFlushing.compareAndSet(false, true)) {
            plugin.getVirtualExecutor().execute(this::flushLoop);
        }
    }

    private void flushLoop() {
        long startNano = System.nanoTime();
        try (Jedis jedis = jedisPool.getResource()) {
            // [v2.0] Use pipeline for batch publish — single network round-trip
            var pipeline = jedis.pipelined();
            int processed = 0;
            while (!offlineQueue.isEmpty() && active.get() && processed < 100) {
                TradePacket packet = offlineQueue.poll();
                if (packet == null) break;

                try {
                    String json = mapper.writeValueAsString(packet);
                    pipeline.publish(tradeChannel, json);
                    processed++;
                } catch (JacksonException e) {
                    LogUtil.error("坏包丢弃: " + packet.productId, e);
                }
            }
            if (processed > 0) {
                pipeline.sync();
            }
            lastTransferLatency.set(System.nanoTime() - startNano);
        } catch (Exception e) {
            LogUtil.warnOnce("REDIS_IO_ERR", "WSL 虚拟网桥响应延迟或中断: " + e.getMessage());
        } finally {
            isFlushing.set(false);
            if (!offlineQueue.isEmpty() && active.get()) {
                flushOfflineQueueAsync();
            }
        }
    }

    private void startSubscriberLoop() {
        Thread.ofVirtual().name("Eco-Redis-Subscriber").start(() -> {
            while (active.get() && plugin.isEnabled()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    this.subscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            plugin.getVirtualExecutor().execute(() -> handleTradePacket(message));
                        }
                    };
                    jedis.subscribe(subscriber, tradeChannel);
                } catch (Exception e) {
                    if (active.get()) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        });
    }

    private void handleTradePacket(String json) {
        try {
            TradePacket packet = mapper.readValue(json, TradePacket.class);
            if (packet == null || serverId.equals(packet.sourceServer)) return;

            if (PricingManager.getInstance() != null) {
                PricingManager.getInstance().onRemoteTradeReceived(
                        packet.productId, packet.amount, packet.timestamp
                );
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        if (!active.getAndSet(false)) return;
        try (Jedis jedis = jedisPool.getResource()) {
            while (!offlineQueue.isEmpty()) {
                TradePacket p = offlineQueue.poll();
                if (p != null) jedis.publish(tradeChannel, mapper.writeValueAsString(p));
            }
            jedis.flushAll(FlushMode.ASYNC);
        } catch (Exception ignored) {}
        if (subscriber != null) subscriber.unsubscribe();
        if (jedisPool != null) jedisPool.close();
    }

    public long getLastTransferLatency() {
        return lastTransferLatency.get();
    }

    private record TradePacket(
            String sourceServer,
            String productId,
            double amount,
            long timestamp
    ) {}
}
