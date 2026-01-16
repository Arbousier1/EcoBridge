// ==================================================
// FILE: ecobridge-java/src/main/java/top/ellan/ecobridge/api/UShopProvider.java
// ==================================================

package top.ellan.ecobridge.api;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.bridge.NativeBridge;
import top.ellan.ecobridge.manager.PricingManager;
import top.ellan.ecobridge.util.PriceOracle;

/**
 * UShop 适配器 - 最终定稿版 (Architecture: Zero-Latency Snapshot)
 * <p>
 * 架构原则:
 * 1. 主线程 0 I/O, 0 Native, 0 Allocation (Hot Path).
 * 2. 仅从 PricingManager 读取预计算好的原子快照。
 * 3. 禁止任何形式的实时计算或缓存逻辑。
 * 4. [Refactor] 物品解析逻辑已下沉至 PriceOracle。
 */
public final class UShopProvider {

    // [删除] 所有 Cache, VarHandle, Arena 定义全部移除，确保无 native 资源泄漏风险

    public static double calculateDynamicPrice(Player player, ObjectItem item, int amount) {
        // 1. 基础空指针防御
        if (player == null || item == null) return 0.0;

        // 2. 静态前置校验 (纯内存操作)
        // [Refactor] 统一委托给 PriceOracle 进行合法性判断，避免逻辑重复
        if (!PriceOracle.isValidEconomyItem(item)) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        // 3. 如果 Native 引擎未就绪，快速降级 (纯内存读取)
        if (!NativeBridge.isLoaded()) {
            return PriceOracle.getOriginalBasePrice(item, amount < 0);
        }

        // 4. [核心] 从原子快照读取单价 (无锁、无计算、无 I/O)
        // 复杂度: O(1) Map Lookup
        // 这里的 getSnapshotPrice 访问的是 AtomicReference 快照，绝不触发 Native 计算
        double unitPrice = PricingManager.getInstance().getSnapshotPrice(
            item.getShop(), 
            item.getProduct()
        );

        // 5. 兜底策略：如果快照中不存在(如新上架或未同步)，回退到配置基准价
        if (unitPrice <= 0) {
            double p0 = PriceOracle.getOriginalBasePrice(item, amount < 0);
            return p0 > 0 ? p0 : 0.0;
        }

        // 6. 简单乘法 (CPU 寄存器级操作)
        // 注意：阶梯定价(Tier Pricing)如需支持，应在 PricingManager 内部预计算好平均单价，
        // 或在此处仅做线性乘法以保证 O(1) 性能。
        return unitPrice * Math.abs(amount);
    }

    // [Refactor] 私有解析方法 isVaultEconomy/isVaultHook 已移除，
    // 逻辑已合并至 PriceOracle.isValidEconomyItem()
}