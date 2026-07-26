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
| 聊天与消息 | `chat` | `/msg` `/reply` `/msgtoggle` `/socialspy` `/ignore` `/nick` `/broadcast` `/me` `/afk` `/mail` |
| 管理与惩罚 | `admin` | `/kick` `/ban` `/tempban` `/unban` `/mute` `/tempmute` `/unmute` `/vanish` `/invsee` `/clearinventory` `/seen` `/whois` |
| 经济与套装 | `economy` | `/balance` `/pay` `/eco` `/baltop` `/kit` |
| HuskTowns 对接 | `husktowns` | `/eetown`（未安装 HuskTowns 时自动跳过） |
| PlaceholderAPI 变量 | `papi` | 无命令，提供 `%ee_...%` 变量（未安装 PlaceholderAPI 时自动跳过） |
| REST API | `webapi` | 无命令，提供 HTTP 接口（默认关闭） |
| 核心 | `core` | `/ee reload\|info\|modules\|save` |

关闭某个模块后，它的命令会真正从服务端命令表里移除，不会和其它插件抢命令名。
也可以在 `config.yml` 的 `commands.disabled` 里单独禁用某条命令、在 `commands.aliases` 里追加别名。

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
