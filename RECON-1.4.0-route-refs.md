# 1.4.0「线路引用自动清理」侦察报告

> 目标：`minecraft-transit-railway-FABRIC-4.0.0+1.20.1.jar`（`gradle.properties` 锁定版本）。
> 方法：javap 反编译 + 二进制常量池检索；**未改动任何仓库文件、未跑构建**。
> 结论先行：方案整体可行，但 **Q3（sync 周期滞后 + 改动被 MTR 自动存盘）** 与
> **Q7（mtrlock 没有按天清理可复用）** 需要补设计。

---

## 目录

| 编号 | 问题 | 结论一句话 |
|---|---|---|
| 1 | Route 的 owner 字段 | **没有**，4.0.0 / 4.0.5 都没有 → 只能走 `ownership.json` |
| 2 | routePlatformData 的原地移除 | **可以原地改**（活引用），无 getter 读 platformId，但可用 public `platform` 绕开 |
| 3 | MTR 对「线路少站台」的反应 | 不会崩、会自己剪 null 站台；但反向索引/路径缓存滞后一个 sync，且改动会被存盘 |
| 4 | 平台 → 车站反查 | 走运行时 `Platform.area`（public），孤儿返回 null → fail-open |
| 5 | `Data#sync()` 触发频率 | 全 core 9 个调用点；不是每 tick；登录/退服不触发，但「拉数据」会 |
| 6 | `canEdit(UUID, objectId)` 重载 | **小改动（~5 行）**，纯逻辑重载已存在 |
| 7 | 快照与 removed_refs 持久化 | 模板完备（ShareData + OwnershipData）；**无按天清理可复用** |
| 8 | 与 1.3.0 sync 钩子合并 | 可安全追加第三个 `@Inject`；需自给自足 + re-entrancy 守卫 |

---

## 【1】Route 的 owner 字段 —— 没有

**结论**：MTR 4.0.0 的 `Route` 完全没有 owner 概念，`RouteSchema`、`NameColorDataBase`（及其 Schema）也都没有。
→ 设计里的「优先 `Route.owner`，没有则用 `ownership.json`」实际会**永远走 ownership.json**，「优先」分支是死代码。

**证据**

```text
$ javap -p org/mtr/core/data/Route.class
  # 只有 getRoutePlatforms / getRouteNumber / getHidden / getRouteType / getRouteTypeKey /
  # getCircularState / getDestination / setRouteNumber / setHidden / setCircularState /
  # setRouteType / getOBARouteElement / isValid ... 无 owner

$ javap -p org/mtr/core/generated/data/RouteSchema.class
  protected RouteType routeType;
  protected String routeNumber;
  protected boolean hidden;
  protected Route$CircularState circularState;
  protected final ObjectArrayList<RoutePlatformData> routePlatformData;   # 全部字段，无 owner

$ javap -p org/mtr/core/data/NameColorDataBase.class
  public final String getHexId(); public final long getId(); public final String getName();
  public final int getColor(); public final String getColorHex(); ...        # 无 owner

$ javap -p org/mtr/core/generated/data/NameColorDataBaseSchema.class
  protected final long id; protected final TransportMode transportMode;
  protected String name; protected long color; protected final Data data;    # 无 owner

$ grep -rli "owner" --include=*.class org/mtr/core
org/mtr/core/generated/WebserverResources.class    # 仅 1 个文件，网页静态资源字符串，非数据模型
```

顺手核对了本地缓存的 **4.0.5**：`RouteSchema` 同样只有 `routePlatformData`，**也没有 owner**。

**坑**：设计文档里「优先 Route.owner」这条可以删掉；`canEdit` 的输入只能是 `ownership.json` 的创建者 UUID。

---

## 【2】routePlatformData 的原地移除 —— 可以原地改；读 platformId 没有 getter

**结论**：`getRoutePlatforms()` 返回**活 `ObjectArrayList`**（`getfield` + `areturn`），可直接 `removeIf` / `add`。
MTR 自己在 `Data.sync()` 里就是用 `route.getRoutePlatforms().removeIf(...)` 剪枝的。
没有 `addPlatform` / `removePlatform` / `setRoutePlatformData`。
**`RoutePlatformData.platformId` 是 `protected final long` 且无 getter**，但可以通过公开的 `RoutePlatformData.platform` 字段绕开。

**证据**

```java
// Route.getRoutePlatforms() —— 直接返回字段，不是拷贝
public ObjectArrayList<RoutePlatformData> getRoutePlatforms();
   0: aload_0
   1: getfield routePlatformData
   4: areturn                                  // ← 活引用

// RouteSchema
protected final ObjectArrayList<RoutePlatformData> routePlatformData;   // final → 无 setter

// RoutePlatformData
public Platform platform;                              // ← public 字段（由 sync 解析）
public RoutePlatformData(long platformId);             // ← public 构造，可重建条目
public Platform getPlatform();                         // ← 唯一公开的读取入口

// RoutePlatformDataSchema
protected final long platformId;                       // 无 getPlatformId()

// RouteSchema.updateData(ReaderBase)：整体 clear + 逐条 add
iterateReaderArray("routePlatformData", routePlatformData::clear, this::lambda$updateData$4)
   lambda$updateData$4: routePlatformData.add(new RoutePlatformData(reader));
```

**移除 / 加回（推荐写法）**

```java
// 移除：platform 由 Data.sync() 解析，sync RETURN 时非 null 的都可安全取 id
route.getRoutePlatforms().removeIf(rpd -> rpd.getPlatform() != null
        && rpd.getPlatform().getId() == platformId);

// 加回
route.getRoutePlatforms().add(new RoutePlatformData(platformId));
```

**坑**

- 读 `platformId` **不用新增 `@Accessor` Mixin**：`Data.sync()` 在 RETURN 之前已跑完 `writePlatformCache`（解析 `platform`）
  **并剪掉了 `platform == null` 的条目**（见 Q3），所以钩子处 `getPlatform()` 必非 null，拿 `getPlatform().getId()` 即可。
  **本功能不需要新增任何 Mixin 类**，也就不会碰 `targets + @Pseudo` 那条约束。
- `RouteSchema.updateData()` 是 **clear + 重建**：owner 每次编辑线路（改名/改色/调站序），客户端 JSON 会把整份
  `routePlatformData` 重新灌进来 → 我们的临时移除会被覆盖。因为 `UpdateDataRequest.update()` 内部会调 `Data.sync()`，
  而我们对账挂在 `sync() RETURN`，所以**同一次调用内就会被重新移除**（这点设计是对的）；
  但客户端自己那份 route 副本仍含该站台，直到它重新拉取全量数据。
- **序列化**：`serializeRoutePlatformData` 直接遍历这个 list；改它就是改「发往客户端的权威数据」。

---

## 【3】MTR 对「线路少站台」的反应 —— 不会崩；但滞后一个 sync 周期 + 会被存盘

**结论**：`Data.sync()` 的顺序是「清空并重建 `Platform.routes` / `routeColors` → 解析并剪枝各 route 的 platform →
重建 depot 路由/路径缓存」。我们的移除发生在**整个 sync 返回之后**，所以：

1. **不会 NPE** —— `lambda$sync$8` 已把 `platform == null` 的条目全部 `removeIf` 掉，
   Siding / Depot 后续遍历到的条目 `platform` 都非 null。
2. **`Platform.routes` / `routeColors` 会脏一个周期**（里面仍含被我们移除的那条 route）。
3. **`Depot` 的 route / path 缓存也早于我们重建**，一个周期内仍按旧站表跑车。
4. MTR 不知道「权限」这件事，**不会自己处理**；要么接受一个 sync 周期的滞后，要么 mtrlock 手动再触发刷新。

**证据**

```java
// Data.sync() 顺序（字节码偏移）
206: platforms.forEach(data -> lambda$sync$5(platform))   // 清空 platform.routes / routeColors
219: routes.forEach(data    -> lambda$sync$8(route))
232: depots.forEach(data    -> lambda$sync$9(depot))      // writeRouteCache + writePathCache

// lambda$sync$5(Platform)
platform.routes.clear();
platform.routeColors.clear();
platformIdToPosition.put(platform.getId(), platform.getMidPosition());

// lambda$sync$8(Route)
route.depots.clear();
route.getRoutePlatforms().forEach(rpd -> lambda$null$6(route, rpd));
      // lambda$null$6 → rpd.writePlatformCache(route, platformIdMap)
      //   writePlatformCache: this.platform = platformIdMap.get(platformId);
      //                       if (platform != null) { platform.routes.add(route); platform.routeColors.add(route.getColor()); }
route.getRoutePlatforms().removeIf(rpd -> lambda$null$7(rpd));   // ← MTR 自己剪 null 站台
      // lambda$null$7: return rpd.platform == null;

// lambda$sync$9(Depot)
depot.writeRouteCache(routeIdMap);
depot.writePathCache();
```

`getRoutePlatforms()` 的消费点（会读到我们改动后的列表）：

- `Depot.writeRouteCache`
- `Siding.generatePathDistancesAndTimeSegments` / `getDepartures` / `iterateArrivals`
- `SimplifiedRoute.<init>`、`VehicleExtraData`、`oba/ArrivalAndDeparture`、`operation/ArrivalResponse`、`map/Route`

**坑（本方案最大的两个）**

- **反向索引 / 缓存滞后**：若要求「移除后当前 tick 就一致」，得在 RETURN 里**再调一次 `data.sync()`**
  （`public void sync()` 可调），但会**递归进入自己的注入点**，必须加 re-entrancy 守卫（ThreadLocal / 布尔）。
  或者自己顺手 `platform.routes.remove(route)`（`public final ObjectAVLTreeSet`，可改），
  但 `routeColors` 是 `IntAVLTreeSet`，多线路同色时不能安全删。
- **会被存盘**：`Simulator.save(boolean)` 会保存 `fileLoaderRoutes`，`FileLoader.save` 只写「脏」对象
  （按序列化 hash 判定，`FileLoader.save` → `writeDirtyDataToFile`）。我们改了 `routePlatformData` →
  route 变脏 → **下一个 autosave 就会把「少了一个站台」的线路写进存档**。
  这不是永久丢数据（`removed_refs.json` 是恢复源），但如果该文件损坏 / 被删，移除就变成**永久性丢站台**。
  缓解：坏文件保护 + 恢复记录绝不主动丢 + 车站/线路删除时清理孤儿记录。

---

## 【4】平台 → 车站反查 —— 走运行时 `Platform.area`；孤儿天然 fail-open

**结论**：`PlatformSchema extends SavedRailBase<Platform, Station>`，`SavedRailBase.area` 是 **`public U area`**，
由 `Data.sync()` 的 `mapAreasAndSavedRails(platforms, stations)` 按几何包含关系挂好，**在 sync RETURN 之前完成**。
序列化数据里**没有** stationId，也没有 `getStationId()`。反查有两条路，生产上推荐第 2 条（不依赖注入器顺序）。

**证据**

```java
public abstract class PlatformSchema extends SavedRailBase<Platform, Station>   // ← 泛型第二参 = 父类型
public abstract class SavedRailBase<T, U extends AreaBase<U,T>> { public U area; }   // ← public 字段

// Data 字段可见性（全部 public final）
public final Long2ObjectOpenHashMap<Platform> platformIdMap;
public final Long2ObjectOpenHashMap<Station>  stationIdMap;
public final ObjectArraySet<Platform> platforms;
public final ObjectArraySet<Station>  stations;
```

```java
// 路径 1：mtrlock 现有 ChildParents（方向：child → parent）
ChildParents.get("platform:<HEX>")  →  "station:<HEX>"      // 无此键返回 null

// 路径 2：直接用 MTR 数据（推荐）
Platform p = data.platformIdMap.get(platformId);
String stationObjectId = (p != null && p.area != null)
        ? "station:" + Utilities.numberToPaddedHexString(p.area.getId())
        : null;
```

**坑**：`platform.area == null`（孤儿站台，所属车站已删或几何不匹配）→ 两条路都返回 null →
按设计 **fail-open 放行**，与「孤儿站台放行」一致。
路径 1 依赖 `mtrlock$indexChildParents` 与新的对账注入器在**同一个 RETURN 点**的相对顺序
（Mixin 不承诺顺序，见 Q8），所以对账里应直接用路径 2。

---

## 【5】`Data#sync()` 触发频率 —— 9 个调用点；不是每 tick

**结论**：全 core 只有 9 处调用 `sync()`（含 1 处客户端）。服务端触发点：加载、结构变更（update / delete）、
**数据响应构建**（开 dashboard / 拉列表）、**电梯生成**、以及 tick 里清理失效侧线时。
**纯登录 / 退服不会触发**，但 MTR 客户端进服后请求数据 → `DataResponse.write()` → 会触发。

**证据**

```text
$ for c in $(grep -rl "sync" --include=*.class org/mtr/core); do javap -c -p $c | grep -E "Data|Simulator\.sync:\(\)V"; done
### org.mtr.core.data.ClientData            -> <init>（客户端）
### org.mtr.core.simulation.Simulator        -> <init>
### org.mtr.core.operation.UpdateDataRequest -> update()           offset 150
### org.mtr.core.operation.DeleteDataRequest -> delete(Simulator)  offset 131
### org.mtr.core.operation.DataResponse      -> write()
### org.mtr.core.operation.DeleteDataResponse-> write(Data)
### org.mtr.core.operation.ListDataResponse  -> write()
### org.mtr.core.operation.GenerateByLift    -> generate()

// Simulator.tick(long) 里的 sync 是条件调用（不是每 tick）
77: sidings.removeIf(isInvalidSavedRail)
80: ifeq 87
83: aload_0
84: invokevirtual Data.sync:()V        ← 只有真的删掉了失效侧线才 sync
// Simulator.tick() → tickUntilCaughtUp() → tick(long)（每服务器 tick 至少一次，但 sync 通常不跑）
```

> 现有 `DataChildParentMixin` 注释写「加载与 tick 时都会调用」——**tick 那句不准确**（是条件触发）。

**坑**：对账逻辑会被「开 dashboard / 拉列表 / 生成电梯」这些**只读**路径触发。
对账必须是**幂等且廉价**的（按线路 × 站台线性即可，但别在里面做重 IO）；
`removed_refs.json` 的写盘要合并 / 防抖，不能每次 sync 都落盘。

---

## 【6】`canEdit(UUID, objectId)` 重载难度 —— 小（约 5 行）

**结论**：`canEdit(ServerPlayerEntity, objectId)` 内部**已经就是纯 UUID 字符串比较**：
取 `player.getUuidAsString()` + `isAdmin(player)`，其余全交给已有的纯逻辑重载。新增 UUID 重载不需要任何新字段。

**证据**

```java
// PermissionChecker.java:60-66
public static boolean canEdit(ServerPlayerEntity player, String objectId) {
    if (player == null) return false;
    return canEdit(objectId, player.getUuidAsString(), isAdmin(player),
            OWNERSHIP, SHARES, MEMBERSHIPS);            // ← 纯逻辑重载，line 88
}
// 私有生产注入，line 192 / 205 / 208：OWNERSHIP / SHARES / MEMBERSHIPS 都是本类静态字段
// line 132-135 editPermissionFor(player)
//        = objectId -> canEdit(objectId, uuid, admin, OWNERSHIP, SHARES, MEMBERSHIPS)

// UUID 字符串一致性：OwnershipData 存 getUuidAsString()（小写带连字符），
// Team.isMember / ownerUuid 也来自 UUID.randomUUID().toString() / getUuidAsString()
// → 同为小写带连字符
// Team.java:155  public boolean isMember(String uuid) { return members.contains(uuid); }   // 纯字符串相等
```

**建议签名与改动量**

```java
public static boolean canEdit(UUID playerUuid, String objectId) {
    return canEdit(objectId, playerUuid == null ? null : playerUuid.toString(),
            false, OWNERSHIP, SHARES, MEMBERSHIPS);
}
```

- **改动量：小（5 行内）**，不需要碰 `SHARES` / `MEMBERSHIPS` 任何逻辑。
- **坑**：`isAdmin` **无法从 UUID 推导**（OP 等级是在线玩家的实时属性），所以 UUID 重载里只能 `isAdmin = false`。
  这与「OP 不进快照」一致；但若将来有人拿这个重载做别的事，要注意它**没有管理员豁免**。
  若想显式一点，可加 `canEdit(UUID, String, boolean isAdmin)` 三参重载。

---

## 【7】快照与 `removed_refs.json` 的持久化 —— 模板完备；无「按天清理」可复用

**结论**：最接近的模板是 **`ShareData`**（`Map<String, Set<String>>`，与 `routeId → Set<platformId>` 同构）
+ **`OwnershipData`**（坏文件保护 + 空内存守卫）。监听器模式在 `ShareData` / `TitleData`。
**mtrlock 里没有任何按天清理逻辑**，只有 `PendingCreators` 的内存 TTL。

**证据**

```text
ShareData.java:49   FILE_NAME = "mtrperm/shares.json"
ShareData.java:58   Type MAP_TYPE = new TypeToken<Map<String, Set<String>>>(){}
ShareData.java:93   Map<String,Set<String>> shares = new ConcurrentHashMap<>()
ShareData.java:110  boolean loadFailed;                    // 坏文件保护
ShareData.java:343  private static volatile Runnable changeListener;  setChangeListener(...)
ShareData.java:325  int cleanupOrphanTeams()               // 「加载后清理孤儿」的现成范例

OwnershipData.java:206 save()：if (loadFailed) skip；if (creators.isEmpty() && 文件非空) skip（防误清空）
OwnershipData.java:249 load()：先解析成功再替换内存；失败置 loadFailed、保留内存

Mtrlock.java       SERVER_STARTED : OwnershipData/TeamData/ShareData/TitleData/DisplayConfig/ProtectionConfig.load()
Mtrlock.java       SERVER_STOPPING + shutdown hook : 全部 .save()

$ grep -rniE "day|expire|ttl|prune|cleanup" src/main/java
PendingCreators.java:28  DEFAULT_TTL_MILLIS = 10 分钟
PendingCreators.java:76  pruneExpired()            ← 唯一的 TTL 机制，且在内存里
（无任何按天 / 按日期分片清理）
```

**建议模板组合**：结构照 `ShareData`；`loadFailed` / save-skip 照 `OwnershipData`；
若要记录「移除时间」以做后续按天清理，值类型照 `TitleData` 的 `Map<String, TitleEntry>`（对象值 + 手工解析）。

**坑**

- **`removed_refs.json` 必须有 `loadFailed` 保护**：它是车站/站台「唯一恢复源」，
  一旦被坏文件覆盖，Q3 的存盘风险就变成永久丢站台。
- **按天清理需要新写**（mtrlock 没有 mtr-audit 那套）；可复用的只有 `PendingCreators.pruneExpired()` 的「惰性 prune」思路。
- 写盘时机：现有数据类只在 **SERVER_STOPPING / shutdown hook** 落盘。
  对账每次 sync 都可能改快照，如果也只在停服写，崩溃会丢恢复记录
  → 需要**节流写盘**（例如变更后 N 秒或 N 次对账落一次）。

---

## 【8】与 1.3.0 sync 钩子的合并 —— 可安全追加第三个 `@Inject`

**结论**：`DataChildParentMixin` 现在在**同一个 `sync()V` RETURN 点有两个独立 `@Inject`**
（`mtrlock$indexChildParents`、`mtrlock$rebuildProtectionIndex`）。
追加第三个 `@Inject`（引用对账）**不会影响前两个**：它们只共享单例（`ChildParents` / `ProtectionIndex`），
没有共享局部状态。

**证据（现有代码摘要，行号为文件内）**

```java
@Mixin(value = Data.class, remap = false)
public abstract class DataChildParentMixin {

  @Inject(method = "sync()V", at = @At("RETURN"), remap = false)     // 第 39 行
  private void mtrlock$indexChildParents(CallbackInfo ci) {          // 只写 ChildParents
      for (Platform p : self.platforms)
          if (p.area != null) ChildParents.put("platform:<hex>", "station:<hex>");
      for (Siding s : self.sidings)
          if (s.area != null) ChildParents.put("siding:<hex>", "depot:<hex>");
  }

  @Inject(method = "sync()V", at = @At("RETURN"), remap = false)     // 第 74 行
  private void mtrlock$rebuildProtectionIndex(CallbackInfo ci) {
      if (!((Object) this instanceof Simulator)) return;             // 排除客户端 ClientData
      ProtectionIndex.rememberServerData(self);
      ProtectionIndex.rebuildFrom(self);
  }
}
```

**建议合并方式**

1. 追加第三个 `@Inject(method = "sync()V", at = @At("RETURN"), remap = false)` →
   `mtrlock$reconcileRouteRefs`，**不新建 Mixin 类、不动现有两个注解**。
2. **不要依赖同点注入器的相对顺序**（Mixin 未承诺；生产实现里按声明顺序，但别赌）。
   第三个注入器应**直接读 `Data`**：`route.getRoutePlatforms()` + `data.platformIdMap.get(id).area`，
   而不是依赖 `ChildParents` 已被刷新。
3. **建议顺序（若确实要排序）**：先 `ProtectionIndex.rebuildFrom`（空间索引），再做引用对账。
   理由是引用对账可能发消息 / 写盘（较慢），放在同步的数据结构重建之后；
   但两者数据上没有依赖，顺序不影响正确性。
4. **`instanceof Simulator` 守卫**：对账只应在服务端跑（客户端 `ClientData` 也走这个注入点）。
5. 若采纳 Q3 的「移除后再 sync 一次」，**必须 re-entrancy 守卫**
   （否则 `sync() → RETURN → sync()` 无限递归）。
   守卫建议放 `DataChildParentMixin` 的静态 `ThreadLocal<Boolean>`，
   并且嵌套那次 sync **跳过对账**（只让 MTR 重建 `platform.routes` / depot 缓存）。

---

## 整体评估

### 8 条里有没有让方案必须改的？

**没有「必须推翻」的，但有 3 处必须补设计：**

| # | 影响 | 必须补的东西 |
|---|---|---|
| 1 | 方案简化 | 删掉「优先 `Route.owner`」分支（MTR 4.0.0 / 4.0.5 都无 owner），owner 唯一来源 = `ownership.json` |
| 3 | ⚠️ **最大风险** | (a) 在 sync RETURN 改列表 → `Platform.routes` / `routeColors` / depot 路径缓存**滞后一个 sync 周期**；要即时一致就得在守卫下**再 sync 一次**，否则接受滞后。(b) 改动会被 MTR **自动存盘** → 必须保证 `removed_refs.json` 是可靠恢复源（坏文件保护 + 节流落盘 + 孤儿清理） |
| 7 | 需新增工作量 | mtrlock **没有**按天清理逻辑（那是 mtr-audit 的），要么新写、要么本版不做 |

**其它结论**：2、4、5、6、8 均**可行且比预想的更简单** ——

- **不需要新增任何 Mixin 类**：`getRoutePlatforms()` 返回活引用，`platformIdMap` 是 public，
  `platform.area` 是 public，所以既不碰 `@Accessor` Mixin，也不触发 `targets + @Pseudo` 约束。
- `canEdit(UUID, objectId)` 是**小改动**（纯逻辑重载早就存在）。
- sync 触发点足够多（含 dashboard 拉数据），对账收敛性没问题；
  代价是它也会被只读路径触发，对账必须廉价 + 写盘防抖。

### 工作量估计（以 1.3.0 为参照）

1.3.0 基线：7 个 main 文件 + 6 个 test 文件、85 个测试、3 个钩子点、1 个配置类、1 组命令、文档三件套。

1.4.0 预计 **≈ 1.3.0 的 55%–70%**：

| 部分 | 占比 |
|---|---|
| 新数据类 `removed_refs`（坏文件保护 + 节流落盘 + 清理） | ~25% |
| 对账 / 快照逻辑 + `canEdit(UUID,...)` + 第 3 个 sync 注入器 + 守卫 | ~20% |
| 测试（快照对比、移除/恢复、孤儿/无归属 fail-open、re-entrancy、持久化往返） | ~15% |
| 命令 / lang / 文档 / 回归 | ~10% |

若要把 Q3 的「即时一致性（再 sync 一次）」和 Q7 的「按天清理」都做进去，会顶到 **~75%–85%**。

### 建议下一步

先就 Q3 的两个坑定调：

1. **接受「一个 sync 周期滞后」还是加「守卫式二次 sync」**；
2. **`removed_refs.json` 的落盘节流策略**，以及「它损坏时不得导致永久丢站台」的兜底。

这两点定了，实现路径就完全清晰了。
