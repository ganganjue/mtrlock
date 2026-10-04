# VERIFY.md — mtrlock 功能 3 / 4 / 5 in-game 验证手册

> 本环境（Termux / Android）**无法运行 Minecraft 客户端**，因此本仓库只做了：
> `./gradlew --offline compileJava` 编译 + JVM 单元测试（31 个用例全过）。
> 真正的 in-game 行为请按本文档在 PC 上验证。
> 本文档不包含任何“已通过 in-game 验证”的声明。

---

## 0. 前置条件

| 组件 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | 0.19.5（`gradle.properties` 同款；0.19.3+ 也可） |
| Fabric API | 0.92.12+1.20.1 |
| MTR (Minecraft Transit Railway) | FABRIC-4.0.0+1.20.1 |
| 本模组 | build/libs/mtrlock-1.0.0.jar（自行 `./gradlew build` 产出） |
| Java | 17+（构建/运行服务端用 21 亦可） |

服务端数据文件位置：

```
<服务端运行目录>/config/mtrperm/ownership.json
```

---

## 1. 装包流程

1. 在项目根目录构建：
   ```
   ./gradlew build
   ```
   产物：`build/libs/mtrlock-1.0.0.jar`（以及 `-sources.jar`，不用管）。

2. 准备 Fabric 服务端（1.20.1）：
   - 放置 `fabric-server-launch.jar` / `fabric-server-mc.1.20.1-loader.0.19.5-launcher…`
   - `mods/` 放入：`fabric-api-0.92.12+1.20.1.jar`、`minecraft-transit-railway-FABRIC-4.0.0+1.20.1.jar`、`mtrlock-1.0.0.jar`

3. 客户端同样装：Fabric Loader + Fabric API + MTR + `mtrlock-1.0.0.jar`。

4. 首次启动服务端，确认控制台无 Mixin 报错；结束后应生成
   `config/mtrperm/ownership.json`（首次可能为空 `{}`，或文件不存在）。

---

## 2. 双客户端 + 服务端搭建

推荐 3 个身份（同一台机器开两个客户端即可）：

| 身份 | 说明 | 准备 |
|---|---|---|
| 玩家 A | 普通玩家，创建线路的人 | 不 OP |
| 玩家 B | 普通玩家，想编辑/删除 A 的线路 | 不 OP |
| 管理员 Admin | OP level 3 | 控制台 `op Admin` |

服务端 `server.properties` 建议：
```
online-mode=false
spawn-protection=0
gamemode=creative
```
（离线模式方便本地双开；正式测试可用正版账号。）

调试日志默认已开启（功能 3 的临时日志 + 功能 5 的拦截日志），无需额外参数。
排查 Mixin 时可加 JVM 参数：
```
-Dmixin.debug.verify=true
-Dmixin.debug.export=true
-Dmixin.debug.countInjections=true
```

---

## 3. 测试场景清单

> 每条场景建议**重开世界或删掉 ownership.json 后重来**，避免互相影响。
> 观察 `ownership.json` 与最新服务端日志。

| # | 场景 | 操作 | 预期结果 | 关键日志 |
|---|---|---|---|---|
| 1 | A 创建线路 | A 用 railway dashboard 新建一条线路并保存 | 成功；`ownership.json` 出现 `"route:<HEX>": "<A-UUID>"` | `[mtrlock][debug] 记录创建者 route:<HEX> -> <A-UUID>` |
| 2 | A 编辑自己的线路 | A 改名/改颜色，保存 | 成功，无拦截 | `[mtrlock][debug] UpdateDataRequest.update() RETURN: ...`（无 `[mtrlock] 拦截`） |
| 3 | B 编辑 A 的线路 | B 尝试改名/改颜色，保存 | 被拒；B 看到聊天框 `你没有权限编辑此对象`；对象不变；`ownership.json` 不变 | `[mtrlock] 拦截 <B-UUID> 的 PacketUpdateData 操作，权限不足: [route:<HEX>]` |
| 4 | Admin(OP3) 编辑 A 的线路 | 管理员改名/改颜色，保存 | 成功，无拦截 | 无 `[mtrlock] 拦截`；有 `UpdateDataRequest.update() RETURN` |
| 5 | A 删除自己的线路 | A 删除线路 | 成功；`ownership.json` 中该 `route:<HEX>` 被移除 | `[mtrlock][debug] 删除清理归属记录: route:<HEX>` |
| 6 | B 删除 A 的线路 | B 尝试删除 | 被拒；B 看到 `你没有权限删除此对象`；对象与 `ownership.json` 都不变 | `[mtrlock] 拦截 <B-UUID> 的 PacketDeleteData 操作，权限不足: [route:<HEX>]` |
| 7 | Admin 删除 A 的线路 | 管理员删除 | 成功；`ownership.json` 清理对应记录 | `[mtrlock][debug] 删除清理归属记录: route:<HEX>` |
| 8 | 线路“禁用报站”开关 | B 在 dashboard 勾选 A 线路的禁用报站 | 被拒；开关不变 | `[mtrlock] 拦截线路开关修改 <B-UUID> 权限不足: route:<HEX>` |
| 9 | 网页 dashboard 编辑 | 浏览器打开 MTR dashboard，编辑/删除线路 | **不受本模组拦截**（无玩家身份）；编辑照常生效、ownership 不变；删除照常生效且**会清理** ownership（核心层 cleanup） | 编辑：无 `[mtrlock] 拦截`；删除：`[mtrlock] 删除清理归属记录: ...` |
| 10 | 车站 / 车厂同理 | 对 station / depot 重复 #1–#7 | 同线路，key 为 `station:<HEX>` / `depot:<HEX>` | 同上，前缀不同 |
| 11 | 单请求混合对象 → 整包拒绝 | 构造一个请求同时含「A 自己的对象」+「A 无权的对象」（复现见 6.7） | **整包被拒**：两个对象都不变；操作者收到 `你没有权限…` | `[mtrlock] 拦截 <UUID> 的 PacketUpdateData 操作，权限不足: [route:<B-HEX>]`（denied 只列无权项，cancel 作用于整包） |
| 12 | ownership.json 损坏 → fail-open + 保护坏文件 | 关服 → 把 `config/mtrperm/ownership.json` 写成非法 JSON（如 `{oops`）→ 开服 | 启动报加载失败；内存归属为空（非管理员编辑/删除**不再被拦**）；停服时 `save()` 跳过，**坏文件不被覆盖**（不再永久丢数据） | 启动：`[mtrlock] 加载归属数据失败，保留原有内存数据，后续 save 将跳过: <path>`；停服：`[mtrlock] 上次加载归属数据失败，跳过保存以避免覆盖损坏文件: <path>` |
| 13 | 编辑站台 / 侧线被拒（方案 A） | B 编辑 A 车站下的**站台**或 A 车厂下的**侧线**（走 PlatformScreen / SidingScreen，请求里只有 `platforms` / `sidings`） | 被拒；B 收到 `你没有权限编辑此对象`；站台/侧线不变 | `[mtrlock] 拦截 <B-UUID> 的 PacketUpdateData 操作，权限不足: [platform:<HEX>]`（或 `siding:<HEX>`） |
| 14 | 删除站台 / 侧线被拒（方案 A） | B 删除 A 车站下的站台 / A 车厂下的侧线 | 被拒；B 收到 `你没有权限删除此对象`；对象仍在 | `[mtrlock] 拦截 <B-UUID> 的 PacketDeleteData 操作，权限不足: [platform:<HEX>]`（或 `siding:<HEX>`） |
| 15 | 客户端拦截：B 编辑 A 的站台 / 侧线 | B 在客户端编辑 A 车站下的**站台**或 A 车厂下的**侧线**，点保存 | 包**不发出**；**世界内渲染无变化**（站台/侧线不变）；B 看到快捷栏 `你没有权限编辑此对象`；<br>**另需确认**：仪表板列表里是否短暂显示本地改动（重开 dashboard 应恢复） | `[mtrlock] 客户端拦截 <B-UUID> 权限不足: [platform:<HEX>]`（或 `siding:<HEX>`） |
| 16 | 客户端拦截：B 编辑 A 的线路 | B 编辑 A 的线路，点保存 | 包**不发出**；**世界内渲染无变化**；提示 `你没有权限编辑此对象`；<br>**另需确认**：仪表板列表里的本地改动是否在重开 dashboard 后恢复 | `[mtrlock] 客户端拦截 <B-UUID> 权限不足: [route:<HEX>]` |
| 17 | 客户端不误伤：A 编辑自己的对象 | A 编辑自己的线路 / 车站 / 车厂 / 站台 / 侧线 | 正常保存并生效；无客户端拦截日志 | 无 `[mtrlock] 客户端拦截`；对象在服务端正常变化 |

---

## 4. 日志关键字一览

创建（功能 3，`UpdateDataRequestMixin`）：
```
[mtrlock][debug] UpdateDataRequest.update() RETURN: stations=<n> routes=<n> depots=<n>
[mtrlock][debug] 记录创建者 route:<HEX> -> <UUID>
[mtrlock][debug] NEW route:<HEX> 但 PendingCreators 无记录，跳过    ← 无玩家来源（网页/命令）
```

编辑 / 删除拦截（功能 5，`PacketEditPermissionMixin`）：
```
[mtrlock] 拦截 <UUID> 的 PacketUpdateData 操作，权限不足: [route:<HEX>]
[mtrlock] 拦截 <UUID> 的 PacketDeleteData 操作，权限不足: [route:<HEX>]
```

线路开关拦截（功能 5，`RouteFlagPermissionMixin`）：
```
[mtrlock] 拦截线路开关修改 <UUID> 权限不足: route:<HEX>
```

删除清理（功能 5，`DeleteOwnershipCleanupMixin`）：
```
[mtrlock][debug] 删除清理归属记录: route:<HEX>
```

玩家会看到的聊天消息：
- 编辑被拒：`你没有权限编辑此对象`
- 删除被拒：`你没有权限删除此对象`

> 说明：任务里给的原文是「你没有权限编辑此对象」，编辑走这条；
> 删除单独用了「你没有权限删除此对象」。若希望两者统一，改
> `PacketEditPermissionMixin.MESSAGE_DELETE` 即可。

---

## 5. 预期的 ownership.json 状态

格式（`Map<String,String>`，objectId → 玩家 UUID）：

```json
{
  "route:0B0829457F350DE9": "11111111-2222-3333-4444-555555555555",
  "station:695F49B3A0810590": "11111111-2222-3333-4444-555555555555",
  "depot:FC5F351C6978B956": "99999999-8888-7777-6666-555555555555"
}
```

- **hexId 是 16 位大写**（`Utilities.numberToPaddedHexString(id)`），例如 `0B0829457F350DE9`；
  不是任务示例里的 `1a2b`，也**不会**小写或截断。
- 由 A 创建 → value 是 A 的 UUID。
- B 编辑/删除被拒后，文件内容**不得变化**。
- A 删除成功后，对应 key **必须消失**。
- 网页 dashboard 创建的对象**不会**写入记录（`PendingCreators 无记录`）；网页编辑也不会改记录；
  网页删除会触发清理（如果对象本就有记录）。

---

## 6. 失败排查清单

### 6.1 创建记录不出现（场景 #1）
- [ ] 服务端日志是否有 `UpdateDataRequest.update() RETURN`？没有 → `UpdateDataRequestMixin` 没生效。
- [ ] Mixin 是否加载：日志开头/`latest.log` 搜 `mtrlock.mixins.json`、`Mixin apply failed`。
- [ ] `config/mtrperm/ownership.json` 是否可写？目录 `config/mtrperm` 是否创建成功。
- [ ] 是否只看到 `NEW ... 但 PendingCreators 无记录，跳过`？
      → 说明创建不是通过玩家 `PacketUpdateData` 来的（网页 dashboard / 命令），属已知未覆盖。
- [ ] MTR 版本是否真的是 `FABRIC-4.0.0+1.20.1`？其它版本字段名可能不同。

### 6.2 B 编辑/删除没有被拦截（场景 #3 / #6）
- [ ] `ownership.json` 里该对象**有没有记录**？没有记录 → 按设计 fail-open 放行。
- [ ] B 是否其实是 OP？用 `/op` 检查；OP level≥3 会被豁免。
- [ ] `PacketEditPermissionMixin` 是否注册（`mtrlock.mixins.json`）。
- [ ] 日志里有没有 `[mtrlock] 拦截`？有拦截但对象还是变了 → 说明改走的不是 `PacketUpdateData`。
- [ ] 是否在编辑**网页 dashboard**？网页路径无玩家身份，已知未覆盖。

### 6.3 管理员也被拦截（场景 #4 / #7）
- [ ] 确认 `hasPermissionLevel(3)`；单人世界若开了 cheat 但没 OP，不算管理员。
- [ ] 用 `/op <name>` 后再试。

### 6.4 删除后 ownership 没清理（场景 #5 / #7）
- [ ] `DeleteOwnershipCleanupMixin` + `DeleteDataRequestSchemaAccessor` 是否都注册。
- [ ] 日志是否有 `删除清理归属记录`？没有 → `DeleteDataRequest.delete()` 没走到，或 accessor 未生效。
- [ ] 删除是否真的成功（对象是否从世界里消失）。

### 6.5 Mixin 启动崩溃 / 注入失败
- [ ] 加 JVM 参数 `-Dmixin.debug.verify=true -Dmixin.debug.export=true` 重启。
- [ ] 看 `run/.mixin.out/` 导出的 class 是否含注入内容。
- [ ] 确认 MTR 版本与 `mtrVersion` 一致；字段/方法名随版本可能变化（本实现基于 4.0.0 反编译）。
- [ ] 本模组 `fabric.mod.json` 的 `mixins` 是否包含 `mtrlock.mixins.json`。

### 6.6 已知限制（非 bug）
- 网页 dashboard（浏览器）没有 `ServerPlayerEntity`，**不经过功能 5 的权限拦截**；
  创建也不会写入归属（功能 3 同样只认玩家包）。
- 对象若在功能 2 引入之前就已存在（无归属记录），编辑/删除按 fail-open 放行；
  需要的话让创建者先触发一次“创建记录”（重新建一个）或手动补 `ownership.json`。
- 一次 `PacketUpdateData` / `PacketDeleteData` 若同时包含“允许的”和“被拒的”对象，
  当前实现是**整包拒绝**（`ci.cancel()`）。MTR 的 dashboard 保存通常是单对象，影响很小。
- **新增的站台 / 侧线会 fail-open（方案 A 的已知缺口）**：
  `platform` / `siding` 的父关系来自服务端 `Data.sync()` 建立、`DataChildParentMixin` 维护的 `ChildParents` 索引
  （MTR 的请求 JSON 不携带父 id）。对**同一请求里首次出现**、尚未进索引的站台 / 侧线，父对象查不到 →
  `PermissionGuard` 按 fail-open 放行。触发条件：玩家 B 往 A 的车站 / 车厂里**新增**一个站台 / 侧线
  （而不是编辑已有的）。严重程度：**中低**——别人可以往你的车站 / 车厂里加子对象，
  但**不能修改 / 删除你已经建好的子对象**。彻底修复需把判定下沉到核心层
  `UpdateDataRequest.update()`（那里有 `data` 可按几何关系解析父对象）并配合操作者关联，属后续项。

---

### 6.7 场景 #11 / #12 复现说明（分别对应 6.6 的「整包拒绝」与「无归属记录 fail-open」）

**#11 单请求混合对象 → 整包拒绝（对应 6.6 第 3 条）**

`PacketUpdateData` / `PacketDeleteData` 的请求体本身支持多个对象
（`stations/routes/depots` 对象数组，或 `stationIds/routeIds/depotIds` long 数组）。
`PacketEditPermissionMixin` 的做法是：`PermissionGuard` 收集**整个请求里所有**被拒的 objectId；
只要 denied 列表非空就 `ci.cancel()`，因此**一个请求里哪怕只有一个对象无权，整包都不生效**。

复现方式（任选其一）：
1. **多选删除**：若 MTR dashboard 支持一次选中多个对象删除，同时选「自己的对象」+「别人的对象」，
   预期两个都不消失。
2. **手工包**：用抓包/自写客户端发一个 `DeleteDataRequest`，`routeIds` 同时包含自己的和别人给的 route id；
   预期服务端日志出现 `[mtrlock] 拦截 <UUID> 的 PacketDeleteData 操作，权限不足: [route:<B-HEX>]`，
   且两个 route 都还在世界里。
3. **无法在 UI 里批量时**：该行为在“列表层面”已由单元测试覆盖——
   `PermissionGuardTest > 编辑：混合请求（新对象 + 别人的对象）只拒绝别人的那个` 与
   `PermissionGuardTest > 删除：混合（1 个别人的 + 2 个无记录）只拒绝 1 个`；
   “只要 denied 非空就 cancel 整包”是 Mixin 胶水层的固定行为。

注意：日志 `权限不足: [route:<HEX>]` 只列出**被拒**的对象；`ci.cancel()` 会把同一请求里
**本来允许**的对象也一起拦下。这是当前设计的取舍（见 6.6）。

**#12 ownership.json 损坏 → fail-open（对应 6.6 第 2 条 + 6.4）**

`OwnershipData.load()` 对损坏 JSON 的处理是：`catch` 异常、**保留当前内存**、不把坏文件读进来，
并置内部标志 `loadFailed = true`。该标志生效后：
- 本次运行内 `save()` 会**跳过写盘并打 warn**，坏文件**不会被覆盖**（数据不会永久丢失）；
- 由于服务端启动时内存本来为空，所有 objectId 的 `hasCreator()` 返回 `false`；
- `PermissionGuard` 按其 fail-open 设计放行（`hasCreator==false` 视为“创建 / 未知对象”）；
- 结果：**本次运行内非管理员也能编辑/删除这些对象**（fail-open 行为不变），但归属文件被保住了。

复现步骤：
1. 关服，**先备份**，再把 `config/mtrperm/ownership.json` 覆盖成非法内容，例如 `{ this is not json`。
2. 开服，日志应出现
   `[mtrlock] 加载归属数据失败，保留原有内存数据，后续 save 将跳过: <path>`。
3. 用玩家 B 去编辑 / 删除玩家 A 的线路：预期**不再出现** `[mtrlock] 拦截`，操作直接生效（fail-open）。
4. 正常停服：预期出现
   `[mtrlock] 上次加载归属数据失败，跳过保存以避免覆盖损坏文件: <path>`；
   用 `cat config/mtrperm/ownership.json` 确认**内容仍是那份坏 JSON，没有被覆盖**。
   （若内存为空且坏文件非空，还可能看到第二道安全网日志
   `[mtrlock] 内存归属数据为空但文件非空，跳过保存以避免清空: <path>`。）

**服主如何恢复**（二选一）：

- **手动修复**：关服后用编辑器 / `jq` 把 `ownership.json` 修成合法 JSON，再开服。
  加载成功后 `loadFailed` 自动重置为 false，后续 save 恢复正常。
- **删除文件重启**：关服后删除 `config/mtrperm/ownership.json` 再开服。
  文件不存在时 `load()` 会把 `loadFailed` 重置为 false，之后 save 会重新生成空表 `{}`。
  ⚠️ 这等于**放弃全部旧归属记录**（之后任何玩家都能编辑/删除），请谨慎。

**修复后的确认点**：
- 启动日志变成 `[mtrlock] 已加载 N 条归属数据`（不再是加载失败）；
- 编辑 / 删除别人对象时重新出现 `[mtrlock] 拦截 ...`；
- 停服不再出现“跳过保存”warn。

> 第 2 道安全网（保护 2）说明：即使文件没坏，`save()` 在「内存为空 + 文件存在且非空」时也会跳过写盘，
> 避免空内存误清空文件。代价是：合法地删光所有归属记录后再 save 不会清空文件；
> 需要真正清空时请手动删除文件或把它编辑成 `{}`。
> 若希望“文件损坏时一律拒绝所有编辑/删除”（fail-closed），仍需改 `OwnershipData.load()` /
> `PermissionGuard` 的策略；当前仍是 **fail-open**（见 6.6）。

---

### 6.9 客户端拦截（功能 6）的局限

客户端拦截注入在 `org.mtr.mapping.registry.RegistryClient.sendPacketToServer(PacketHandler)`（MTR 客户端所有 C2S 包的公共出口），
`ci.cancel()` 后包不发出，服务端不会广播 `UpdateDataResponse`，因此世界内的主数据不会被本地“乐观更新”改掉。

局限：

- **只挡 MTR GUI 的正常编辑 / 删除**：恶意客户端、改包客户端、或其它模组绕过 MTR 的 `RegistryClient` 直接发包 → 客户端拦截不生效。
  **服务端拦截（功能 5）才是最终权威**：即使绕过了客户端，服务端仍会拒绝并广播修正。
- **依赖 S2C 快照**：`ClientOwnership` 的归属表与“是否 OP 3+”来自服务端 `mtrlock:sync_ownership`。
  若快照未到（网络竞态 / 非本模组服务端 / 断线重连），客户端会按 fail-open 放行；但服务端仍会拦截。
- **只覆盖编辑与删除**：创建（归属里查不到 id）一律放行；平台 / 侧线用父对象判定，依赖客户端 `ChildParents`
  索引（由 `ClientData.sync() → Data.sync()` 填充），与新增子对象 fail-open 的限制一致（见 6.6）。
- 客户端提示使用快捷栏（`sendMessage(Text, true)`），服务端提示仍走聊天栏（`sendMessage(Text, false)`）。

---

### 6.10 客户端拦截的“本地视觉”结论与备选方案（问题 1）

**字节码证据（5 个编辑界面都在发包前改了对象字段）：**

| 界面 | 发送方法 | 发包前已执行的本地修改 |
|---|---|---|
| `EditStationScreen` | `saveData()` | `super.saveData()`→`setName/setColor`；`setZone1`（off 4–37），off 130 才 `sendPacketToServer` |
| `EditRouteScreen` | `saveData()` | `setName/setColor/setRouteNumber/setHidden/setCircularState`，之后再发包 |
| `EditDepotScreen` | `saveData()` | `setName/setColor/...`，之后再发包 |
| `PlatformScreen` | **`onClose2()`** | `savedRailBase.setDwellTime(...)`（off 19–39），off 72 才发包 |
| `SidingScreen` | **`onClose2()`** | 设置串在 `onClose2()` 内，off 333 才发包 |

**但被改的是 `dashboardInstance`，不是世界渲染用的 `instance`：**

- `MinecraftClientData.reset()` 明确 `new` 了**两个不同对象**：`instance` 与 `dashboardInstance`。
- 编辑界面构造请求用的是 `MinecraftClientData.getDashboardInstance()`（5 个界面皆然）；`data` / `savedRailBase` 也来自该实例。
- 世界渲染类（`MainRenderer` / `RenderRails` / `RenderVehicles` / `RenderLifts` / `RenderPIDS` / `RenderRailwaySign` / `RenderSignalBase` / `RenderLiftPanel`）
  引用 `MinecraftClientData`，但**没有任何一个**使用 `getDashboardInstance()` → 它们用 `getInstance()`。
- S2C 的 `PacketUpdateData.update(JsonReader)` 会同时写 `getInstance()` 与 `getDashboardInstance()`；客户端 cancel 后服务端不广播 → `getInstance()` 不更新。

**结论：**

- **世界内视觉状态不会因乐观更新而改变**（当前发包层拦截对“世界”是有效的）。
- **仪表板 GUI 自己的 `dashboardInstance` 会被本地改**；重开 dashboard 会从服务端重新拉取，从而恢复一致（短暂不一致）。
- 因此当前方案可用；只有在“仪表板列表也不能出现本地改动”时，才需要更早的 Screen 层注入。

**备选方案（若 in-game 发现世界也变了，或要求 GUI 也严格不出现本地改动）：**

- **方案 A：逐个 Screen 注入 `saveData()` / `onClose2()` 的 HEAD**（`ci.cancel()` 会在字段被改之前直接跳过）：
  - `EditStationScreen#saveData()`、`EditDepotScreen#saveData()`、`EditRouteScreen#saveData()`（字段 `EditNameColorScreenBase.data`，`protected final T`）；
  - `PlatformScreen#onClose2()`、`SidingScreen#onClose2()`（字段 `SavedRailScreenBase.savedRailBase`，`protected final T`）。
  - objectId 由 `data` / `savedRailBase` 按 `instanceof Route/Station/Depot/Platform/Siding` + `getHexId()` 推导（**不硬编码**）。
  - ⚠️ `onClose2()` 在**任何关闭方式**（含 ESC）都会触发，直接 cancel 会对“没做修改就关界面”的玩家误报，需要额外“是否改过”的判断；因此站台/侧线这条不如保持发包层拦截。
- **方案 B：拦截按钮回调**：MTR 各 Screen 在 `init2()` 里各自 `ButtonWidgetExtension` 绑定，没有统一的 `onPress` 入口（需要逐个界面处理），不推荐。
- **方案 C：cancel 后主动回滚**：重新向服务端请求 dashboard 数据（MTR 的 `PacketRequestData`）刷新 `dashboardInstance`，或直接关闭当前界面。
  比 A 简单，但会多一次往返；也依赖 MTR 内部请求包。

**推荐**：保留当前**发包层拦截**（世界视觉正确、无误报）；若确实要求仪表板 GUI 也严格回滚，再上方案 A（优先做 3 个 `Edit*Screen`，`PlatformScreen/SidingScreen` 因 `onClose2` 语义问题谨慎处理）。

---

## 7. 本环境已完成的验证（非 in-game）

- `./gradlew --offline compileJava`：BUILD SUCCESSFUL
- `./gradlew --offline test`：31 tests, 0 failures, 0 errors
  - `PermissionCheckerTest`：21 用例（功能 4 判定逻辑）
  - `PermissionGuardTest`：10 用例（功能 5 编辑/删除请求解析与拒绝判定）
- 详见 `build/test-results/test/*.xml` 与 README / 提交记录。

---

## 8. 玩家名前缀（阶段 6）：生效范围

> 状态：本环境仅完成 `./gradlew --offline compileJava` + JVM 单元测试
> （`./gradlew --offline test --rerun-tasks`），**未 in-game 验证**。
> 下表为基于 yarn 1.20.1+build.10 的字节码反编译确定的预期范围，请在 PC 上按此验证。

聊天栏 / tab / 头顶名字在 1.20.1 走的是**不同方法或不同侧**，因此团队名前缀由三个 Mixin 提供：

| Mixin | 注入点 | 生效范围 |
|---|---|---|
| `EntityDisplayNameMixin`（服务端） | `PlayerEntity.getDisplayName()` 的 RETURN（运行时用 `instanceof ServerPlayerEntity` 限定服务端） | **聊天栏 + 加入/离开消息 + 死亡消息** |
| `PlayerListNameMixin`（服务端） | `ServerPlayerEntity.getPlayerListName()` 的 RETURN | **tab 列表** |
| `ClientDisplayNameMixin`（客户端） | `PlayerEntity.getDisplayName()` 的 RETURN（运行时用 `instanceof ClientPlayerEntity` 限定客户端） | **头顶名字（nametag，客户端渲染）** |

前缀规则（见 `TeamPrefix`）：取玩家**最早加入**的团队名前 2 个 Unicode code point，
例如“红石铁路局” → `[红石]`；没有任何团队 → `[独立建造者]`。

**头顶名字（客户端渲染）✅ 已带前缀：**
头顶 nametag 由客户端实体渲染、走客户端 `getDisplayName()`，由 `ClientDisplayNameMixin` 加前缀。
客户端拿不到服务端 `TeamData`，因此前缀改由 `ClientTeamPrefix` 基于 S2C 同步的团队快照计算：
`MtrlockClient` 收到 `mtrlock:sync_ownership` 时同时缓存 `teamId → name`
（`ClientOwnership.TEAM_NAMES`，新增）与 `teamId → members`；`ClientTeamPrefix.of(uuid)`
遍历成员表，取包含该玩家、且 **teamId 字典序最小** 的团队名，截前两字包成 `[xx]`；查不到 → `[独立建造者]`。

> 服务端取“最早加入的团队”，但 S2C 不含 `createdAt`，客户端用 **teamId 字典序** 近似，视觉可接受；
> 两个 `getDisplayName` Mixin 守卫互斥（`ServerPlayerEntity` vs `ClientPlayerEntity`），
> 同一侧只会命中一个，不会出现双重前缀。

### 8.1 验证点

| # | 场景 | 操作 | 预期结果 |
|---|---|---|---|
| 1 | 聊天栏前缀 | 有团队的玩家 A 发言 | `<[团队名前两字] A> ...` |
| 2 | 无团队前缀 | 无团队的玩家 B 发言 | `<[独立建造者] B> ...` |
| 3 | tab 列表前缀 | 打开 tab 列表 | A 的名前带 `[团队名前两字]`；B 带 `[独立建造者]` |
| 4 | 加入/离开消息 | A 进出服务器 | 消息中的玩家名带前缀 |
| 5 | 死亡消息 | A 死亡 | 死亡消息中的玩家名带前缀 |
| 6 | 头顶名字 | 观察 A 的头顶 nametag | **带前缀**（客户端渲染，`ClientDisplayNameMixin`；与聊天栏同一团队名前缀语义） |

> 排查：聊天栏没前缀 → 检查 `EntityDisplayNameMixin` 是否在 `mtrlock.mixins.json` 注册、
> 注入是否成功（`defaultRequire=1` 失败会直接崩）；tab 没前缀 → 检查 `PlayerListNameMixin`
> 是否注册（它覆盖原版恒为 `null` 的 `getPlayerListName()`）。

---

## 9. 1.2.3 GUI 验证

### 9.1 构建与测试

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
bash gradlew test --console=plain     # 单元测试（184 → 215+）
bash gradlew build --console=plain    # remap 后的发行 jar: build/libs/mtrlock-1.2.3.jar
```

验收点：
- 单元测试全绿，数量 ≥ 215；
- 持久化格式未变：`ownership.json` / `teams.json` / `shares.json` / `titles.json` 与 1.2.2 可互读；
- `mtrlock.mixins.json` / `mtrlock.client.mixins.json` 未新增条目（本版 GUI 不需要新 Mixin）。

### 9.2 javap：现有 Mixin 注入点回归

1.2.3 **没有新增 Mixin 注入点**（GUI 走 Fabric 自定义通道 + 客户端 `Screen`），
因此这里用 javap 复核现有注入目标方法签名仍然存在、且与 `@Inject(method = "...")` 里写的描述符一致。

```bash
JP="$JAVA_HOME/bin/javap"

# (a) 服务端显示前缀：PlayerEntity.getDisplayName / ServerPlayerEntity.getPlayerListName
$JP -p classes/net/minecraft/entity/player/PlayerEntity.class | grep -i getDisplayName
$JP -p classes/net/minecraft/server/network/ServerPlayerEntity.class | grep -i getPlayerListName

# (b) MTR 目标：从 loom 缓存的 remapped jar 里取
MTR=$(find .gradle/loom-cache/remapped_mods -name 'minecraft-transit-railway-*.jar' | head -1)
mkdir -p /tmp/mtrlock-javap && cd /tmp/mtrlock-javap
jar xf "$OLDPWD/$MTR" org/mtr/core/data/Data.class \
  'org/mtr/core/operation/UpdateDataRequest.class' \
  'org/mtr/core/operation/DeleteDataRequest.class' \
  org/mtr/mod/packet/PacketRequestResponseBase.class
$JP -p org/mtr/core/data/Data.class | grep 'sync()'
$JP -p org/mtr/core/operation/UpdateDataRequest.class | grep 'update()'
$JP -p org/mtr/core/operation/DeleteDataRequest.class | grep 'delete('
$JP -p org/mtr/mod/packet/PacketRequestResponseBase.class \
  | grep -E 'runServerOutbound|runServer'
```

预期：`Data.sync()`、`UpdateDataRequest.update()`、`DeleteDataRequest.delete(Simulator)`、
`PacketRequestResponseBase.runServerOutbound(...)` / `runServer(...)` 均存在，
与 `mtrlock.mixins.json` 里的 `@Inject(method = "...")` 描述符一致；启动日志无 `Mixin apply failed`。

### 9.3 团队 GUI 手动验证（双客户端 + 服务端）

前置：两个客户端 A / B 均安装 mtrlock 1.2.3；A 为普通玩家，B 为普通玩家，再准备一个 OP 3+ 账号。

| # | 操作 | 预期 |
|---|---|---|
| 1 | A `/mtrlock gui` | 打开团队 GUI；“我的团队”为空 |
| 2 | A 创建团队“红石铁路局” | 成功；命令 `/team list` 也能看到同名团队（GUI 与命令一致） |
| 3 | A 创建第 2、3 个团队 | 成功；第 4 个提示失败（每人最多 3 个） |
| 4 | B 在 GUI 申请加入“红石铁路局” | A 的“待处理”出现该申请；`/team info` 也能看到 |
| 5 | A 在“待处理”点批准 | B 成功入队；成员数 +1 |
| 6 | A 在“邀请成员”选在线玩家 B2 | B2 的“待处理”出现邀请；点接受后入队 |
| 7 | A 在“成员管理”对 B 点踢出 | B 离队；B 之前分享给该团队的对象在 GUI“分享管理”中消失 |
| 8 | A 把对象分享给团队，再转让队长给 B | B 成为队长；A 保留成员身份 |
| 9 | B（队长）在“危险操作”退出团队 | 退出按钮禁用（队长不能退）；解散按钮可用，二次确认后解散 |
| 10 | 重复第 4 步两次 | 第二次提示“已申请过”，界面不变（无乐观更新） |
| 11 | 用未安装客户端的账号执行 `/mtrlock gui` | 聊天栏提示“需要安装 mtrlock 客户端”，`/team ...` 命令仍可用 |

### 9.4 称号 GUI 手动验证

| # | 操作 | 预期 |
|---|---|---|
| 1 | 普通玩家 `/mtrlock gui title` | 拒绝并提示“需要 OP 权限等级 3”；不打开界面 |
| 2 | OP 3+ `/mtrlock gui title` | 打开称号界面，列出在线玩家 |
| 3 | 搜索并选中玩家，输入中文称号后保存 | 该玩家前缀变为 `[称号]`（聊天栏 / tab / 头顶） |
| 4 | 输入 17 个字符 | 保存失败并提示非法；旧称号不变 |
| 5 | 点“清除称号” | 称号被清除，回落到团队前缀 / 无前缀 |
| 6 | 观察颜色区 | 16 原版色 / HEX / 最近使用置灰，标注“1.2.4 开放”；不影响保存纯文本 |

### 9.5 协议与限流

- 客户端与服务端版本不一致（例如只升级一端）时：`/mtrlock gui` 提示协议不匹配，界面不打开；
- 连续快速点击 GUI 按钮：超过令牌桶额度后提示“操作过于频繁，请稍后再试”，数据不变；
- 服务端日志可用关键字过滤：`已为 <uuid> 打开 TEAM GUI` / `TITLE GUI`。

### 9.6 边界清单

- 未装客户端：命令可用，GUI 入口提示安装客户端；
- 无权限：普通玩家打不开称号 GUI，称号操作返回 `NEED_ADMIN`；
- 团队满：创建第 4 个团队失败；批准 / 接受邀请时对方已达上限失败；
- 重复申请 / 重复邀请 / 重复分享：均提示失败且不产生重复数据；
- 非队长：踢人 / 转让 / 解散在 GUI 中返回失败（OP 3+ 兜底成功）。

---

## 10. 1.2.4 称号颜色验证

### 10.1 构建、测试与 Java target

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew test --console=plain     # 232 → 328，全绿
./gradlew build --console=plain    # build/libs/mtrlock-1.2.4.jar

# Java target 仍为 17
grep -nE "release = 17|sourceCompatibility|targetCompatibility" build.gradle

# 产物字节码版本应为 61（Java 17）
unzip -p build/libs/mtrlock-1.2.4.jar com/mtrstar/lock/team/ColorParser.class > /tmp/ColorParser.class
javap -verbose -cp /tmp ColorParser 2>/dev/null | grep -E "major version"
```

### 10.2 旧数据升级（必须无感）

1. 取 1.2.3 的 `config/mtrperm/titles.json`（`{"uuid": "称呼"}` 字符串格式）放入 1.2.4 服务端配置目录；
2. 启动服务端 → 称号正常显示、`color = null`（无颜色），日志无 `加载称呼数据失败`；
3. OP 给其中一条设置颜色并关闭服务端 → 文件变为
   `{"uuid": {"text": "...", "color": "#rrggbb"}}`，再启动读取正常。

### 10.3 四处显示一致性（双客户端 + 服务端）

| # | 操作 | 预期 |
|---|---|---|
| 1 | OP 执行 `/mtrlock title Alice "红石局长" red` | Alice 的聊天栏 / tab / 头顶均为 `[红石局长]` 且为 `#ff5555`；`/mtrlock title` 查看自己显示颜色 |
| 2 | OP 执行 `/mtrlock title color Alice "&a"` | 颜色变为 `#55ff55`；文本不变 |
| 3 | OP 执行 `/mtrlock title color Alice "#123456"` | 颜色变为 `#123456`（HEX 大小写不敏感） |
| 4 | OP 执行 `/mtrlock title color Alice "&x&f&f&5&5&5&5"` | 颜色变为 `#ff5555` |
| 5 | OP 执行 `/mtrlock title color Alice reset` | 颜色清除、文本保留 |
| 6 | OP 执行 `/mtrlock title Alice "新称呼"`（不带颜色） | 文本更新，**保留**当前颜色 |
| 7 | OP 执行 `/mtrlock title clear Alice` | 称呼与颜色一起消失 |
| 8 | 给 Alice 一个团队前缀后再设带色称呼 | 显示称呼（带色）而非团队前缀；清除称呼后显示团队前缀（**不带色**） |
| 9 | 普通玩家执行 `/mtrlock title Alice x red` / `title color` / `gui title` | 全部拒绝（NEED_ADMIN），数据不变 |

### 10.4 Placeholder

| # | 配置 | 预期 |
|---|---|---|
| 1 | StyledChat 用 `%mtrlock:title%` | 纯文本称呼，无颜色标签 |
| 2 | StyledChat 用 `%mtrlock:title_colored%`（`display.json` 默认 minimessage） | `<#rrggbb>称呼`，StyledChat 渲染出颜色 |
| 3 | `display.json` 改为 `{"placeholderFormat":"legacy"}` 后重启 | `§x§r§r§g§g§b§b称呼` |
| 4 | 无称呼 | 两个占位符都为空串，不输出悬空颜色标签 |
| 5 | `%mtrlock:prefix%` / `%mtrlock:team%` | 行为不变（纯文本、优先级不变） |

### 10.5 GUI 颜色

1. `/mtrlock gui title`（OP 3+）→ 颜色区不再是灰度块，点击 16 色任一块会高亮；
2. HEX 输入框输入 `#ffaa00` → 点“应用HEX” → 高亮切换、预览变黄；
3. 预览区三行（聊天栏 / tab / 头顶）随文本与颜色实时变化；
4. “最近使用”出现刚用过的颜色，点击可复用；
5. “重置颜色” → 颜色清空（服务端回全量快照后确认）；
6. “保存” → 服务端校验通过后聊天栏 / tab / 头顶同步变色；
7. 重新打开 GUI、切换目标玩家 → 当前称号与颜色从快照载入。

### 10.6 版本与边界

- **1.2.3 客户端连 1.2.4 服务端**：`/mtrlock gui` 提示协议版本不匹配、界面不打开；
  但命令、聊天栏、tab、头顶名字、Placeholder **全部正常**（`sync_ownership` 颜色表是可忽略尾段）。
- **1.2.4 客户端连 1.2.3 服务端**：同样 GUI 不匹配；显示按 1.2.3 行为（无颜色）。
- **坏文件**：把 `titles.json` 改成非法 JSON → `loadFailed`，旧内存保留、`save()` 不覆盖坏文件；
  修复或删除文件后重启恢复。
- **未装客户端**：命令全部可用；`/mtrlock gui` 提示需要安装客户端。

---

## 11. 1.3.0 区域方块保护验证

### 11.1 构建、测试与 Java target

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew test --offline --console=plain    # 328 → 413，全绿
./gradlew build --offline --console=plain   # build/libs/mtrlock-1.3.0.jar

# Java target 仍为 17
grep -nE "release = 17|sourceCompatibility|targetCompatibility" build.gradle

# 产物字节码版本应为 61（Java 17）
unzip -p build/libs/mtrlock-1.3.0.jar com/mtrstar/lock/protect/SpatialIndex.class > /tmp/SpatialIndex.class
javap -verbose -cp /tmp SpatialIndex 2>/dev/null | grep -E "major version"

# 1.3.0 新增测试类应全绿
ls build/test-results/test/ | grep -E "ObjectRange|SpatialIndex|ProtectionRanges|ProtectionConfig|BlockProtection|ProtectionCoexistence"
```

### 11.2 场景清单（双客户端 + 服务端）

> 前置：A、B 两名非 OP 玩家；另备一名 OP 3+。A 先创建对象。坐标用 F3 查看。
> **方块破坏**用左键挖，**方块放置**用手持方块右键点地面。

| # | 场景 | 操作 | 预期结果 |
|---|---|---|---|
| 1 | 车站范围内破坏被拒 | A 建一个车站；B 走进矩形内挖任意方块 | 方块**不消失**；B 收到红字 `你不能在这里破坏方块…`；服务端日志有 `拦截 <B-UUID> 在 (x, z) 的方块破坏，保护对象: [station:<HEX>]` |
| 2 | 车站范围外破坏放行 | B 走到矩形**外**挖方块 | 正常破坏，无提示、无日志 |
| 3 | 创建者放行 | A 在自己车站矩形内挖 / 放方块 | 正常，无拦截 |
| 4 | 团队成员放行 | A 与 B 建团队、A 把车站分享给该团队；B 在矩形内破坏 | 正常放行（与编辑保护同一套判定） |
| 5 | OP 3+ 放行 | OP 在 B 的车站矩形内破坏 / 放置 | 正常放行 |
| 6 | 车站范围内放置被拒 | B 手持方块在 A 车站矩形内右键放置 | 方块**放不下**；B 收到红字 `你不能在这里放置方块…` |
| 7 | 车站范围外放置放行 | B 在矩形外放置 | 正常放下 |
| 8 | 车站删除后不再保护 | A 删除该车站；B 回到原矩形内破坏 | 正常放行（索引随删除立即移除） |
| 9 | 车厂同车站 | 对 depot 重复 #1–#8 | 同车站，`保护对象: [depot:<HEX>]` |
| 10 | 站台 / 侧线随父对象 | 在 A 车站里放一个站台、在 A 车厂里放一条侧线；B 在站台 / 侧线所在方块处破坏 | 被拒绝（位置被父车站 / 车厂的矩形覆盖，**没有** `platform:` / `siding:` 保护对象） |
| 11 | **重启后仍保护（关键）** | 停服 → 开服 → B 直接在 A 的车站矩形内破坏 | **仍被拒绝**（`Data#sync()` 在存档加载完成后自动重建索引）；`/mtrlock protect status` 显示索引对象数 > 0 |
| 12 | 改范围后索引更新 | A 把车站矩形改大；B 在新增区域内破坏 | 被拒绝（`UpdateDataRequest#update()` RETURN 重建） |
| 13 | 无归属对象 fail-open | 手工删除 `ownership.json` 里某车站条目并重启；B 在该车站矩形内破坏 | 放行（无归属 → 不保护） |
| 14 | 总开关 | `enabled=false` → `/mtrlock protect reload` → B 在矩形内破坏 | 放行 |
| 15 | 车站开关 | `protectStations=false`（车厂保持 true）→ `reload` | 车站内放行；车厂内仍被拒 |
| 16 | 范围扩张 | `expandBlocks=5` → `reload`；B 在矩形外 3 格处破坏 | 被拒绝（扩张生效）；扩大站外 10 格处则放行 |
| 17 | 提示开关 | `notifyPlayer=false` → `reload`；B 范围内破坏 | 仍被拒绝，但**不弹提示**；日志仍有 `拦截` |
| 18 | 命令权限 | 非 OP 执行 `/mtrlock protect status|reload|rebuild` | 红字 `需要 OP 权限等级 3 才能使用该命令`，无副作用 |
| 19 | 命令功能 | OP 执行 `status` / `reload` / `rebuild` | `status` 列出开关与索引规模；`reload` 重读配置；`rebuild` 重建索引并显示对象数 / chunk 数 |
| 20 | 配置坏文件保护 | 关服 → 把 `protection.properties` 写成含非法 Unicode 转义的内容（如 `enabled=\uZZZZ`）→ 开服 | 启动报 `加载保护配置失败，保留当前设置，后续 save 将跳过`；内存用默认值；停服时**不覆盖**坏文件 |
| 21 | 未装客户端仍可用 | 纯服务端（客户端不装 mtrlock）跑 #1 / #6 / #18 / #19 | 保护与命令全部照常（方块保护是纯服务端逻辑） |
| 22 | 编辑保护回归 | 重复旧场景 #3 / #6 / #13 / #14 | 编辑 / 删除拦截行为与 1.2.4 完全一致（两套保护共用 `PermissionChecker`，互不干扰） |

### 11.3 本版「不做」的确认（应为放行，不是 bug）

- **线路（route）**没有坐标 → 线路不参与方块保护。
- **爆炸 / 活塞 / 火焰 / 水流 / 命令 / 其它模组**等间接改变方块 → 不受保护（本版范围明确限定）。
- **网页 dashboard 直接改数据**：通常下一次 `sync`（任意对象增删改）或重启会自动纠正；
  在没有任何 `sync` 的窗口期内改了范围，用 `/mtrlock protect rebuild` 立即纠正。

### 11.4 日志关键字一览（1.3.0）

```
[mtrlock] 拦截 <UUID> 在 (<x>, <z>) 的方块破坏，保护对象: [station:<HEX>]
[mtrlock] 拦截 <UUID> 在 (<x>, <z>) 的方块放置，保护对象: [depot:<HEX>]
[mtrlock] 区域方块保护: 启用 (车站 保护 / 车厂 保护 / 扩张 0 格)     ← 配置加载
[mtrlock] 加载保护配置失败，保留当前设置，后续 save 将跳过: <path>   ← 坏文件
[mtrlock] 上次加载保护配置失败，跳过保存以避免覆盖损坏文件: <path>   ← 坏文件 + 停服
[mtrlock] 保护范围过大，跳过空间索引: <objectRange> (NxM chunks)     ← 安全阀（正常不会出现）
```

### 11.5 快速排查

| 现象 | 先查 |
|---|---|
| 范围内破坏没被拦 | `/mtrlock protect status` 看 `enabled` / `protectStations|Depots`；确认该对象在 `ownership.json` 里有归属记录；确认刚重启过且索引对象数 > 0（否则执行 `/mtrlock protect rebuild`） |
| 范围外也被拦 | 检查 `expandBlocks` 是否设大了；用 F3 核对对象矩形的两个对角点 |
| 重启后不保护 | 看启动日志里配置是否加载成功；`/mtrlock protect status` 的「服务端数据」是否「已就绪」；必要时 `/mtrlock protect rebuild` |
| 放置拦不住 | 确认是「手持方块右键放置」，且 `UseBlockCallback` 未被其它模组取消；本版不覆盖发射器 / 活塞等间接放置 |
| 提示没出现 | `notifyPlayer=false`；或客户端未装 mtrlock（服务端聊天栏仍会有红字） |

---

## 12. 1.4.0 线路引用自动清理验证

### 12.1 构建、测试与 Java target

```bash
./gradlew --offline test     # 479 个用例全过（1.3.0 为 413，本版 +66）
./gradlew --offline build    # 产物 build/libs/mtrlock-1.4.0.jar
```

- Java target 仍为 17（`build.gradle` 的 `options.release = 17`、`sourceCompatibility` / `targetCompatibility`）。
- 新增单测：`RemovedRefsDataTest`（持久化往返 / 坏文件 / loadFailed / 30 天清理 / 节流落盘 / 覆盖不追加）、
  `RouteRefReconcilerTest`（真实 MTR `ClientData` + `sync()` 夹具：失权 / 恢复 / 无 owner / 孤儿 /
  幽灵清理 / 多线路 / 横跳 / loadFailed）、`PermissionCheckerUuidEditTest`（UUID 重载与权限矩阵）、
  `RefsNoticesTest`、`RefsCommandTest`。

### 12.2 lang key 与注入点回归（可用 javap / jar 复核）

```bash
unzip -p build/libs/mtrlock-1.4.0.jar mtrlock.mixins.json
# DataChildParentMixin 仍在列表里；本版没有新增 Mixin 类

javap -p build/classes/java/main/com/mtrstar/lock/mixin/DataChildParentMixin.class | grep mtrlock
# private void mtrlock$indexChildParents(CallbackInfo);
# private void mtrlock$rebuildProtectionIndex(CallbackInfo);
# private void mtrlock$reconcileRouteRefs(CallbackInfo);   ← 1.4.0 追加的第三个
```

- `DataChildParentMixin` 现在有**三个** `@Inject(method = "sync()V", at = @At("RETURN"))`：
  `mtrlock$indexChildParents` / `mtrlock$rebuildProtectionIndex` / `mtrlock$reconcileRouteRefs`；
  前两个的注解与描述符**未改动**。
- 客户端 `ClientData` 也走这个注入点，但对账有 `instanceof Simulator` 守卫。

### 12.3 场景清单（双客户端 + 服务端）

准备：A、B 两个客户端；A 建车站 S，B 建线路 R 并引用 S 里的站台 P1（再放一个 B 自己的车站站台 P2 做对照）。

| # | 步骤 | 预期 |
|---|---|---|
| 1 | A 把车站 S 分享给团队 T，B 在 T 里；B 用 P1 建线路 R | 正常：R 引用 P1 + P2；`removed_refs.json` 为空（或不存在） |
| 2 | A 撤销对 T 的分享（或 B 退出 T），等下一次 `sync`（任何对象增删改 / 重新拉取数据都会触发） | R 的 `routePlatformData` 里 **P1 被移除**、P2 保留；`removed_refs.json` 出现 `route:...` → `{platformId: P1, stationObjectId: station:S}`；MTR 的线路界面里 R 少了一站 |
| 3 | 提示 | **B 在线** → B 的聊天栏出现 `[mtrlock] 你引用的车站权限已被撤销…`（一次列全，不刷屏）；服务器日志有 `线路引用移除（owner 已失去站台权限）` |
| 4 | A 重新分享给 T（或 B 重新加入 T），等下一次 `sync` | P1 **自动加回** R（会追加到线路末尾，不还原原站序）；账本记录被删除；B 聊天栏出现恢复提示；日志有 `线路引用恢复` |
| 5 | 反复横跳：重复 #2 / #4 各 5 次 | 每次都能正确移除 / 加回；`removed_refs.json` 里同一 `(route, platform)` **始终只有一条记录**（覆盖不追加） |
| 6 | 停机 → 重启服务器 | `removed_refs.json` 正常加载（日志 `已加载 N 条线路的 M 条引用移除记录`）；**未恢复的引用仍处于移除状态**，不会被自动加回 |
| 7 | 关服 → 把 `removed_refs.json` 改成坏 JSON（如 `{ broken`）→ 开服 | 日志 `加载引用账本失败，本轮对账将跳过，后续 save 也将跳过`；**本轮对账不做任何移除**（已有引用保持原样）；停服时该坏文件**不被覆盖**；`/mtrlock refs status` 显示「上次加载失败」 |
| 8 | 30 天清理：把某条记录的 `removedAt` 改成 31 天前 → 重启服务器 | 记录被清理（日志 `已清理 N 条超过 30 天的线路引用记录`）；该站台此后视为永久移除，权限恢复也不再自动加回 |
| 9 | 坏数据 / 边界：手工把 `removedAt` 删掉（或写 0） | 按「未知时间」处理，**不清理** |
| 10 | 无归属线路 | 手工删掉 `ownership.json` 里某线路条目并重启：该线路的引用**不做任何移除**（fail-open），日志无相关记录 |
| 11 | 车站已删（孤儿站台） | 删掉车站 S（站台变成孤儿，`area == null`）：引用里的 P1 **被自动移除**并记一笔 `stationObjectId = null` 的账（视为失去权限）；若 P1 此前已有记录，则**保持移除、不重复记账**；重建车站后可用 `/mtrlock refs restore` 加回 / 等 30 天清理 |
| 11b | 站台对象已从存档消失 | 删掉站台本身：MTR 自己的 `sync` 会剪掉该条目，对账**不产生任何改动**、不记账（fail-open） |
| 12 | 命令权限 | 非 OP 执行 `/mtrlock refs status|list|restore` | 红字 `需要 OP 权限等级 3 才能使用该命令`，无副作用 |
| 13 | 命令功能 | OP 执行 `status` / `list` / `list route:...` | `status` 显示条数 / 线路数 / 加载状态 / 文件路径 / 保留期限；`list` 逐条显示 platformId、父车站、移除时间，可按线路过滤 |
| 14 | 手动恢复 | OP 执行 `/mtrlock refs restore route:<HEX> <platformId>` | 站台立即加回线路、账本记录删除并**立即落盘**（不必等 5 秒 debounce）；线路 / 站台 / 服务端数据不存在时明确报错、不做半截操作 |
| 15 | 未装客户端仍可用 | 纯服务端（客户端不装 mtrlock）跑 #2 / #4 / #12 / #13 | 移除 / 加回与命令照常（对账是纯服务端逻辑）；聊天提示只在 owner 的**服务端**聊天栏出现 |
| 16 | 编辑 / 删除保护回归 | 重复 1.3.0 的场景 #3 / #6 / #13 / #14 | 编辑 / 删除拦截行为与 1.3.0 完全一致（共用 `PermissionChecker`，UUID 重载不影响在线玩家路径） |
| 17 | 区域方块保护回归 | 重复 1.3.0 的场景 #1 / #6 / #11 / #22 | 方块保护行为与 1.3.0 完全一致（第三个注入器与保护索引互不依赖） |

### 12.4 本版「不做 / 刻意取舍」的确认（不是 bug）

- **滞后一个 `sync` 周期**：移除发生在 `Data#sync()` 返回之后，`Platform.routes` / 车厂路径缓存
  要等下一次 `sync` 才一致。设计上**不做守卫式二次 sync**。
- **加回不还原站序**：加回的站台追加到线路末尾。
- **OP 3+ 不算「有权限」**：owner 是离线 UUID，OP 等级推不出来；对账里管理员豁免固定关闭。
  非创建者的 OP 对别人的线路引用**不参与判定**。
- **GUI 本版不做**：只有命令 + 聊天提示 + 日志。
- **网页 dashboard 直改数据**：会在下一次 `sync`（任意对象增删改 / 拉数据）自动收敛；
  也可用 `/mtrlock refs restore` 手动纠正。

### 12.5 日志关键字一览（1.4.0）

```
[mtrlock] 引用账本不存在（首次启动），按空账本处理: <path>
[mtrlock] 已加载 N 条线路的 M 条引用移除记录
[mtrlock] 加载引用账本失败，本轮对账将跳过，后续 save 也将跳过: <path>      ← 坏文件（WARN/ERROR）
[mtrlock] 上次加载引用账本失败，跳过保存以避免覆盖损坏文件: <path>          ← 坏文件 + 落盘
[mtrlock] 已清理 N 条超过 30 天的线路引用记录
[mtrlock] 已保存 N 条线路的 M 条引用移除记录到 <path>                      ← 节流落盘
[mtrlock] 线路引用移除（owner 已失去站台权限）: route:<HEX> → platform <id>
[mtrlock] 线路引用恢复（owner 权限已恢复）: route:<HEX> → platform <id>
[mtrlock] 线路引用记录清理（线路或站台已不存在）: route:<HEX> → platform <id>
```

### 12.6 快速排查

| 现象 | 先查 |
|---|---|
| 失权后引用没被移除 | 该线路在 `ownership.json` 里有创建者记录吗（无归属 → fail-open 跳过）？站台的 `area` 是否为 null（孤儿 / 车站已删 → fail-open）？是否刚发生过一次 `sync`（可任意增删改一个对象或重开 dashboard 触发）？`removed_refs.json` 是否损坏（`loadFailed` 会整体跳过）？ |
| 权限恢复后没加回 | 记录是否还在账本里（`/mtrlock refs list route:<HEX>`）？记录是否已超 30 天被清理？站台是否已从存档删除？ |
| 聊天提示没出现 | owner 是否**在线**（离线只写日志）；语言文件是否加载（`zh_cn` / `en_us` 的 `mtrlock.refs.*`）；用 `/mtrlock refs status` 看账本 |
| 账本文件不更新 | 节流是「变更后 5 秒或 10 次对账」；可用 `/mtrlock refs restore` 或正常停服触发落盘；坏文件时**永远不会**写盘（这是刻意的） |
| 记录膨胀 | 同一 `(route, platform)` 是覆盖式记录；若发现同站台多条，检查 `platformId` 是否真的不同（站台被删后重建会拿到新 id） |
