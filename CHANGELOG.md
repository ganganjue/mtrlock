# Changelog

mtrlock（MTR 线路 / 车站 / 车厂权限模组）的版本变更记录。
版本号以 `gradle.properties` 的 `version` 为准，构建产物为 `build/libs/mtrlock-<version>.jar`（已 remap）。

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
