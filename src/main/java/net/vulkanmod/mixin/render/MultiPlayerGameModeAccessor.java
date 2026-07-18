package net.vulkanmod.mixin.render;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// === RT PATCH (M8.16): доступ к прогрессу ломания блока (для трещин в RT) ===
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyProgress") float rtGetDestroyProgress();
    @Accessor("isDestroying")    boolean rtIsDestroying();
}
