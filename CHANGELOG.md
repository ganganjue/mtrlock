# Changelog

mtrlock（MTR 线路 / 车站 / 车厂权限模组）的版本变更记录。
版本号以 `gradle.properties` 的 `version` 为准，构建产物为 `build/libs/mtrlock-<version>.jar`（已 remap）。

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
