package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.geysermc.pack.converter.type.texture.transformer.TransformContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Targeted safety net for the grid-spritesheet particle transformers, and the source of the startup
 * "<frame>.png is missing. Please report this." log flood.
 *
 * GridSpritesheetParticleTransformer rebuilds a Bedrock particle spritesheet (leaf / cherry / gust /
 * pale_oak / small_gust) by stitching together per-frame VANILLA Java textures, e.g.
 *   particle/leaf_1.png ... particle/leaf_11.png
 * On Minecraft 1.21.11 those per-frame textures are not present in the layout this (older) converter
 * snapshot expects, so for every frame {@code context.pollOrPeekVanilla(...)} returns null. The transform
 * then:
 *   1. logs {@code "<frame>.png is missing. Please report this."} for EACH frame, and
 *   2. overruns its {@code javaPaths[]} array (an off-by-one in the warn branch: it reads
 *      {@code javaPaths[index]} after {@code index} was already post-incremented) ->
 *      {@code java.lang.ArrayIndexOutOfBoundsException: Index 12 out of bounds for length 12}.
 *
 * Because TextureConverter.extract runs this for every converted mod, a single startup prints ~1150 of
 * those warnings plus a crash per spritesheet per mod. None of it produces any output (it throws before
 * {@code context.offer(...)}), and these are vanilla particles that Bedrock renders natively anyway.
 *
 * Two complementary, dependency-light fixes (no adventure Key needed — these compile against the same
 * symbols the rest of the patch already uses):
 *
 *   1. {@link #hydleaffix$suppressMissingFrameWarning} — {@code @WrapOperation} around the transform's own
 *      {@code TransformContext.warn(String)} call: swallow the per-frame "... is missing. Please report
 *      this." messages (the ~1150-line flood). Any other warning still passes through untouched. If the
 *      frames DO exist on some other version, no such warning is emitted and the transform converts as
 *      normal — so this never hides a real problem.
 *
 *   2. {@link #hydleaffix$guardTransform} — {@code @WrapMethod} around {@code transform}: catch the
 *      array-overrun crash so one failing vanilla particle spritesheet can't abort the whole pack
 *      conversion, and log it at most ONCE PER transformer class (≈5 lines/startup) instead of once per
 *      converted mod (~115).
 *
 * remap = false: target is a third-party library class, not Minecraft.
 */
@Mixin(targets = "org.geysermc.pack.converter.type.texture.transformer.type.particle.GridSpritesheetParticleTransformer", remap = false)
public class GridSpritesheetParticleTransformerMixin {

    /** Transformer classes whose skip we've already reported, so each is logged at most once per JVM. */
    private static final Set<String> hydleaffix$loggedSkips = ConcurrentHashMap.newKeySet();

    @WrapOperation(
            method = "transform",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/geysermc/pack/converter/type/texture/transformer/TransformContext;warn(Ljava/lang/String;)V"
            )
    )
    private void hydleaffix$suppressMissingFrameWarning(TransformContext context, String message, Operation<Void> original) {
        // Swallow only the vanilla per-frame "missing" spam; let any other warning through.
        if (message != null && message.contains("is missing. Please report this.")) {
            return;
        }
        original.call(context, message);
    }

    @WrapMethod(method = "transform")
    private void hydleaffix$guardTransform(TransformContext context, Operation<Void> original) {
        try {
            original.call(context);
        } catch (Throwable t) {
            // Swallow: a single failing particle spritesheet must not abort the whole pack conversion.
            // Log once per transformer class so the doomed vanilla spritesheets don't flood the log.
            if (hydleaffix$loggedSkips.add(this.getClass().getName())) {
                System.out.println("[HydraulicLeafFix] Skipped the crashing '" + this.getClass().getSimpleName()
                        + "' vanilla particle spritesheet transform so pack conversion can continue (its source"
                        + " frames are absent on this Minecraft version; Bedrock renders these particles"
                        + " natively). Logged once per transformer. Cause: " + t);
            }
        }
    }
}
