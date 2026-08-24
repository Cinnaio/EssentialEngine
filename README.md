# EssentialEngine

模块化的 Minecraft 服务器**基础功能插件**，提供 CMI / EssentialsX 风格的常用功能，
兼容 **Paper 与 Folia（1.21.4+）**。

> 本项目由 LinkEngine（原 mc-server-bridge）演进而来：原先的四个 Gradle 子项目
> （bridge-core / bridge-servercore / bridge-husktowns / bridge-plugin）已经合并为
> **单模块结构**，所有功能改为插件内部的「功能模块」，通过 `config.yml` 逐个开关。
> 原有的 REST API 与 HuskTowns 对接能力被完整保留，成为其中两个可选模块。

---

## 功能模块一览

| 模块 | ID | 主要命令 |
| --- | --- | --- |
| 传送 | `teleport` | `/home` `/sethome` `/delhome` `/homes` `/warp` `/setwarp` `/delwarp` `/warps` `/spawn` `/setspawn` `/tpa` `/tpahere` `/tpaccept` `/tpdeny` `/tpacancel` `/back` `/tp` `/tphere` `/rtp` |
| 玩家指令 | `player` | `/heal` `/feed` `/fly` `/god` `/speed` `/gamemode`（`/gms` `/gmc` `/gma` `/gmsp`） `/repair` `/hat` `/workbench` `/enderchest` `/top` `/suicide` `/near` `/ping` `/playtime` |
| 世界控制 | `world` | `/time`（`day` `night` `noon` `midnight` `sunrise` `sunset` `set` `add` `query`） `/weather`（`clear` `rain` `thunder`） |
| 聊天与消息 | `chat` | `/msg` `/reply` `/msgtoggle` `/socialspy` `/ignore` `/nick` `/broadcast` `/me` `/afk` `/mail` |
| 管理与惩罚 | `admin` | `/kick` `/ban` `/tempban` `/unban` `/mute` `/tempmute` `/unmute` `/vanish` `/invsee` `/clearinventory` `/seen` `/whois` |
| 经济与套装 | `economy` | `/balance` `/pay` `/eco` `/baltop` `/kit` |
| HuskTowns 对接 | `husktowns` | `/eetown`（未安装 HuskTowns 时自动跳过） |
| PlaceholderAPI 变量 | `papi` | 无命令，提供 `%ee_...%` 变量（未安装 PlaceholderAPI 时自动跳过） |
| REST API | `webapi` | 无命令，提供 HTTP 接口（默认关闭） |
| 网页管理面板 | `panel` | 无命令，浏览器里改配置 / 管玩家 / 查流水 / 看监控曲线（默认关闭） |
| 性能监控 | `monitor` | `/eemonitor`，定时采样 TPS / 内存，自动记录性能事件与重启关闭事件，为 AstrBot 预留 REST 接口 |
| 核心 | `core` | `/ee reload\|info\|modules\|save` |

关闭某个模块后，它的命令会真正从服务端命令表里移除，不会和其它插件抢命令名。
也可以在 `config.yml` 的 `commands.disabled` 里单独禁用某条命令、在 `commands.aliases` 里追加别名。

命令注册默认「谁先注册谁占用命令名」，被别的插件抢了名就得写全名 `/essentialengine:heal`。
想让本插件的命令强制压过同名命令，把它写进 `commands.override`（默认已含 `heal`、`god`）：
所有插件加载完成后本插件会把这些命令名夺回来，玩家直接输入 `/heal`、`/god` 就走本插件。

> **时长必须带单位。** `/tempban`、`/tempmute` 只接受 `30m`、`2h`、`7d`、`1w2d`、`30分钟`、`永久`
> 这类写法，`/tempban 某人 7` 会被拒绝。早期版本把无单位的数字当分钟处理，
> 于是这条命令会静默地只封 7 分钟而不是 7 天，且不给任何提示。

---

## 存储

三种后端共用同一套数据结构，可以随时切换：

| 类型 | 说明 |
| --- | --- |
| `yaml` | 每个玩家一个 `.yml`，零外部依赖，适合中小型服务器（默认） |
| `sqlite` | 单文件数据库，玩家量大时更快 |
| `mysql` | 多服共享余额、家、封禁等数据 |

SQLite / MySQL 的 JDBC 驱动**不会打进 jar**，而是在第一次使用 SQL 后端时
自动下载到 `plugins/EssentialEngine/libs/`，之后离线也能用。
国内服务器可以把 `storage.maven-repository` 换成阿里云镜像。

---

## 经济与 Vault

内置经济系统的余额直接存在玩家数据里，因此自动跟随所选存储后端。
如果服务器装了 Vault，插件会把自己注册为 Vault 的经济提供者
（通过运行时动态代理实现，**不需要 VaultAPI 编译依赖**，没装 Vault 也不会报错）。

### 加载时序

商店、职业、任务这类插件通常在自己的 `onEnable()` 里执行
`getServicesManager().getRegistration(Economy.class)`，
如果那一刻还没有经济提供者，它们会直接判定「没装经济插件」而自我禁用。

所以本插件把**配置读取、存储初始化、玩家管理器、Vault 注册**全部提前到 `onLoad()`：
Bukkit 会先对所有插件依次调用 `onLoad()`，然后才开始调用任何插件的 `onEnable()`，
因此无论服务器上的插件加载顺序如何，经济服务都必定先于消费方就绪，
而且此时存储已经连上，余额查询是真实数据而不是 0。

`onEnable()` 里只保留必须在服务端就绪后才能做的事（命令注册、事件监听、模块启用）。

这样就不需要 `load: STARTUP`（那会让插件在世界加载前启用，副作用更多），
也不需要维护一份 `loadbefore` 插件名单。

同时装了多个经济插件时，用 `modules.economy.vault-priority`
（`Lowest` / `Low` / `Normal` / `High` / `Highest`）决定谁生效，默认 `Normal`。

---

## REST API（可选）

`modules.webapi` 默认为关闭。开启后会监听一个带 API Key 鉴权的 HTTP 接口，
供 QQ / Discord 机器人、网页后台、监控面板调用。

所有请求都需要携带请求头：`Authorization: Bearer <api-key>`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/server/status` | 服务器状态、TPS、内存、在线人数 |
| GET | `/api/server/players` | 在线玩家列表 |
| GET | `/api/server/players/{name}` | 在线玩家详情 |
| GET | `/api/server/plugins` | 已安装插件 |
| POST | `/api/server/command` | 以控制台身份执行命令（可配白名单） |
| GET | `/api/essentials/players/{name}` | 玩家档案（**离线也能查**） |
| GET | `/api/essentials/homes/{name}` | 某玩家的家列表 |
| GET | `/api/essentials/warps` | 地标列表 |
| GET | `/api/essentials/economy/top?limit=10` | 余额排行榜 |
| POST | `/api/essentials/economy/{name}` | 增减 / 设置余额 |
| POST | `/api/essentials/broadcast` | 全服广播 |
| POST | `/api/essentials/message` | 给指定玩家发消息 |
| GET/POST/DELETE | `/api/husktowns/...` | 城镇查询与管理（需 HuskTowns） |

安全提示：`bind-address` 默认是 `127.0.0.1`（只允许本机访问）。
若确实需要公网访问，请务必换成足够随机的 `api-key`，并在防火墙上限制来源 IP。

---

## 权限

命令权限统一为 `essentialengine.command.<命令名>`，
对他人操作的额外权限为 `essentialengine.command.<命令名>.others`。

几个常用的特殊权限：

- `essentialengine.homes.<数字>` / `essentialengine.homes.unlimited` —— 家数量上限
- `essentialengine.teleport.bypass.warmup` / `.cooldown` —— 免吟唱 / 免冷却
- `essentialengine.vanish.see` —— 能看见隐身玩家

传送冷却与吟唱除了全局的 `warmup-seconds` / `cooldown-seconds`，
还可以按类型覆写（`home` `warp` `spawn` `tpa` `back` `rtp`）：

```yaml
modules:
  teleport:
    cooldown-seconds: 5   # 没单独配的类型用它
    cooldowns:
      rtp: 300            # /rtp 要现场生成区块，单独拉长
      home: 0             # 0 = 这一类不限制
    warmups:
      spawn: 0            # 回城秒传
```

没写 `cooldowns` / `warmups` 段时行为与旧版本完全一致。
- `essentialengine.chat.color` —— 聊天使用颜色代码
- `essentialengine.kit.<套装名>` —— 领取指定套装
- `essentialengine.ban.exempt` / `essentialengine.kick.exempt` —— 免疫封禁 / 踢出

---

## 构建

```bash
# Windows
gradlew.bat clean shadowJar

# Linux / macOS
./gradlew clean shadowJar
```

产物位于 `build/libs/EssentialEngine-<版本>.jar`，直接丢进 `plugins/` 即可。

首次构建需要联网拉取 `paper-api`、`husktowns-bukkit` 与 `placeholderapi`
（后两者都是可选前置，仅编译期需要）。

### 测试

```bash
gradlew.bat test
```

测试只覆盖不依赖服务端运行时的纯逻辑，重点是出错代价高、又不容易靠肉眼发现的几处：

| 覆盖对象 | 为什么值得测 |
| --- | --- |
| `UserData` 余额并发 | 并发扣款超卖会直接刷钱；用例是 64 线程同时扣款后对账 |
| `TimeUtil.parseDuration` | 时长解析错了不会报错，只会静默封错时长 |
| `ConfigService.coerce` | 面板保存配置的唯一类型关口，转错会改坏 config.yml 里的类型 |
| `OidcClient.isAllowed` | 面板唯一的授权关口，放宽等于交出服务端配置 |

需要 Bukkit 实例才能跑的部分不在这里覆盖，属于上服验证的范畴。

---

## 消息与本地化

语言文件统一放在 `plugins/EssentialEngine/lang/`，内置 `zh_CN.yml` 与 `en_US.yml`。

- **自动跟随客户端语言**：中文客户端（`zh_*`）看到 zh_CN，其余语言回退到 en_US；
  控制台使用 config.yml 的 `language`。往 `lang/` 里添加 `ja_JP.yml` 之类的文件，
  对应语言的客户端会自动匹配，无需任何配置。
- **无前缀、统一配色**：消息使用 MiniMessage 十六进制配色
  （成功 `#7BC96F`、失败 `#E06C75`、警告 `#E5C07B`、主色 `#61AFEF`、
  正文 `#E8EAED`、次要 `#8B95A5`、弱化 `#5C6370`）。
- 把某条消息改成空字符串即可让插件不再发送它；
  插件更新后新增的消息键会自动回落到内置默认值，不需要删档重建。
- 「永久」「控制台」、时长（`3天2小时` / `3d 2h`）等占位符值
  也会按每位接收者的语言分别渲染——同一条广播，中英文玩家各看各的语言。

---

## 经济统计与流水

因为本插件注册成了 **Vault 的经济提供者**，商店、职业、任务这些插件的每一笔扣款和发钱
最终都会经过本插件的经济接口。所以流水记录能覆盖**全服所有**资金变动，
而不只是本插件自己的 `/pay` `/eco`。

用 `/eco stats` 查看，或在网页面板的「经济」页里看图形化的版本。

### 能看到什么

- **余额层面**：流通总量、账户数、人均余额、最高余额
- **流动层面**（近 N 天，默认 7 天）：总流入、总流出、净流入
- **按来源拆分**：哪个插件发出了多少钱、又收走了多少钱

最后一项是最有价值的——它能直接回答「服务器在通货膨胀吗」：
如果 Jobs 每天注入 20 万而商店只回收 5 万，货币总量就在持续膨胀。
本插件自己的操作会细分到具体动作（`pay → Notch`、`starting-balance`、`eco set by Console`）。

### 配置

```yaml
modules:
  economy:
    track-transactions: true          # 总开关
    track-sources: true               # 是否识别发起交易的插件
    transaction-flush-seconds: 15     # 攒多久批量落盘一次
    transaction-retention-days: 30    # 流水保留天数
    stats-window-days: 7              # 统计窗口
```

### 实现说明

- **不阻塞游戏**：交易可能发生在主线程（玩家点一下商店），所以记账只往内存队列里塞，
  由定时任务批量异步落盘。队列写满时丢弃最旧的记录——宁可少几条统计，也不拖慢服务器。
- **来源怎么识别的**：Vault 的接口没有「谁发起的」这个参数，只能顺着调用栈往外找，
  取第一个不属于本插件的类，反查它属于哪个插件。结果按类缓存，同一个插件只解析一次。
  关掉 `track-sources` 后所有外部交易都会记成 `EssentialEngine`。
- **存哪里**：跟随 `storage.type`。SQLite / MySQL 建一张带索引的 `ee_transactions` 表；
  YAML 后端写成 `data/transactions.jsonl`（一行一条，追加是 O(1)，统计时顺序扫一遍）。
  MySQL 时多个服务器的流水会汇总到一起。

---

## 性能监控与 AstrBot 预留接口

`modules.monitor` 默认开启：定时采样 **TPS / 内存 / 在线人数**，并自动记录
**性能相关事件**与**服务器启停事件**，数据跟随 `storage.type` 持久化
（YAML 后端写成 `data/monitor_events.jsonl` / `monitor_samples.jsonl`，
SQLite / MySQL 建 `ee_monitor_events` / `ee_monitor_samples` 表）。

### 自动记录什么

| 事件类型 | 触发条件 |
| --- | --- |
| `lag` | TPS 跌破阈值（默认 15），去重间隔 60 秒 |
| `lag_recovered` | TPS 恢复到「阈值 + 2」以上 |
| `memory_high` | 已用内存占比超过阈值（默认 90%），去重间隔 5 分钟 |
| `server_start` | 插件随服务器启动 |
| `server_stop` | 服务器正常关闭 |
| `reload` | `/ee reload` 或插件被重载 |
| `abnormal_shutdown` | **下次启动时补记**：上次会话没走完关服流程（崩溃 / 强杀） |

采样与事件都先入内存队列、由定时任务批量异步落盘，**不会阻塞主线程**——
即使主线程已经卡死，采样器依然能记录下当时的 TPS，这正是排查卡顿需要的数据。

### 配置

```yaml
modules:
  monitor:
    sample-interval-seconds: 10     # 采样间隔
    flush-seconds: 15               # 攒多久批量落盘一次
    retention-days: 7               # 数据保留天数，超期每小时清理
    record-lag: true
    lag-threshold-tps: 15.0
    lag-cooldown-seconds: 60
    record-memory: true
    memory-warning-percent: 90
    memory-cooldown-minutes: 5
    record-start-stop: true         # 启动 / 关闭 / 重载事件
    allow-custom-events: true       # 是否允许外部程序写入自定义事件
```

### 查看方式

- **游戏内 / 控制台**：`/eemonitor [status|events|samples|sessions|record]`，
  权限 `essentialengine.command.eemonitor`（默认 OP）。
  `/eemonitor record <类型> <内容>` 可以手动记录一条自定义事件。
- **网页管理面板**：开启 `panel` 模块后，「监控」页有 TPS / 内存曲线、
  最近事件与会话记录（异常退出会标红）。
- **REST 接口**（为 AstrBot 等外部程序预留，见下）。

### AstrBot 预留接口

同时开启 `modules.monitor` 与 `modules.webapi` 后，webapi 会挂载
`/api/monitor/*` 接口，鉴权与其它接口一致（`Authorization: Bearer <api-key>`），
接口契约如下，AstrBot 插件按此对接即可：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/monitor/status` | 当前 TPS / 内存 / 在线 / 运行时长 / 本次运行事件计数 / 会话状态 |
| GET | `/api/monitor/samples?minutes=30&limit=500` | 性能采样历史（正序，超出自动抽稀），含 `tps` `usedMB` `maxMB` `online` |
| GET | `/api/monitor/events?limit=50&type=lag&since=<毫秒时间戳>` | 最近事件（倒序），可按类型过滤、按时间起点过滤 |
| GET | `/api/monitor/sessions?limit=20` | 启动 / 关闭配对记录，`abnormal` 标记异常退出，`running` 标记进行中 |
| POST | `/api/monitor/events` | 写入自定义事件（需 `allow-custom-events`），请求体 `{"type":"...","message":"...","data":{...}}` |

典型用法示例（Python）：

```python
import requests

BASE = "http://127.0.0.1:8192"
HEADERS = {"Authorization": "Bearer <api-key>"}

status = requests.get(f"{BASE}/api/monitor/status", headers=HEADERS).json()["data"]
print(status["tps"], status["memory"]["usedMB"])

# 让 AstrBot 把「检测到刷屏」这类外部事件也记进同一份事件日志
requests.post(f"{BASE}/api/monitor/events", headers=HEADERS,
              json={"type": "spam_detected", "message": "玩家 Alice 连续发送 20 条消息"})
```

> 事件类型字段、`data` 里的结构化字段都是稳定契约；Java 侧也预留了
> `MonitorService.recordEvent(type, message, data)`，其它模块可直接调用。

---

## 网页管理面板

在浏览器里查看服务器状态、直接编辑 `config.yml`、管理在线与离线玩家。
界面是 iOS 风格的毛玻璃单页应用，**整页打包进 jar，不依赖任何外部 CDN**，
因此没有外网出口的服务器也能正常打开。

### 启用

```yaml
modules:
  panel:
    enabled: true
    password: "换成一个足够复杂的密码"
```

改完重启或 `/ee reload`，然后打开 `http://127.0.0.1:8193`。

> **密码和 OAuth 至少要配好一个，否则模块会拒绝启动**，不存在「空密码进后台」的窗口。
> 不想在配置文件里留明文，可以填 sha256 摘要：
> `password: "sha256:<64 位十六进制摘要>"`；
> 也可以完全不用密码，只走下面的 OAuth 登录。

### 功能

| 页面 | 能做什么 |
| --- | --- |
| 概览 | 在线人数、TPS、内存、运行时长、各模块启用状态、一键重载 |
| 配置 | 按分组编辑 config.yml，**YAML 里的注释会作为说明显示出来**，支持搜索 |
| 玩家 | 在线列表 + **按名字搜索离线玩家**；点任一条目展开详情抽屉 |
| 经济 | 全服流通总量、人均余额、资金来源占比与最近流水 |
| 监控 | TPS / 内存双曲线图（每 15 秒自动刷新）、最近性能事件、启动关闭会话记录（需开启 monitor 模块） |

玩家详情抽屉里能看到档案（余额、首次加入、最后在线、游戏时长、家的数量、UUID）、
封禁与禁言的理由 / 操作者 / 到期时间、在线时的世界与延迟，以及**这名玩家自己的经济流水**。
管理操作（踢出 / 封禁 / 临时封禁 / 解封 / 禁言 / 解除禁言 / 改余额）在抽屉里同样可用，
对离线玩家也生效——踢出只对在线玩家显示。

> 搜索走存储层的名字索引，离线玩家只要在本服登录过就能查到。
> 用户名里的 `_` 会被正确转义，不会当成通配符匹配到别人。

配置页保存后**需要重载才生效**，保存与重载是两个独立动作：
先改若干项再保存不会中断面板，只有点「重载插件」才会重启模块（届时需要重新登录）。

### 用皮肤站账号登录（OAuth / OIDC）

面板可以作为 **OpenID Connect 客户端**，用皮肤站等身份源登录，不必再单独记一个面板密码。
配套的 provider 例如 [EnderPass](https://github.com/Cinnaio/EnderPass)（Blessing Skin 插件，
让皮肤站变成标准 OIDC Provider）。

**第一步**，在身份源那边创建应用，回调地址填「面板地址 + `/oauth/callback`」。
如果你按推荐做法走 SSH 隧道访问面板，那就是：

```
http://127.0.0.1:8193/oauth/callback
```

**第二步**，把拿到的 client_id / client_secret 填进配置：

```yaml
modules:
  panel:
    oauth:
      enabled: true
      issuer: "https://skin.example.com"
      client-id: "填这里"
      client-secret: "填这里"
      redirect-uri: "http://127.0.0.1:8193/oauth/callback"
      require-admin: true
```

`issuer` 填到域名即可，会自动去取 `/.well-known/openid-configuration`。

**谁能登录？** 由 `require-admin` 与 `allowed-users` 两项共同决定：

| `allowed-users` | `require-admin` | 谁能进 |
| --- | --- | --- |
| 空 | `true`（默认） | 身份源里的管理员 |
| 空 | `false` | 身份源里的任何账号 |
| 有名单 | 任意值 | **只有名单里的人，`require-admin` 不再生效** |

注意第三行：填名单是**替换**判断依据，不是在 `require-admin` 之上再收窄一层。
名单里的人即使不是身份源的管理员也能进面板。想要「必须是管理员、且在名单里」的效果，
就只把管理员写进名单。

名单可以填角色名、游戏 UUID 或身份源用户 ID。
**被身份源封禁的账号任何情况下都会被拒绝，名单也覆盖不了。**

实际生效的规则会在开服时打印到控制台，例如：

```
[Panel] 准入规则：仅 allowed-users 名单内的 2 人可登录；已配置名单，require-admin 不再生效
```

密码登录和 OAuth 可以共存——两个都配好时登录页会同时显示，密码登录可以留作
身份源挂掉时的兜底。也可以把 `password` 留空，只用 OAuth。

实现上走的是标准授权码流程 + PKCE：`state` 防 CSRF、`nonce` 防重放，
`id_token` 会对 JWKS 公钥做 RS256 验签并逐项核对 `iss`/`aud`/`exp`/`nonce`。
规范允许直连 token 端点时跳过验签，但这里坚持验——你完全可能把 issuer 配成内网
http 地址，那时候没有 TLS 可依赖。整个实现只用 JDK 自带的 HttpClient 和
`java.security`，没有引入任何 JWT / OAuth 库。

### 安全须知

面板能改配置、能管玩家，**拿到面板密码基本等同于拿到服务器管理权**。默认配置已经做了收敛：

- **只监听 `127.0.0.1`**。面板没有 HTTPS，密码是明文传输的，
  所以不要直接把 `bind-address` 改成 `0.0.0.0` 暴露到公网。
  需要远程访问就用 SSH 端口转发（推荐），或在前面套 Nginx 反代加 HTTPS：

  ```bash
  ssh -L 8193:127.0.0.1:8193 你的用户名@服务器地址
  ```

  然后在本地浏览器打开 `http://127.0.0.1:8193`。
- **面板不提供控制台**。执行任意命令的能力风险过高，且已有 REST API
  （`webapi` 模块，带命令白名单）可以满足这类需求。
- **登录限流**：同一 IP 连续失败 5 次锁定 10 分钟。
- **会话**：登录后签发随机 token，放在请求头而不是 Cookie 里
  （Cookie 会被浏览器自动带上跨站请求，那样还得再做一层 CSRF 防护）。
  默认 120 分钟无操作即失效。
- **敏感字段不下发**：MySQL 密码、REST API 的 api-key、面板自己的密码
  在配置页只显示「已设置」，不会把明文发到浏览器；留空即表示不修改。
- 配置写入只接受 config.yml 里**已存在的标量路径**，并保持原有类型，
  既不能注入新键，也不会把某个配置节整个覆盖掉。

---

## PlaceholderAPI 变量

装了 [PlaceholderAPI](https://www.spigotmc.org/resources/6245/) 后 `papi` 模块会自动启用，
变量无需 `/papi ecloud download`——它由本插件自己注册。

每个变量都有两种写法，`%essentialengine_<名称>%` 与更好写的 `%ee_<名称>%`，两者等价
（短别名可在 `config.yml` 的 `modules.papi.short-alias` 关掉）。

### 玩家

| 变量 | 说明 |
| --- | --- |
| `name` / `uuid` | 玩家名 / UUID |
| `displayname` | 展示名，有昵称则用昵称 |
| `nickname` / `nickname_raw` | 昵称；无昵称时前者显示「无」，后者为空 |
| `has_nickname` | `true` / `false` |
| `is_online` | 是否在线 |
| `locale` | 客户端语言，如 `zh_cn` |

### 经济

| 变量 | 说明 |
| --- | --- |
| `balance` | 余额，两位小数 |
| `balance_formatted` | 带货币符号，如 `$100.00` |
| `balance_commas` | 带千分位，如 `1,234.00` |
| `currency_symbol` / `currency_name` | 货币符号 / 名称 |
| `baltop_position` | 自己的排名，未进前 N 名时为 `0` |
| `baltop_name_<名次>` | 排行榜第 N 名的玩家名 |
| `baltop_balance_<名次>` | 第 N 名的余额 |
| `baltop_balance_formatted_<名次>` | 第 N 名的余额（带符号） |

### 时间

| 变量 | 说明 |
| --- | --- |
| `playtime` | 累计在线时长，按玩家语言渲染 |
| `playtime_hours` / `_minutes` / `_seconds` | 累计在线的纯数字 |
| `session` / `session_seconds` | 本次会话时长 |
| `idle` / `idle_seconds` | 空闲（无操作）时长 |
| `firstjoin` / `lastlogin` / `lastseen` | 首次登录 / 最后登录 / 最后在线的时间 |
| `lastseen_ago` | 最后在线距今多久，如 `3小时前` |

### 状态

| 变量 | 说明 |
| --- | --- |
| `afk` / `vanished` / `god` / `fly` | `true` / `false` |
| `socialspy` / `msgtoggle` | `true` / `false` |
| `afk_display` / `vanish_display` | 开启时显示 `[挂机]` / `[隐身]`，否则为空 |

### 惩罚

| 变量 | 说明 |
| --- | --- |
| `muted` / `banned` | `true` / `false` |
| `mute_display` / `ban_display` | 生效时显示 `[禁言]` / `[封禁]`，否则为空 |
| `mute_reason` / `ban_reason` | 原因 |
| `mute_source` / `ban_source` | 执行者 |
| `mute_expiry` / `ban_expiry` | 到期时间，永久时显示「永久」 |
| `mute_remaining` / `ban_remaining` | 剩余时长 |

### 家、地标与套装

| 变量 | 说明 |
| --- | --- |
| `homes` / `homes_max` / `homes_free` | 已设置 / 上限 / 剩余可设置的家数量 |
| `homes_list` | 家名称列表 |
| `has_home_<名称>` | 是否设置了指定的家 |
| `warps` / `warps_list` | 地标数量 / 名称列表 |
| `kit_ready_<套装名>` | 该套装现在能否领取 |
| `kit_cooldown_<套装名>` | 剩余冷却，可领取时显示「可领取」 |
| `mails` / `has_mail` / `ignored` | 邮件数 / 是否有邮件 / 屏蔽人数 |

### 服务器与城镇

| 变量 | 说明 |
| --- | --- |
| `online`（= `online_visible`） | 在线人数（**不含隐身玩家**） |
| `online_total` | 在线人数（含隐身） |
| `vanished_count` / `afk_count` | 隐身 / 挂机人数 |
| `max_players` | 服务器人数上限 |
| `tps`（= `tps_1m`） / `tps_5m` / `tps_15m` | TPS（Folia 上返回空） |
| `version` / `storage` / `modules` | 插件版本 / 存储后端 / 已启用模块 |
| `module_<模块ID>` | 该模块是否启用 |
| `town` / `has_town` / `town_role` | 所属城镇 / 是否有城镇 / 城镇身份 |
| `town_level` / `town_members` / `town_money` | 城镇等级 / 成员数 / 金库（需 HuskTowns） |

### 几点说明

- **时长与日期跟随玩家语言**：`%ee_playtime%` 在中文客户端上是 `3天2小时`，
  在英文客户端上是 `3d 2h`，与插件内消息使用同一套本地化。
- **颜色**：带颜色的变量输出传统 `§` 颜色码，因此不认识 MiniMessage 的
  计分板 / Tab 插件也能正确显示。`[挂机]` 这类标签的文本和颜色可在
  语言文件的 `papi` 段自行修改，改成空字符串即可让它不显示。
- **性能**：占位符只读内存，不做任何阻塞的磁盘 / 数据库读取。
  余额排行榜走异步刷新的快照，缓存名次和间隔由 `modules.papi.baltop-size`
  与 `baltop-cache-seconds` 控制（默认前 10 名、60 秒）。
- **离线玩家**：只有仍在缓存中的离线玩家才能取到数据，
  其余情况下与玩家数据相关的变量返回空字符串（为的是不在渲染路径上读盘）。
