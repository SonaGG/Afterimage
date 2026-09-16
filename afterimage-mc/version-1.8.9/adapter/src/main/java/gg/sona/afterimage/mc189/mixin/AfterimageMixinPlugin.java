package gg.sona.afterimage.mc189.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class AfterimageMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> VANILLA_TERRAIN_MIXINS = Set.of(
            "WorldRendererChunkMixin",
            "RenderChunkMixin",
            "RenderChunkStorageMixin"
    );

    private String mixinPackage;

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!FabricLoader.getInstance().isModLoaded("argentum")) {
            return true;
        }
        String simpleName = mixinClassName.startsWith(mixinPackage + ".")
                ? mixinClassName.substring(mixinPackage.length() + 1)
                : mixinClassName;
        return !VANILLA_TERRAIN_MIXINS.contains(simpleName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
