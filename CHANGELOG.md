# Changelog

mtrlock（MTR 线路 / 车站 / 车厂权限模组）的版本变更记录。
版本号以 `gradle.properties` 的 `version` 为准，构建产物为 `build/libs/mtrlock-<version>.jar`（已 remap）。

## [1.3.0]

主题「区域方块保护」：把归属权从「MTR 对象的编辑 / 删除」扩展到「对象范围内的方块破坏与放置」。
**归属数据格式零改动**，权限判定完全复用现有 `PermissionChecker`，不新增权限体系。

- **车站矩形范围内的方块破坏 / 放置拦截**：范围取 MTR `Station` 的矩形
  （`AreaBase.getMinX/getMaxX/getMinZ/getMaxZ`，内部已是 `Math.min/max` 归一化）。
  车站 / 车厂的 y 恒为 `Long.MIN_VALUE / Long.MAX_VALUE`（表示不限高度），因此**忽略 y 维度**。
- **车厂矩形范围内同样保护**；**站台 / 侧线没有独立坐标**，随父车站 / 车厂矩形自动覆盖，不单独处理。
- **权限复用**：与编辑 / 删除保护同一套判定——创建者本人、对象分享到的团队成员、OP 3+ 放行，其余拒绝。
  无归属记录（模组安装前 / 网页创建）**fail-open 放行**。多个对象矩形重叠时采用
  「**任一覆盖对象拒绝即拒绝**」，避免用自有小车厂覆盖进别人的车站来绕过保护。
- **服务端权威**：`PlayerBlockBreakEvents.BEFORE`（破坏）与 `UseBlockCallback`（放置）都在服务端判定并取消，
  客户端拦截行为不变；**未安装客户端的玩家同样受保护**，命令也仍可用。
  放置用 `ItemPlacementContext.canPlace()/getBlockPos()` 精确定位「真正会放下的方块」，
  只处理手持 `BlockItem` 的交互，不会误伤开箱子等正常操作；判定异常 fail-open。
- **空间索引 `SpatialIndex`**：按 chunk 索引（`Map<chunkKey, List<ObjectRange>>`），
  查询只遍历所在 chunk，不随车站 / 车厂总数线性扫描。chunk 键为
  `((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`（负数先转 `long`，符号扩展不会造成键碰撞）。
  写入幂等：同一 objectId 改名 / 改色 / 改范围只保留最后一次范围，不累加。
- **索引维护（三处，冗余是刻意的）**：
  - `Data#sync()` RETURN —— MTR 自己的「数据一致」时刻；`Simulator` 构造函数在 FileLoader 全部读完
    （内部 `Future.get()` 阻塞汇合）之后才调用，**重启后索引自动重建，不会保护失效**；
  - `UpdateDataRequest#update()` RETURN —— 新建对象归属刚落库，再补一次；
  - 删除钩子 —— `remove(objectId)`，删除立即失效（删除侧只有 id，无需坐标）。
- **配置** `config/mtrperm/protection.properties`：`enabled` / `protectStations` / `protectDepots` /
  `expandBlocks` / `notifyPlayer`。坏文件保护与其它数据类一致（加载失败置 `loadFailed`、`save` 跳过），
  单个键值非法只回退该键的默认值。
- **命令（OP 3+）**：`/mtrlock protect status`（配置 + 索引规模）、`/mtrlock protect reload`
  （重读配置并重建索引）、`/mtrlock protect rebuild`（从当前存档重建索引）。
- **本版不做（留后续版本）**：线路保护（无坐标）、爆炸 / 活塞 / 火焰 / 水流等间接破坏、
  保护范围可视化、网页 dashboard 创建对象的归属。
- **测试**：新增 `ObjectRange` / `SpatialIndex` / `ProtectionRanges` / `ProtectionConfig` /
  `BlockProtection` 权限矩阵 / 与现有编辑保护共存 等测试（**328 → 413**，全绿）。
- **Java target**：仍为 Java 17（`options.release = 17`，`sourceCompatibility` / `targetCompatibility = 17`）。

## [1.2.4]

在 1.2.3 的统一称号 GUI 内**激活颜色功能**；不改 GUI 结构、命令全部保留、数据语义不变。

- **称号颜色（OP 3+）**：支持 16 原版色（`&a` / `§a` / `red` 等）、`#RRGGBB`（大小写不敏感）、
  原版 hex（`&x&r&r&g&g&b&b`）；统一规范化为小写 `#rrggbb` 存储。
  - **颜色只影响样式，不影响优先级**：自定义称呼（带色）> 团队名前两字（不带色）> 不显示；
    团队前缀行为不变、不被称号颜色干扰。
  - 聊天栏 / tab / 头顶用 Minecraft **Component** 渲染（不拼 legacy 字符串），
    `%mtrlock:title_colored%` 与它们显示一致。
  - **玩家不能自选**称呼 / 颜色，仅 OP 3+ 可修改。
- **命令（全部保留 + 扩展；命令与 GUI 都只走 `TitleActions`）**：
  - `/mtrlock title <玩家> <称呼>` —— 改文本，保留原颜色；
  - `/mtrlock title <玩家> <称呼> <颜色>` —— 同时设置文本与颜色；
  - `/mtrlock title color <玩家> <颜色>` —— 只改颜色（`reset` / `none` 清除）；
  - `/mtrlock title clear <玩家>` —— 连同颜色一起清除。
- **GUI**：`TitleGuiScreen` 颜色区启用（16 色网格 + HEX 输入 + 最近使用 + 重置颜色 + 实时预览），
  去掉“1.2.4 开放”置灰标注；**不新增独立 Screen，页面结构不变**。
- **Placeholder**：新增 `%mtrlock:title_colored%`，输出格式由
  `config/mtrperm/display.json` 的 `placeholderFormat` 控制（`minimessage` 默认 / `legacy`）；
  `%mtrlock:title%` 仍为纯文本、向后兼容；StyledChat / StyledPlayerList 占位符行为保持。
- **数据格式**：`ownership.json` / `teams.json` / `shares.json` 持久化格式**零改动**；
  `titles.json` 升级为 `{"uuid": {"text": "...", "color": "#rrggbb"}}`，
  旧的 `{"uuid": "称呼"}` 仍可读取（`color = null`），坏文件保护 / `loadFailed` / fail-open 不变。
- **协议**：`GuiProtocol.VERSION` 1 → 2（`TitleGuiAction` / `TitleGuiSnapshot` 新增颜色字段）。
  **使用 GUI 需客户端与服务端同为 1.2.4**；1.2.3 客户端连 1.2.4 服务端（或反之）时 GUI 提示版本不匹配、
  界面不打开，但**命令、聊天栏、tab、头顶名字、Placeholder 不受影响**。
  `sync_ownership` 的称号颜色表是**可被旧客户端忽略的兼容尾段**（写端无条件写长度，
  读端用 `isReadable()` 判断），1.2.3 客户端仍可正常进服。
  `ResultCode` **只追加**（`COLOR_SET` / `COLOR_RESET` / `COLOR_INVALID` / `TITLE_REQUIRED`），
  已有结果码名称与序号未改动，旧客户端仍能解析旧结果码。
- **测试**：新增 `ColorParser` / `ColorFormatter` / `TitleData` 新旧格式往返 / `TitleActions` 颜色权限矩阵 /
  Placeholder 颜色输出 / `DisplayConfig` / `ResultCode lang 全覆盖` 等测试（**232 → 328**，全绿）。
- **Java target**：仍为 Java 17（`options.release = 17`，`sourceCompatibility` / `targetCompatibility = 17`）。

## [1.2.3]

新增图形界面（GUI）：团队系统与称号系统都能在游戏内操作；**数据文件格式、命令行为、权限判定完全不变**。

- **团队系统 GUI**（`/mtrlock gui` 或 `/mtrlock gui team`，普通玩家可用）：
  我的团队（队长 / 成员数 / 分享对象数）、创建团队（支持中文名）、申请加入、邀请成员
  （在线玩家列表 + 手动输入）、待处理（批准 / 拒绝 / 接受）、成员管理（踢出 / 转让队长）、
  分享管理（按线路 / 车站 / 车厂 / 站台 / 侧线筛选）、危险操作（退出 / 解散，二次确认）。
  被踢 / 退队仍会自动撤销该玩家分享给该团队的对象。
- **称号系统 GUI**（`/mtrlock gui title`，**仅 OP 3+** 可打开和操作）：玩家搜索 / 在线选择、
  当前称号、称号文本输入（最多 16 个 code point，支持中文）、颜色选择区
  （16 原版色 + HEX + 最近使用，本版**置灰**并标注“1.2.4 开放”）、纯文本实时预览、
  保存 / 清除 / 取消。本版实际生效的是纯文本称号的设置与清除。
- **网络协议**：新增 C2S `TeamGuiActionC2S` / `TitleGuiActionC2S`，S2C `OpenGuiS2C` /
  `TeamGuiSyncS2C` / `TitleGuiSyncS2C` / `GuiActionResultS2C`；所有 GUI 包带**协议版本号**，
  两端不匹配时拒绝并提示。GUI 操作在服务端**限流**（令牌桶），防连点刷包。
- **服务端权威**：GUI 不自己实现权限 / 状态判断，所有操作都走与命令**同一段**
  `TeamActions` / `TitleActions`；客户端发包前不做乐观更新，收到服务端校验后的全量快照才刷新。
- **未装客户端**：命令入口始终可用；用 `/mtrlock gui` 会提示需要安装 mtrlock 客户端。
- **兼容性**：`ownership.json` / `teams.json` / `shares.json` / `titles.json` 持久化格式未改动，
  与 1.2.2 数据可互读；旧的 `sync_ownership` S2C 布局未改动。GUI 为新增协议，
  客户端与服务端需同时升级到 1.2.3 才能使用 GUI（命令与显示前缀不受影响）。
- 已装 StyledChat / StyledPlayerList 时的占位符行为保持不变。

## [1.2.2]

修复：Placeholder 占位符改用 `mtrlock:prefix` / `mtrlock:title` / `mtrlock:team`
（之前 `%mtrlock_prefix%` 的命名空间是 `minecraft`，StyledChat 无法解析、会字面显示）。

## [1.2.1]

显示前缀调整 + StyledChat / StyledPlayerList 共存支持。

- **去掉 “独立建造者” 默认前缀**：无团队无称呼时不再显示任何前缀（聊天栏 / tab 列表 / 头顶名字统一生效）。
- **新增 Placeholder API 支持**：与 StyledChat / StyledPlayerList 共存时，不再注入显示 Mixin，
  改用 `%mtrlock:prefix%` / `%mtrlock:title%` / `%mtrlock:team%` 占位符（由服主在对方的配置里引用）。

## [1.2.0]

新增功能：管理员自定义称呼（title）。

- **自定义称呼**：OP 3+ 可用 `/mtrlock title <玩家名> <称呼>` 给任意在线玩家设置称呼、`/mtrlock title clear <玩家名>` 清除、`/mtrlock title` 查看自己的称呼。
- 称呼规则：≤ 16 个 Unicode code point、不能为空、不能含控制字符（中文 / emoji 按 code point 计）；含空格需双引号。
- 显示优先级：**自定义称呼（完整显示、不截断）> 团队前缀（截前两字）> `[独立建造者]`**；聊天栏 / tab 列表 / 头顶名字三处统一生效。
- 新增数据文件 `config/mtrperm/titles.json`（`playerUuid → title`），沿用坏文件保护（loadFailed 不覆盖坏文件 + 空内存不误清空）。
- 称呼变更后通过 S2C 全量快照推送到客户端；命令层 / 数据层均有测试覆盖。
- ⚠️ **S2C 协议新增 `titles` 字段**：1.2.0 与此前版本（1.1.x）**不兼容**，服务端与客户端必须同时升级。

## [1.1.1]

维护性更新：玩家名前缀（聊天栏 / tab / 头顶名字）、README 完善。

- **玩家名前缀**：聊天栏、tab 列表、头顶名字（客户端渲染）在玩家名前显示 `[团队名前两字]`；无团队显示 `[独立建造者]`。
- 前缀规则：取“最早加入的团队”名的前 2 个 Unicode code point（中文按字算，emoji 不截断）。
- 服务端：聊天栏 / 加入离开 / 死亡消息（`EntityDisplayNameMixin`）与 tab 列表（`PlayerListNameMixin`）由服务端计算。
- 客户端：头顶名字由 `ClientDisplayNameMixin` 基于 S2C 同步的团队快照本地计算；服务端 / 客户端两个 `getDisplayName` Mixin 守卫互斥，不会出现双重前缀。
- README 版本号（1.1.1）与安装说明里的 jar 文件名同步更新。

## [1.1.0]

- 团队系统：玩家可创建 / 加入最多 3 个团队，把对象的编辑权限分享给团队成员（每人最多 3 队）。
- 团队 / 分享 / 查询命令：`/team ...`、`/mtrlock my|info`。
- 团队 / 分享变更后通过 S2C 全量同步到客户端；被踢 / 退队 / 解散时自动撤销相关分享。
