/*
 * Copyright (c) 1994, 2026, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 价格预言机 (PriceOracle v0.7.5)
 * 职责：为 Rust 物理内核提取静态锚点价格 p0。
 * 技术栈：Java 25 Pattern Matching + BigDecimal High-Precision Arithmetic.
 * 修复：解决多货币累加时的浮点数精度丢失问题。
 */
public final class PriceOracle {

    // 物理演算安全阈值：防止 p0 <= 0 导致 Tanh/Log 公式崩溃
    private static final double MIN_SAFE_P0 = 0.01;

    private PriceOracle() {}

    /**
     * 获取物品的原始物理基准价 (p0)
     * @param item  商店物品对象
     * @param isBuy 是否为买入行为
     */
    public static double getOriginalBasePrice(@NotNull ObjectItem item, boolean isBuy) {
        // 1. 深度检索原始 YAML 配置 (SSoT - Single Source of Truth)
        String primaryPath = isBuy ? "buy-prices" : "sell-prices";
        String secondaryPath = "prices";

        ConfigurationSection config = item.getItemConfig();
        if (config != null) {
            Optional<Double> yamlPrice = tryExtractFromPaths(config, primaryPath, secondaryPath);
            if (yamlPrice.isPresent()) {
                return Math.max(MIN_SAFE_P0, yamlPrice.get());
            }
        }

        // 2. 降级方案：从 API 运行时对象提取 (已升级为高精度累加)
        return fetchStaticPriceFromApi(item, isBuy);
    }

    private static Optional<Double> tryExtractFromPaths(ConfigurationSection root, String... paths) {
        for (String path : paths) {
            Object section = root.get(path);
            if (section != null) {
                var result = deepSearchAmount(section);
                if (result.isPresent()) return result;
            }
        }
        return Optional.empty();
    }

    /**
     * 深度递归搜索金额 (Java 25 模式匹配实现)
     * 逻辑：通过 switch 表达式直接解构异构数据源
     */
    private static Optional<Double> deepSearchAmount(Object obj) {
        return switch (obj) {
            // 模式 A: 匹配包含显式 amount 键的配置段
        case ConfigurationSection sec when sec.contains("amount") ->
                Optional.of(sec.getDouble("amount"));

                // 模式 B: 匹配通用配置段，继续向下递归搜索
            case ConfigurationSection sec -> sec.getKeys(false).stream()
                    .map(sec::get)
                    .map(PriceOracle::deepSearchAmount)
                    .flatMap(Optional::stream)
                    .findFirst();

                    // 模式 C: 匹配 Map 结构中直接包含 amount 的情况
                case Map<?, ?> map when map.get("amount") instanceof Number n ->
                        Optional.of(n.doubleValue());

                        // 模式 D: 遍历 Map 的值继续递归搜索
                    case Map<?, ?> map -> map.values().stream()
                            .map(PriceOracle::deepSearchAmount)
                            .flatMap(Optional::stream)
                            .findFirst();

                            // 模式 E: 匹配最终数值终端
                        case Number n -> Optional.of(n.doubleValue());

                            case null, default -> Optional.empty();
                                };
                            }

                            /**
                             * [关键修复]: API 降级提取逻辑 (BigDecimal 高精度版)
                             * 解决了 Issue #6 中 double 直接相加导致的精度漂移问题
                             */
                            private static double fetchStaticPriceFromApi(ObjectItem item, boolean isBuy) {
                                try {
                                    ObjectPrices prices = isBuy ? item.getBuyPrice() : item.getSellPrice();
                                    if (prices == null || prices.empty) return MIN_SAFE_P0;

                                    // 调用驱动获取不带加成的裸价 (Player=null, amount=1)
                                    // 返回值 Map<?, BigDecimal> 中的 Value 已经是 BigDecimal，直接利用
                                    Map<?, BigDecimal> resultMap = prices.getAmount(null, 0, 1);
                                    if (resultMap == null || resultMap.isEmpty()) return MIN_SAFE_P0;

                                    boolean isAnyMode = prices.getMode().name().contains("ANY");

                                    // 使用 BigDecimal 进行流式计算
                                    BigDecimal calculatedPrice = isAnyMode ?
                                    // 1. 如果是 ANY 模式（任选一种货币），取第一个正值
                                    resultMap.values().stream()
                                    .filter(val -> val.compareTo(BigDecimal.ZERO) > 0)
                                    .findFirst()
                                    .orElse(BigDecimal.ZERO)
                                    :
                                    // 2. 如果是 ALL 模式（组合支付），执行高精度累加
                                    resultMap.values().stream()
                                    .filter(val -> val.compareTo(BigDecimal.ZERO) > 0)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                                    // 最后安全转换为 double 并兜底
                                    return Math.max(MIN_SAFE_P0, calculatedPrice.doubleValue());

                                } catch (Exception e) {
                                    logOracleWarning(item, "API 提取异常 (可能存在变量依赖): " + e.getMessage());
                                    return MIN_SAFE_P0;
                                }
                            }

                            private static void logOracleWarning(ObjectItem item, String reason) {
                                // 使用采样日志，防止在高频交易时刷屏
                                LogUtil.logTransactionSampled(
                                "<yellow>[预言机]</yellow> <gray>物品 <white><id></white> 基准价提取降级。原因: <white><reason></white>",
                                Placeholder.unparsed("id", item.getProduct()),
                                Placeholder.unparsed("reason", reason)
                            );
                            }
                        }
