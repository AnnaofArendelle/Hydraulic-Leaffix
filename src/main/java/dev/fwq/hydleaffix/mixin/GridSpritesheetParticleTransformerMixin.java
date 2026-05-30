package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.geysermc.pack.converter.type.texture.transformer.TransformContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Targeted safety net (belt-and-suspenders) for the specific particle transformer that first
 * surfaced the problem.
 *
 * On Minecraft 1.21.11 the leaf particle spritesheet makes GridSpritesheetParticleTransformer.transform
 * overrun its javaPaths[] array:
 *   java.lang.ArrayIndexOutOfBoundsException: Index 12 out of bounds for length 12
 *     at ...particle.GridSpritesheetParticleTransformer.transform(GridSpritesheetParticleTransformer.java:83)
 *
 * The general {@link TextureConverterMixin} already catches this at the call site, but this keeps a
 * direct guard on the particle transformer in case the generic wrap ever fails to match.
 *
 * remap = false: target is a third-party library class, not Minecraft.
 */
@Mixin(targets = "org.geysermc.pack.converter.type.texture.transformer.type.particle.GridSpritesheetParticleTransformer", remap = false)
public class GridSpritesheetParticleTransformerMixin {

    @WrapMethod(method = "transform")
    private void hydleaffix$guardTransform(TransformContext context, Operation<Void> original) {
        try {
            original.call(context);
        } catch (Throwable t) {
            // Swallow: a single failing particle spritesheet must not abort the whole pack conversion.
            System.out.println("[HydraulicLeafFix] Skipped a crashing particle spritesheet transform so pack conversion can continue: " + t);
        }
    }
}
