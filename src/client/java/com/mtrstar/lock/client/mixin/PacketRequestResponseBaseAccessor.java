package com.mtrstar.lock.client.mixin;

import org.mtr.mod.packet.PacketRequestResponseBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读访问 {@link PacketRequestResponseBase} 的私有请求 JSON 字段 {@code content}。 */
@Mixin(value = PacketRequestResponseBase.class, remap = false)
public interface PacketRequestResponseBaseAccessor {

    @Accessor("content")
    String mtrlock$getContent();
}
