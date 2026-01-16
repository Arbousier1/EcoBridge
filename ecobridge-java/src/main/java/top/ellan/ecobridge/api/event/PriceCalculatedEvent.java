package top.ellan.ecobridge.api.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * 价格演算完成事件 (PriceCalculatedEvent v0.8.8 - Async Safe)
 * <p>
 * 触发时机：当 Rust 核心完成基于 Tanh 平滑定价和环境因子演算后，应用到商店前触发。
 * 职责：
 * 1. 允许外部插件（如 VIP 系统、折扣券）在物理定价基础上叠加业务逻辑。
 * 2. 维护价格修改的审计链，记录所有干预操作。
 * <p>
 * 修复日志 (v0.8.8):
 * - [Safety] 引入 synchronized 与 CopyOnWriteArrayList，确保非同步监听下的线程安全。
 */
public class PriceCalculatedEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final @Nullable Player player;   // 触发计算的玩家上下文
    private final String shopId;             // UltimateShop 商店 ID
    private final String productId;          // UltimateShop 商品 ID

    private final double rustBasePrice;      // Rust 物理引擎算出的原始单价 (不可变)
    private volatile double finalPrice;      // 经过干预后的最终应用单价 (可变)

    // 修改审计日志：记录所有干预过此价格的插件及来源
    private final List<String> modificationLog = new CopyOnWriteArrayList<>();

    /**
     * @param player    触发价格计算的玩家
     * @param shopId    商店 ID
     * @param productId 商品 ID
     * @param calculated Rust 内核算出的平滑价格
     */
    public PriceCalculatedEvent(@Nullable Player player, @NotNull String shopId, @NotNull String productId, double calculated) {
        super(false); 
        this.player = player;
        this.shopId = shopId;
        this.productId = productId;
        this.rustBasePrice = calculated;
        this.finalPrice = calculated;
        this.modificationLog.add("EcoKernel-v0.8.8");
    }

    // ==================== 核心业务 API ====================

    public @Nullable Player getPlayer() { return player; }

    @NotNull
    public String getShopId() { return shopId; }

    @NotNull
    public String getProductId() { return productId; }

    /**
     * 获取 Rust 内核算出的原始物理价格
     */
    public double getRustBasePrice() { return rustBasePrice; }

    /**
     * 获取经过所有插件干预后的最终成交价
     */
    public double getFinalPrice() { return finalPrice; }

    /**
     * 链式修改价格 (推荐用法)
     * @param source   修改来源名称（如 "VipSystem"、"XmasCoupon"）
     * @param modifier 价格处理函数 (例如: p -> p * 0.8)
     */
    public synchronized void modifyPrice(@NotNull String source, @NotNull UnaryOperator<Double> modifier) {
        double oldPrice = this.finalPrice;
        this.finalPrice = Math.max(0.01, modifier.apply(this.finalPrice));

        if (Double.compare(oldPrice, finalPrice) != 0) {
            this.modificationLog.add(source);
        }
    }

    /**
     * 直接覆盖最终价格
     * @param finalPrice 设定的目标价格
     * @param source     覆盖来源说明
     */
    public synchronized void setFinalPrice(double finalPrice, @NotNull String source) {
        this.finalPrice = Math.max(0.01, finalPrice);
        this.modificationLog.add(source + "(Overwrite)");
    }

    /**
     * 判断价格是否被第三方插件干预过
     */
    public boolean isModified() {
        return modificationLog.size() > 1;
    }

    /**
     * 获取审计追踪日志 (只读)
     */
    @NotNull
    public List<String> getModificationLog() {
        return Collections.unmodifiableList(new ArrayList<>(modificationLog));
    }

    // ==================== 视觉表现 (Adventure API) ====================

    /**
     * 生成美化的演算日志组件
     */
    @NotNull
    public Component toComponent() {
        // 线程安全地获取当前快照状态
        List<String> logSnapshot = new ArrayList<>(modificationLog);
        String lastSource = logSnapshot.get(logSnapshot.size() - 1);

        String template = isModified()
        ? "<gray>[EcoBridge] <white><product> <yellow><final> <dark_gray>(原:<base>, 改自:<source>)"
        : "<gray>[EcoBridge] <white><product> <green><final> <dark_gray>(物理定价)";

        return EcoBridge.getMiniMessage().deserialize(template,
        Placeholder.unparsed("product", productId),
        Placeholder.unparsed("final", String.format("%.2f", finalPrice)),
        Placeholder.unparsed("base", String.format("%.2f", rustBasePrice)),
        Placeholder.unparsed("source", lastSource)
    );
    }

    // ==================== Bukkit 样板代码 ====================

    @NotNull
    @Override
    public HandlerList getHandlers() { return handlers; }

    public static HandlerList getHandlerList() { return handlers; }
}
