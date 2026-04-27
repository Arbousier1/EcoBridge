package top.ellan.ecobridge.application.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration; // [閺傛澘顤僝
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.File; // [閺傛澘顤僝
import java.io.IOException; // [閺傛澘顤僝
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 鐎瑰繗顫囩紒蹇旂ス缁狅紕鎮婇崳?(EconomyManager v1.6.1 - Data Separation)
 * <p>
 * 閺嶇绺炬导妯哄閿?
 * 1. [IO Safety] 鐏忓棗濮╅幀浣哄劰鎼达附鏆熼幑?economy-heat)閸掑棛顬囬懛?data.yml閿涘矂妲诲?config.yml 濞夈劑鍣存稉銏犮亼閵?
 * 2. [Precision] 閸忋劑鍣洪柌鍥╂暏 i64 Micros (10^-6) 鐎涙ê鍋嶉弽绋跨妇缂佸繑绁归幐鍥ㄧ垼閵?
 */
public class EconomyManager {

    private static volatile EconomyManager instance;
    private final EcoBridge plugin;

    // [閺傛澘顤僝 閻欘剛鐝涢弫鐗堝祦閺傚洣娆㈤敍宀€鏁ゆ禍搴＄摠閸屻劎鈻兼惔蹇氱箥鐞涘本妞傛禍褏鏁撻惃鍕З閹焦鏆熼幑?
    private final File dataFile;
    private YamlConfiguration dataConfig;

    // 鐎规氨鍋ｉ弫鎷屾祮閹广垹鐖堕柌?(1.0 = 1,000,000 Micros)
    private static final double PRECISION_SCALE = 1_000_000.0;

    // --- 閺嶇绺剧紒蹇旂ス閹稿洦鐖?---
    private volatile double inflationRate = 0.0;
    private volatile double marketHeat = 0.0;
    private volatile double ecoSaturation = 0.0;

    // --- 闁插洦鐗辨稉搴ｅЦ閹胶鐤崝鐘叉珤 ---
    private final LongAdder circulationHeat = new LongAdder();      // 闂€鎸庢埂缁鳖垳袧閻戭厼瀹?(Micros)
    private final LongAdder tradeVolumeAccumulator = new LongAdder(); // 閻厽婀℃禍銈嗘閼村鍟?(Micros)
    private final LongAdder faucetAccumulator = new LongAdder();      // 閸涖劍婀℃禍褍鍤?(Micros)
    private final LongAdder sinkAccumulator = new LongAdder();        // 閸涖劍婀￠崶鐐存暪 (Micros)
    private final AtomicLong m1MoneySupplyMicros = new AtomicLong(0); // 鐠愌冪閸欐垼顢戦幀濠氬櫤 (Micros)
    
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(System.currentTimeMillis());
    private long lastMacroUpdateTime = System.currentTimeMillis();

    // --- 缁犳纭堕柊宥囩枂閸欏倹鏆?---
    private double volatilityThreshold; // 濞夈垹濮╃憴锕€褰傞梼鍫濃偓?
    private double decayRate;           // 閻戭厼瀹抽懛顏嗗姧鐞涙澘鍣洪悳?
    private double capacityPerUser;     // 閸楁洜鏁ら幋閿嬪鏉炶棄濮?

    private final ScheduledExecutorService economicScheduler;
    private final ReentrantLock configLock = new ReentrantLock();

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        // [閺傛澘顤僝 閸掓繂顫愰崠?data.yml 閺傚洣娆㈤崣銉︾労
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");

        this.economicScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("EcoBridge-Economy-Worker").factory()
        );
        
        loadState();
        startEconomicTasks();
        startMacroAnalyticsTask();
    }

    public static void init(EcoBridge plugin) {
        instance = new EconomyManager(plugin);
    }

    public static EconomyManager getInstance() {
        return instance;
    }

    // =================================================================================
    // SECTION: 閺嶇绺炬稉姘闁槒绶?(API)
    // =================================================================================

    public void loadState() {
        var config = plugin.getConfig();
        
        // [閺傛澘顤僝 閸旂姾娴?data.yml閿涘苯顩ч弸婊勬瀮娴犳湹绗夌€涙ê婀崚娆忓灡瀵?
        if (!dataFile.exists()) {
            try {
                if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                LogUtil.error("閺冪姵纭堕崚娑樼紦 data.yml 閺佺増宓侀弬鍥︽", e);
            }
        }
        this.dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        // 1. 鐠囪褰囬棃娆愨偓渚€鍘ょ純?(娴?config.yml 鐠囪褰囬敍灞藉涧鐠?
        double m1Double = config.getDouble("economy.m1-supply", 10_000_000.0);
        this.m1MoneySupplyMicros.set((long) (m1Double * PRECISION_SCALE));
        
        this.volatilityThreshold = config.getDouble("economy.volatility-threshold", 50_000.0);
        this.decayRate = config.getDouble("economy.daily-decay-rate", 0.05);
        this.capacityPerUser = config.getDouble("economy.macro.capacity-per-user", 5000.0);

        // 2. 鐠囪褰囬崝銊︹偓浣哄劰鎼?(娴兼ê鍘?data.yml閿涘苯鍚嬬€?config.yml)
        // 闁槒绶敍姘洤閺?data.yml 闁插本鐥呴張澶庮唶瑜版洩绱欐＃鏍偧閺囧瓨鏌婇敍澶涚礉閸掓瑥鐨剧拠鏇☆嚢閸?config.yml 闁插瞼娈戦弮褎鏆熼幑顕嗙礉楠炶櫕绮︽潻浣盒?
        double savedHeat = dataConfig.getDouble("internal.economy-heat", 
            config.getDouble("internal.economy-heat", 0.0));
            
        circulationHeat.reset();
        circulationHeat.add((long) (savedHeat * PRECISION_SCALE));

        // 閸掓繂顫愰崠鏍х唨绾偓閹稿洦鐖?
        this.marketHeat = savedHeat / 100.0; 

        LogUtil.info("EconomyManager 閻樿埖鈧礁濮炴潪钘夌暚閹?(M1=" + m1Double + ", Heat=" + savedHeat + ")");
    }

    /**
     * 婢跺嫮鎮婄€圭偞妞傛禍銈嗘閼村鍟?
     * @param amount 娴溿倖妲楁０?(閸樼喎顫?double)
     * @param isMarketActivity 閺勵垰鎯佹稉鍝勭閸﹂缚顢戞稉?
     */
    public void onTransaction(double amount, boolean isMarketActivity) {
        // 1. 鏉烆剚宕叉稉?Micros
        long amountMicros = (long) (Math.abs(amount) * PRECISION_SCALE);
        
        if (!isMarketActivity) {
            // 瑜板崬鎼?M1 閹鍣?(婢跺嫮鎮婂锝堢閿涘瞼鏁ゆ禍搴ゆ彛鐢礁顤冮崣?閸ョ偞鏁?
            long change = (long) (amount * PRECISION_SCALE);
            m1MoneySupplyMicros.addAndGet(change);
            if (change > 0) {
                faucetAccumulator.add(change);
            } else if (change < 0) {
                sinkAccumulator.add(-change);
            }
            return;
        }

        // 2. 濞夈劌鍙嗛惌顓熸埂閼村鍟跨槐顖氬閸?
        tradeVolumeAccumulator.add(amountMicros);
        
        // 3. 濞夈劌鍙嗛梹鎸庢埂缁鳖垳袧閻戭厼瀹?
        circulationHeat.add(amountMicros);

        // 4. 濞夈垹濮╅惄鎴炵ゴ
        if (Math.abs(amount) >= volatilityThreshold) {
            lastVolatileTimestamp.set(System.currentTimeMillis());
        }
    }

    public void recordTradeVolume(double amount) {
        onTransaction(amount, true);
    }

    public void recordFaucet(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        onTransaction(Math.abs(amount), false);
    }

    public void recordSink(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) return;
        onTransaction(-Math.abs(amount), false);
    }

    public long pollFaucetMicros() {
        return faucetAccumulator.sumThenReset();
    }

    public long pollSinkMicros() {
        return sinkAccumulator.sumThenReset();
    }

    // =================================================================================
    // SECTION: 鐎瑰繗顫囬悽璇插剼濠曟梻鐣?(The Brain)
    // =================================================================================

    private void startMacroAnalyticsTask() {
        economicScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                double dt = (now - lastMacroUpdateTime) / 1000.0;
                if (dt < 0.1) return;

                // A. 鐠侊紕鐣荤拹銏犵槣濞翠線鈧?(Market Heat)
                long currentWindowMicros = tradeVolumeAccumulator.sumThenReset();
                double currentWindowDouble = currentWindowMicros / PRECISION_SCALE;
                this.marketHeat = currentWindowDouble / dt;

                // B. 鐠侊紕鐣婚悽鐔糕偓渚€銈遍崪灞藉
                int online = Bukkit.getOnlinePlayers().size();
                double totalCapacity = Math.max(1, online) * capacityPerUser;
                this.ecoSaturation = Math.min(1.0, marketHeat / totalCapacity);

                // C. 鐠侊紕鐣荤€圭偞妞傞柅姘冲剦閻?(FFI 鐠嬪啰鏁?
                if (NativeBridge.isLoaded()) {
                    double m1Double = m1MoneySupplyMicros.get() / PRECISION_SCALE;
                    this.inflationRate = NativeBridge.calcInflation(marketHeat, m1Double);
                }

                lastMacroUpdateTime = now;
            } catch (Throwable e) {
                LogUtil.warn("Economy task error: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void startEconomicTasks() {
        // 濮?30 閸掑棝鎸撻幍褑顢戞稉鈧▎锛勫劰鎼达箒鈥滈崙?
        economicScheduler.scheduleAtFixedRate(this::runEconomicDecay, 30, 30, TimeUnit.MINUTES);
    }

    private void runEconomicDecay() {
        if (!NativeBridge.isLoaded()) return;

        double currentTotal = circulationHeat.sum() / PRECISION_SCALE;
        double reductionDouble = NativeBridge.calcDecay(currentTotal, decayRate);

        if (Math.abs(reductionDouble) > 0.01) {
            long reductionMicros = (long) (reductionDouble * PRECISION_SCALE);
            circulationHeat.add(-reductionMicros);
            
            if (reductionDouble > 100.0) saveState();
        }
    }

    public void saveState() {
        double currentTotal = circulationHeat.sum() / PRECISION_SCALE;
        try {
            plugin.getVirtualExecutor().execute(() -> persistHeatState(currentTotal));
        } catch (RejectedExecutionException ignored) {
            // During shutdown, fallback to sync persist to avoid losing final state.
            persistHeatState(currentTotal);
        }
    }

    private void persistHeatState(double currentTotal) {
        configLock.lock();
        try {
            dataConfig.set("internal.economy-heat", currentTotal);
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                LogUtil.error("鏃犳硶淇濆瓨 data.yml 缁忔祹鏁版嵁", e);
            }
        } finally {
            configLock.unlock();
        }
    }

    // =================================================================================
    // SECTION: Getters
    // =================================================================================

    public double getMarketHeat() { return this.marketHeat; }
    public double getEcoSaturation() { return this.ecoSaturation; }
    public double getInflationRate() { return this.inflationRate; }
    
    // 閼惧嘲褰囪ぐ鎾冲閻ㄥ嫯鎻ｇ敮浣光偓濠氬櫤 (double 閻楀牊婀版笟娑樼潔缁€?
    public double getM1Supply() {
        return m1MoneySupplyMicros.get() / PRECISION_SCALE;
    }

    public double getMarketStability() {
        if (!NativeBridge.isLoaded()) return 1.0;
        return NativeBridge.calcStability(lastVolatileTimestamp.get(), System.currentTimeMillis());
    }

    public void shutdown() {
        economicScheduler.shutdown();
        try {
            if (!economicScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                economicScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            economicScheduler.shutdownNow();
        }
        // Shutdown path must persist synchronously before plugin executors are stopped.
        persistHeatState(circulationHeat.sum() / PRECISION_SCALE);
    }
}

