package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.geysermc.pack.converter.type.texture.transformer.TextureTransformer;
import org.geysermc.pack.converter.type.texture.transformer.TransformContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * General safety net for Hydraulic's (Bedframe-supplied) pack converter.
 *
 * TextureConverter.extract(...) loops over every TextureTransformer and calls transformer.transform(ctx)
 * with NO try/catch. On Minecraft 1.21.11, several of these transformers throw on vanilla assets that
 * changed format, e.g.:
 *   - GridSpritesheetParticleTransformer  -> ArrayIndexOutOfBoundsException (leaf particle grid)
 *   - MapIconsTransformer (via gridTransform -> KeyUtil.key) -> NullPointerException ("value")
 * ANY single throw aborts the ENTIRE pack conversion for that mod ("Failed to convert pack for mod X"),
 * so Bedrock clients never receive the converted block models/textures (Waystones look invisible).
 *
 * This wraps the per-transformer call so one failing transformer is logged and skipped, letting the
 * rest of the pack (the actual block textures/models) convert. It is intentionally generic so it fixes
 * the whole family of "one transformer crashes the pack" bugs at once, instead of patching them one by one.
 *
 * remap = false: target is a third-party library class, not Minecraft.
 */
@Mixin(targets = "org.geysermc.pack.converter.type.texture.TextureConverter", remap = false)
public class TextureConverterMixin {

    @WrapOperation(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/geysermc/pack/converter/type/texture/transformer/TextureTransformer;transform(Lorg/geysermc/pack/converter/type/texture/transformer/TransformContext;)V"
            )
    )
    private void hydleaffix$guardTransformer(TextureTransformer transformer, TransformContext context, Operation<Void> original) {
        try {
            original.call(transformer, context);
        } catch (Throwable t) {
            System.out.println("[HydraulicLeafFix] Skipped a failing texture transformer ("
                    + transformer.getClass().getName() + ") so pack conversion can continue: " + t);
        }
    }
}
