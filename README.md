# mtrlock

> 把 MTR（Minecraft Transit Railway）的**线路 / 车站 / 车厂（含站台 / 侧线）**的编辑与删除权限，
> 限制给对象创建者与管理员（OP 3+）。

## 功能

- **归属记录**：玩家通过铁路仪表板创建 route / station / depot 时，自动记录 `objectId → 玩家UUID`。
- **服务端拦截（最终权威）**：非创建者且非 OP 3+ 的编辑 / 删除会被拒绝并提示。
  - 编辑 / 删除**线路、车站、车厂**；
  - 编辑 / 删除**站台、侧线**（按所属车站 / 车厂判定，即“改子对象 = 改它所属的对象”）；
  - 线路“禁用报站”开关。
- **管理员豁免**：OP 权限等级 ≥ 3 直接放行。
- **客户端预判（功能 6）**：MTR 客户端发包前先本地判定，拒绝时不发包，避免本地“乐观更新”造成世界内视觉不一致。
- **坏文件保护**：`ownership.json` 损坏时不会被空数据覆盖；删除成功后自动清理归属记录。
- **区域方块保护（1.3.0）**：**车站 / 车厂矩形范围内**的方块破坏与放置，按该对象的归属权保护。
  - 创建者、对象分享给的团队成员、OP 3+ 可正常破坏 / 放置，其余玩家被拒绝并提示；
  - **站台 / 侧线所在位置随父车站 / 车厂矩形自动覆盖**，不单独处理；
  - 纯服务端判定，客户端拦截行为不变；**未装客户端同样生效**；
  - 开关 / 范围扩张见 `config/mtrperm/protection.properties`，管理命令 `/mtrlock protect ...`。

- **团队系统**：玩家可创建 / 加入最多 3 个团队，把对象的编辑权限分享给团队成员。
  - 团队命令：`/team create|apply|accept|invite|join|leave|kick|transfer|disband|...`（共 13 个）
  - 分享命令：`/team share|unshare|shares`
  - 查询命令：`/mtrlock my`（列出你创建的对象 ID）、`/mtrlock info <对象ID>`
  - 被踢 / 退队时，该玩家分享给团队的对象自动撤销分享
  - 团队解散时清理所有指向该团队的分享

- **玩家名前缀**：聊天栏、tab 列表、头顶名字显示 `[团队名前两字]`；有自定义称呼时显示 `[称呼]`；两者都没有时**不显示前缀**。
  - 前缀取“最早加入的团队”名前 2 个 Unicode code point（中文按字算，emoji 不截断）。
  - 客户端头顶名字基于 S2C 同步的团队快照本地计算，无团队时不误标。
  - 装了 StyledChat / StyledPlayerList 时改用 Placeholder API，见下方“与 StyledChat / StyledPlayerList 共存”。

- **管理员自定义称呼 + 颜色（1.2.2 引入 / 1.2.3 GUI / 1.2.4 颜色）**：仅 OP 3+ 可修改：
  `/mtrlock title <玩家名> <称呼> [颜色]` 设置称呼（可带颜色）、
  `/mtrlock title color <玩家名> <颜色>` 只改颜色（`reset` / `none` 清除）、
  `/mtrlock title clear <玩家名>` 清除（连同颜色）、`/mtrlock title` 查看自己（含颜色）；
  也能用 `/mtrlock gui title` 在图形界面里操作。**玩家不能自选称呼 / 颜色。**
  - 称呼最长 16 个 Unicode code point，不能为空、不能含控制字符；含空格需用双引号。
  - **颜色（1.2.4）**：支持 16 原版色（`&a` / `§a` / `red` 等）、`#RRGGBB`（大小写不敏感）、
    原版 hex（`&x&r&r&g&g&b&b`）；统一规范化为小写 `#rrggbb` 存储。
  - **颜色只影响样式、不影响优先级**：**自定义称呼（带色）> 团队名前两字（不带色）> 不显示**；
    聊天栏 / tab / 头顶 / `%mtrlock:title_colored%` 四处显示一致。

- **图形界面（1.2.3 引入 / 1.2.4 颜色生效）**：团队系统与称号系统可在游戏内操作，命令入口全部保留。
  - 团队 GUI：`/mtrlock gui` 或 `/mtrlock gui team`（普通玩家可用）。
  - 称号 GUI：`/mtrlock gui title`（**仅 OP 3+**）。
  - GUI 与命令走**同一段服务端逻辑**（`TeamActions` / `TitleActions`），权限判定、成功 / 失败结果完全一致；
    客户端只做展示与发包，服务端校验通过后才下发全量快照（客户端不做乐观更新）。
  - 未安装 mtrlock 客户端的玩家命令仍可用，GUI 入口会提示需要安装客户端。
  - **称号 GUI 颜色区 1.2.4 已生效**：16 原版色网格 + HEX 输入 + 最近使用 + 重置颜色，
    并实时预览聊天栏 / tab / 头顶效果。
  - GUI 数据文件格式：`ownership.json` / `teams.json` / `shares.json` 与 1.2.3 完全一致；
    `titles.json` 1.2.4 升级为对象格式（旧字符串格式仍可读取，升级无感）。

## 环境要求

| 组件 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | >= 0.19.5 |
| Fabric API | 0.92.12+1.20.1 |
| MTR (Minecraft Transit Railway) | >= 4.0.0（`FABRIC-4.0.0+1.20.1`） |
| Java | 17+ |
| 本模组 | mtrlock 1.3.0 |

> StyledChat（`styledchat`）/ StyledPlayerList（`styledplayerlist`）是**可选**模组。
> 装了它们时 mtrlock 改用 Placeholder API 暴露前缀（详见“与 StyledChat / StyledPlayerList 共存”），
> 未装时内置的显示 Mixin 直接生效，**无需任何配置**。

## 安装

服务端和客户端**都要**安装。

1. 安装 Fabric Loader（1.20.1）。
2. 把以下 jar 放进 `mods/`：
   - `mtrlock-1.3.0.jar`
   - `fabric-api-0.92.12+1.20.1.jar`
   - `minecraft-transit-railway-FABRIC-4.0.0+1.20.1.jar`
3. 启动一次服务端，会生成 `config/mtrperm/ownership.json`。

## 使用

- 无需命令；管理员 = OP 权限等级 ≥ 3。
- 创建者自动记录；只有创建者本人与管理员可以编辑 / 删除对应对象。
- 非创建者操作时：
  - **客户端**（MTR GUI）：快捷栏提示 `你没有权限编辑此对象` / `你没有权限删除此对象`，且不发包；
  - **服务端**：聊天栏同样提示，并拒绝操作。
- **区域方块保护（1.3.0）**：在别人的车站 / 车厂矩形里破坏或放置方块会被拒绝并提示；
  自己创建的对象、分享给的团队、OP 3+ 不受影响。详见下方「区域方块保护（1.3.0）」。

## 图形界面（GUI）与权限

| 入口 | 谁能用 | 说明 |
|---|---|---|
| `/mtrlock gui`、`/mtrlock gui team` | 普通玩家（OP 3+ 兜底不变） | 团队：我的团队 / 创建 / 申请 / 邀请 / 待处理 / 成员管理 / 分享管理 / 退出·解散（二次确认） |
| `/mtrlock gui title` | **仅 OP 3+** | 称号：搜索玩家 / 当前称号 / 文本（≤16 字）/ 颜色区（**1.2.4 已生效**）/ 实时预览 / 保存·清除·重置颜色 |

- GUI 操作在服务端做**协议版本校验 → 限流 → 权限 / 目标校验**，再调用与命令相同的
  `TeamActions` / `TitleActions`，因此 **GUI 与命令行为一致**。
- 客户端发包前**不做乐观更新**；操作后等服务端 `GuiActionResultS2C` 与全量快照。
- 未安装 mtrlock 客户端时命令入口仍可用，GUI 入口提示需要安装客户端。
- 新增 GUI 通道带协议版本号；客户端 / 服务端版本不匹配时拒绝并提示。

## 区域方块保护（1.3.0）

在 **车站 / 车厂矩形范围内**，方块的破坏与放置按该对象的归属权保护：创建者、对象分享给的团队成员、
OP 3+ 可正常操作，其余玩家被拒绝并收到提示。**站台 / 侧线没有独立坐标，随父车站 / 车厂矩形自动覆盖。**
判定完全复用编辑 / 删除保护的那套 `PermissionChecker`（不新增权限体系），并且**只在服务端做权威判定**——
客户端拦截行为不变，未安装客户端的玩家同样受保护。

- 范围取车站 / 车厂的矩形；MTR 的 y 恒为 `Long.MIN_VALUE / Long.MAX_VALUE`（不限高度），
  所以保护的是**整个高度**。
- 无归属记录的对象（模组安装前 / 网页创建）**fail-open**，不保护。
- 多个矩形重叠时「**任一覆盖对象拒绝即拒绝**」（避免用自有小车厂覆盖进别人的车站来绕过）。
- `expandBlocks > 0` 时范围向四周扩张（例如把围墙也算进去）。

### 配置：`config/mtrperm/protection.properties`

```properties
enabled=true            # 总开关
protectStations=true    # 保护车站矩形
protectDepots=true      # 保护车厂矩形
expandBlocks=0          # 范围向外扩张的方块数（0-256）
notifyPlayer=true       # 拒绝时给玩家发提示
```

- 修改后用 `/mtrlock protect reload` 立即生效，或重启服务器。
- **文件损坏时**保留当前内存设置、且**不会覆盖坏文件**；单个键写错只回退该键的默认值。

### 命令（OP 权限等级 3+）

| 命令 | 说明 |
|---|---|
| `/mtrlock protect status` | 查看保护开关、范围扩张、索引规模与数据是否就绪 |
| `/mtrlock protect reload` | 重新读取 `protection.properties` 并重建索引 |
| `/mtrlock protect rebuild` | 从当前存档（MTR 数据）重建索引，用于网页 dashboard 直改数据后的手动兜底 |

### 索引重建时机

服务器**重启后索引会自动重建**（挂在 MTR 的 `Data#sync()` 上，此时存档数据已加载完成），无需手动操作；
对象创建 / 修改 / 删除时也会同步维护。`/mtrlock protect rebuild` 只是应对「网页直接改数据」的兜底。

## 配置文件

位置：`<服务端运行目录>/config/mtrperm/ownership.json`

格式（`Map<String, String>`，objectId → 玩家 UUID）：

```json
{
  "route:0B0829457F350DE9": "11111111-2222-3333-4444-555555555555",
  "station:695F49B3A0810590": "11111111-2222-3333-4444-555555555555",
  "depot:FC5F351C6978B956": "99999999-8888-7777-6666-555555555555"
}
```

- objectId 为 `<prefix>:<hexId>`，prefix ∈ `route` / `station` / `depot`；hexId 为 **16 位大写十六进制**（MTR `getHexId()`）。
- 可手工编辑（建议在服务端关闭时）。
- **文件损坏时**：本次运行内归属判为 fail-open（大家都能编辑 / 删除），但**坏文件不会被覆盖**；修复 JSON 或删除文件后重启即可恢复。

## 与 StyledChat / StyledPlayerList 共存

mtrlock 启动时会检测服务器是否装了 **StyledChat**（mod id `styledchat`）或
**StyledPlayerList**（mod id `styledplayerlist`），任一存在时：

- mtrlock **不再注入**内置的显示名前缀 Mixin（`EntityDisplayNameMixin` /
  `PlayerListNameMixin` / `ClientDisplayNameMixin` 全部早退），避免和它们重复加前缀；
- 改为用 [Placeholder API](https://modrinth.com/mod/placeholder-api)（mod id `placeholder-api`，
  作者 Patbox，两者都已内置该库）注册下面四个占位符，由服主在它们的配置里自行引用：

| 占位符 | 含义 | 无数据时 |
|---|---|---|
| `%mtrlock:prefix%` | 完整前缀，如 `[红石]` / `[服主]` | 空串 |
| `%mtrlock:title%` | 自定义称呼（不含方括号），如 `服主` | 空串 |
| `%mtrlock:team%` | 团队名前两字（不含方括号），如 `红石` | 空串 |
| `%mtrlock:title_colored%` | **1.2.4 新增**：带颜色的称呼（不含方括号） | 空串 |

优先级与内置一致：**称呼 > 团队 > 不显示**。`%mtrlock:title%` 永远保持纯文本。

`%mtrlock:title_colored%` 的输出格式由 `config/mtrperm/display.json` 控制：

```json
{ "placeholderFormat": "minimessage" }
```

- `minimessage`（默认）：`<#rrggbb>称号`，StyledChat / StyledPlayerList 原生支持；
- `legacy`：`§x§r§r§g§g§b§b称号`。

未装 StyledChat / StyledPlayerList 时，内置显示 Mixin 直接用 Minecraft Component 上色，
不受该配置影响。

### StyledChat 参考配置

配置文件：`config/styled-chat.json`。把玩家名 / 聊天行加上前缀，例如：

```json
{
  "defaultStyle": {
    "displayName": "%mtrlock:prefix% %player:displayname%",
    "messages": {
      "chat": "%mtrlock:prefix% <${player}> ${message}"
    }
  }
}
```

### StyledPlayerList 参考配置

配置文件：`config/styledplayerlist/config.json`。在玩家名格式里加上前缀，例如：

```json
{
  "playerName": {
    "playerNameFormat": "%mtrlock:prefix% %player:displayname%"
  }
}
```

> 上面的 JSON 只展示需要改动的字段，实际配置文件还有其它字段；
> **具体字段名 / 结构以你安装的模组版本为准**。
> 没装 StyledChat / StyledPlayerList 时，mtrlock 内置 Mixin 直接生效，**无需任何配置**。

## 已知限制

- **网页 dashboard（浏览器）**没有玩家身份，不经过权限拦截；网页创建也不会写入归属（网页删除仍会走清理）。
- **功能引入之前已存在**的对象（无归属记录）按 fail-open 放行。
- **一次请求里混合“允许的 + 被拒的”对象**时，当前实现是**整包拒绝**（`ci.cancel()`）。
- **新增的站台 / 侧线**（同一请求内首次出现、尚未进入从属索引）会 fail-open 放行：
  别人可以往你的车站 / 车厂里“加”站台 / 侧线，但**不能改 / 删你已经建好的**。
- **客户端拦截只是体验层防护**：只挡 MTR GUI 的正常编辑；恶意客户端 / 改包可从底层发送绕过。
  **服务端拦截才是最终权威。**
- 客户端提示走快捷栏，服务端提示走聊天栏。
- 团队信息（成员、分享）通过 S2C 同步到客户端，进服后约 1-2 秒内到达；在此之前客户端对非创建者 fail-open（不误拦），服务端仍精确拦截。
- **S2C 协议在 1.2.2 新增 `titles` 字段**：服务端 1.2.2 与客户端 1.1.x（或反之）混用会不兼容，请两端同步升级到同一版本。

- **GUI 与命令是同一套服务端逻辑**：GUI 只是入口，恶意客户端仍无法绕过服务端校验。
- **GUI 协议版本在 1.2.4 升到 2**：使用 GUI 需要**客户端与服务端同为 1.2.4**；
  1.2.3 客户端连 1.2.4 服务端（或反之）时，`/mtrlock gui` 会提示版本不匹配、界面不打开，
  但**命令、聊天栏、tab、头顶名字、Placeholder 均不受影响**（`sync_ownership` 的颜色表是
  可被旧客户端忽略的兼容尾段）。
- **称号颜色只影响样式**：不改优先级、不影响团队前缀选择；颜色区在 1.2.4 已生效。

- **区域方块保护只覆盖“直接破坏 / 放置”**：爆炸、活塞、火焰、水流、命令或其它模组等**间接**改变方块
  本版不保护（功能范围明确限定，留后续版本）。
- **线路（route）没有坐标**，不参与区域方块保护；站台 / 侧线随父对象矩形覆盖，也不单独保护。
- **网页 dashboard 直接改数据**时，MTR 的 `Data#sync()` 通常会在下一次数据操作或重启时把索引纠正过来；
  若在没有任何 `sync` 触发的窗口期内改了范围，可用 `/mtrlock protect rebuild` 立即纠正。
- **车站 / 车厂被 MTR 之外的方式删除**（例如直接改存档文件）时，索引要等下一次 `sync` 或重启才收敛。
- **`titles.json` 在 1.2.4 升级为 `{"uuid": {"text": "...", "color": "#rrggbb"}}`**：
  旧的 `{"uuid": "称呼"}` 仍能正常读取（`color = null`），坏文件保护 / `loadFailed` 策略不变。

> 完整验证步骤与排查清单见仓库根目录的 `VERIFY.md`。

## 从源码构建

```bash
./gradlew build
# 分发用产物: build/libs/mtrlock-<version>.jar （已 remap）
# 注意: build/devlibs/* 是开发用 jar，不要分发
```

## 反馈 / 问题

- 作者：ganganjue

## 许可证

CC0-1.0（见 `LICENSE`）。
