package com.mtrstar.lock.protect;

/**
 * 一个受保护对象的水平矩形范围（1.3.0 区域方块保护）。
 *
 * <p>只有 X / Z 两个维度：MTR 的车站 / 车厂矩形在数据里 {@code position1/position2} 的 y
 * 恒为 {@code Long.MIN_VALUE / Long.MAX_VALUE}（表示不限高度），所以 <b>y 被刻意忽略</b>，
 * 本类没有 y 字段。</p>
 *
 * <p>坐标类型与 MTR 保持一致：{@code AreaBase.getMinX()/getMaxX()/getMinZ()/getMaxZ()}
 * 返回 {@code long}（内部就是 {@code Math.min/max}），因此这里全部用 {@code long}，
 * 不与 Minecraft 的 {@code int} 型 {@code BlockPos} 混用。判断方块是否在范围内由
 * {@link #contains(long, long)} 承担，{@code BlockPos} → long 的转换只在事件监听器里做。</p>
 *
 * <p>不可变值对象：{@link SpatialIndex} 会把它发布到并发可见的快照里，
 * 因此所有字段都是 final，构造后不再变化。</p>
 */
public final class ObjectRange {

    /** 对象 id，形如 {@code station:0B0829457F350DE9} / {@code depot:...}。 */
    private final String objectId;

    private final long minX;
    private final long maxX;
    private final long minZ;
    private final long maxZ;

    private ObjectRange(String objectId, long minX, long maxX, long minZ, long maxZ) {
        this.objectId = objectId;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /**
     * 由两个对角点构造 (x1,z1)-(x2,z2)，内部用 {@link Math#min}/{@link Math#max} 归一化。
     *
     * <p>MTR 客户端可能以任意顺序给出两个角，所以归一化是必须的。</p>
     *
     * @param objectId 对象 id；null / 空返回 null
     * @return 归一化后的范围；objectId 非法返回 null
     */
    public static ObjectRange ofCorners(String objectId, long x1, long z1, long x2, long z2) {
        if (objectId == null || objectId.isEmpty()) {
            return null;
        }
        return new ObjectRange(objectId,
                Math.min(x1, x2), Math.max(x1, x2),
                Math.min(z1, z2), Math.max(z1, z2));
    }

    /**
     * 由已归一化的边界构造（{@code AreaBase#getMinX()} 等）。
     *
     * <p>仍然做一次 {@link Math#min}/{@link Math#max} 防御：即使调用方传反了也不会产生
     * “min &gt; max” 的坏范围，避免后续 {@link #contains} 永远为 false 的静默失效。</p>
     *
     * @param objectId 对象 id；null / 空返回 null
     * @return 范围；objectId 非法返回 null
     */
    public static ObjectRange ofBounds(String objectId, long minX, long maxX, long minZ, long maxZ) {
        if (objectId == null || objectId.isEmpty()) {
            return null;
        }
        return new ObjectRange(objectId,
                Math.min(minX, maxX), Math.max(minX, maxX),
                Math.min(minZ, maxZ), Math.max(minZ, maxZ));
    }

    /** 方块坐标 (x,z) 是否落在范围内（含边界）。 */
    public boolean contains(long x, long z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * 向外扩张若干方块（配置 {@code expandBlocks}）。
     *
     * @param blocks 扩张量；&lt;= 0 时返回自身
     * @return 扩张后的新对象（本类不可变）
     */
    public ObjectRange expanded(int blocks) {
        if (blocks <= 0) {
            return this;
        }
        return new ObjectRange(objectId,
                minX - blocks, maxX + blocks,
                minZ - blocks, maxZ + blocks);
    }

    public String objectId() {
        return objectId;
    }

    public long minX() {
        return minX;
    }

    public long maxX() {
        return maxX;
    }

    public long minZ() {
        return minZ;
    }

    public long maxZ() {
        return maxZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectRange)) {
            return false;
        }
        final ObjectRange other = (ObjectRange) o;
        return minX == other.minX && maxX == other.maxX && minZ == other.minZ && maxZ == other.maxZ
                && objectId.equals(other.objectId);
    }

    @Override
    public int hashCode() {
        int result = objectId.hashCode();
        result = 31 * result + Long.hashCode(minX);
        result = 31 * result + Long.hashCode(maxX);
        result = 31 * result + Long.hashCode(minZ);
        result = 31 * result + Long.hashCode(maxZ);
        return result;
    }

    @Override
    public String toString() {
        return objectId + "[" + minX + ".." + maxX + ", " + minZ + ".." + maxZ + "]";
    }
}
