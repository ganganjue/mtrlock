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
