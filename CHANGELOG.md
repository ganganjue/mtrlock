# Changelog

mtrlock（MTR 线路 / 车站 / 车厂权限模组）的版本变更记录。
版本号以 `gradle.properties` 的 `version` 为准，构建产物为 `build/libs/mtrlock-<version>.jar`（已 remap）。

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
