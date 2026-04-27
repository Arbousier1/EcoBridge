# EcoBridge

Minecraft Paper 服务器经济系统插件。接管 UltimateShop 的定价与风控流程，提供工业级宏观经济调控、实时交易审计和 AI 协同决策能力。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  Paper/Bukkit Platform                                  │
│  ┌──────────┐  ┌──────────┐  ┌────────────────────┐   │
│  │ Commands │  │ Listeners│  │ PlaceholderAPI      │   │
│  └────┬─────┘  └────┬─────┘  └─────────┬──────────┘   │
│       └──────────────┼─────────────────┘               │
│              ┌───────┴────────┐                         │
│              │   API Facade   │                         │
│              └───────┬────────┘                         │
│  ┌───────────────────┼─────────────────────────────┐   │
│  │            Application Layer                     │   │
│  │  ┌─────────┐ ┌──────────┐ ┌──────────────────┐  │   │
│  │  │ Pricing │ │ Transfer │ │ MacroEngine       │  │   │
│  │  │ Manager │ │ Manager  │ │ (Fuzzy+PID+MPC+AI)│  │   │
│  │  └────┬────┘ └────┬─────┘ └────────┬─────────┘  │   │
│  └───────┼───────────┼───────────────┼─────────────┘   │
│          │           │               │                  │
│  ┌───────┴───────────┴───────────────┴─────────────┐   │
│  │          Infrastructure / FFI Bridge             │   │
│  │  ┌──────────┐ ┌────────┐ ┌──────────────────┐   │   │
│  │  │ MariaDB  │ │ Redis  │ │ Panama FFM (J25)  │   │   │
│  │  └──────────┘ └────────┘ └────────┬─────────┘   │   │
│  └───────────────────────────────────┼──────────────┘   │
│                                      │ C ABI             │
│  ┌───────────────────────────────────┼──────────────┐   │
│  │                        Rust Native Core          │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │   │
│  │  │ Pricing  │ │Security  │ │ Macro Control     │ │   │
│  │  │ GARCH    │ │Regulator │ │ PID / MPC / ARIMA │ │   │
│  │  │ Kalman   │ │Tax Engine│ │ Fuzzy Fluid       │ │   │
│  │  └──────────┘ └──────────┘ └──────────────────┘ │   │
│  │  ┌──────────────────────────────────────────────┐ │   │
│  │  │ DuckDB (embedded) + SIMD N_eff summation     │ │   │
│  │  └──────────────────────────────────────────────┘ │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## 核心算法

### 定价引擎 (`pricing.rs`)
- **行为定价**：P = P_base × ε × exp(-λ_adj × N_eff)，含卖出不对称性 (λ_sell = 0.6λ)
- **tanh 软限幅**：防止极端供应量导致价格归零
- **三级阶梯定价**：大批量卖出自动折扣 (100%/85%/60%)
- **均值回归恢复 (v1.7)**：价格低于历史均价 85% 时，累积积分项向上拉回
- **GARCH 动态底价**：波动率高时自动抬高底价保护

### 风险控制 (`regulator.rs`)
- 平方根模型动态交易限额 (veteran 玩家限额更高)
- 傀儡行为检测：低活跃度 + 高交易频率 = 拦截
- 奢侈税 (大额交易) + 贫富差距税 (穷→富转账)
- 税收上限 80%

### 宏观调控
| 控制器 | 文件 | 简介 |
|--------|------|------|
| Fuzzy Fluid | `PredictiveFuzzyFluidController.java` | 模糊推理 sink/faucet 调节 |
| PID | `control.rs` | 增益调度 + 抗积分饱和 + 恐慌阻尼 |
| MPC | `mpc.rs` | 滚动时域约束优化 (替代 PID) |
| Kalman | `kalman.rs` | 3 状态恒加速度滤波，降噪 M1 预测 |
| ARIMA | `forecast.rs` | ARIMA(p,d,0) 时序预测，12-48h 前瞻 |
| GARCH | `volatility.rs` | GARCH(1,1) 波动率聚类 + 动态底价 |

### AI 协同决策 (`ai-co-pilot`)
- 支持 Claude API / OpenAI API / 兼容端点 (Ollama, vLLM)
- 算法先算安全基线，AI 给建议，按权重混合
- AI 超时或异常时自动退回纯算法，不间断服务

## 环境变量 (密钥安全)

| 变量 | 说明 |
|------|------|
| `ECOBRIDGE_DB_PASSWORD` | MariaDB 密码 (优先于 config.yml) |
| `ECOBRIDGE_REDIS_PASSWORD` | Redis 密码 |
| `ANTHROPIC_API_KEY` | AI Co-Pilot Claude API 密钥 |
| `OPENAI_API_KEY` | AI Co-Pilot OpenAI API 密钥 |

## 快速开始

### 依赖

- JDK 25 (Project Panama FFM)
- jextract 22+ (生成 FFM 绑定)
- Rust 1.80+ (MSVC / GNU / Apple)
- MariaDB 10.11+
- Redis 7+ (可选，跨服同步)
- Paper 1.21.1+

### 构建

```bash
# 1. 编译 Rust 核心
cd ecobridge-rust
cargo build --release

# 2. 生成 C 头文件 (自动，由 build.rs 调用 cbindgen)
# 输出: ecobridge-rust/ecobridge_rust.h

# 3. 配置 jextract
export JEXTRACT_HOME=/path/to/jextract-22

# 4. 编译 Java 插件
cd ../ecobridge-java
./gradlew clean shadowJar

# 输出: ecobridge-java/build/libs/EcoBridge-{version}.jar
```

### 部署

```bash
# 将 JAR 放入 Paper 服务器的 plugins 目录
cp ecobridge-java/build/libs/EcoBridge-*.jar /path/to/server/plugins/

# 将对应平台的 Rust .so/.dll/.dylib 放入插件数据目录
# 或打包在 JAR 的 resources 中 (默认行为)

# 启动服务器，首次运行会在 plugins/EcoBridge/ 生成 config.yml
```

### 配置数据库

```sql
CREATE DATABASE ecobridge CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

设置环境变量或修改 `config.yml` 中的数据库密码。

## 配置要点

```yaml
# 核心经济参数
economy:
  tau: 10.0                # N_eff 衰减天数 (越小价格恢复越快)
  default-lambda: 0.0016   # 基础价格弹性
  sell-ratio: 0.58         # 卖出占比 (影响非对称灵敏度)

  recovery:                # 均值回归恢复 (v1.7)
    enabled: true
    activation-ratio-to-history: 0.82   # 触发线
    target-ratio-to-history: 0.95       # 回归目标
    strength: 0.34                      # 拉力强度

  audit-settings:          # 交易审计
    base-tax-rate: 0.05
    luxury-threshold: 100000.0
    velocity-threshold: 1000.0

# AI 协同 (可选)
ai-co-pilot:
  enabled: false
  provider: "claude"       # claude | openai
  weight: 0.30             # 0=纯算法, 1=纯AI
```

## 目录结构

```
ecobridge/
├── ecobridge-java/               # Java 插件 (Paper)
│   └── src/main/java/top/ellan/ecobridge/
│       ├── api/                  # 公共 API
│       ├── application/
│       │   ├── bootstrap/        # 生命周期引导
│       │   ├── control/          # 宏观控制 (Fuzzy, MPC, AI)
│       │   ├── lifecycle/        # 生命周期编排
│       │   └── service/          # 业务服务
│       ├── domain/algorithm/     # 领域算法
│       ├── infrastructure/
│       │   ├── ffi/bridge/       # Panama FFM 桥接
│       │   ├── persistence/      # 数据库/Redis
│       │   └── i18n/             # 国际化
│       ├── integration/platform/ # Paper 平台集成
│       └── util/                 # 工具类
├── ecobridge-rust/               # Rust 核心 (cdylib)
│   └── src/
│       ├── lib.rs                # FFI 导出
│       ├── models.rs             # C ABI 数据模型
│       ├── storage.rs            # DuckDB 持久化
│       ├── economy/
│       │   ├── pricing.rs        # 定价引擎
│       │   ├── control.rs        # PID 控制
│       │   ├── mpc.rs            # 模型预测控制
│       │   ├── kalman.rs         # 卡尔曼滤波
│       │   ├── forecast.rs       # ARIMA 预测
│       │   ├── volatility.rs     # GARCH 波动率
│       │   ├── environment.rs    # 环境因子
│       │   ├── macro_eco.rs      # 宏观指标
│       │   └── summation.rs      # SIMD 体积累加
│       └── security/
│           └── regulator.rs      # 交易审计
├── sim_long_run.py               # 365 天经济模拟器
├── sim_chart.py                  # 可视化图表生成
└── .github/workflows/            # CI/CD
```

## 测试

```bash
# Rust 单元测试 (70+ tests)
cd ecobridge-rust && cargo test

# Java 单元测试
cd ecobridge-java && ./gradlew test

# 365 天经济模拟
python sim_long_run.py
python sim_chart.py        # 生成图表
```

## CI/CD

推送 `main` 分支自动触发：
- **Rust**: Linux/Windows/macOS 三平台编译 + `cargo test`
- **Java**: jextract 生成 FFM 绑定 + Gradle 构建 + 完整测试套件
- **集成测试** (`test.yml`): 启动 Paper 服务器，加载 EcoBridge，验证启动无异常 (手动触发)
