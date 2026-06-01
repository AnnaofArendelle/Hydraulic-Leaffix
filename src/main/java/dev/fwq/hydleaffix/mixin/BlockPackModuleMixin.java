package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.geysermc.hydraulic.block.Materials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fixes purple/missing-texture break particles (and the placement failure that rides along with it) for
 * Hydraulic's custom blocks such as Waystones.
 *
 * How Bedrock chooses the destroy/break particle texture (per the Bedrock docs):
 *   It samples the block's "down" material instance, or "*" if "down" is not defined. That texture name MUST
 *   be a shortname present in the SAME pack's textures/terrain_texture.json.
 *
 * What Hydraulic does in BlockPackModule.onDefineCustomBlocks:
 *   For a custom-geometry block it walks the model's texture map and, for the "particle" entry, registers it
 *   as the "*" (default) material instance:
 *       if ("particle".equals(materialKey)) materialKey = "*";
 *       ...materialInstance(materialKey, MaterialInstance...texture(PackUtil.getTextureName(value)))
 *   The Waystones models declare their particle as a VANILLA texture, e.g.
 *       waystone_bottom  -> "particle": "minecraft:block/polished_andesite"
 *   getTextureName turns a remapped vanilla texture into a "hydraulic:block/..." reference, which lives in the
 *   separate shared hydraulic.mcpack — a CROSS-PACK reference that the waystones block cannot resolve. So the
 *   "*" instance (hence the break particle) has no usable texture -> purple debris. (Waystones whose particle
 *   happens to be an identity-mapped vanilla key like blackstone/end_stone resolve against the client's vanilla
 *   atlas and look fine, which is exactly the "only some are broken" split that was reported. The same broken
 *   default material also appears to make those blocks fail to place on Bedrock, while the ones with a
 *   resolvable default place normally.)
 *
 * The fix: the block's own face texture (e.g. waystones:block/andesite_waystone) is in the SAME pack and is
 * proven to resolve (the block's faces render with it). So we point the particle at that in-pack texture
 * instead of the unresolvable vanilla/cross-pack one. We only touch "minecraft:" particle values and only when
 * a real "texture" entry exists, leaving already-in-pack particles (warp_plate, mossy, ...) untouched.
 *
 * Implemented by wrapping Materials$Material.textures() inside onDefineCustomBlocks so the value the loop reads
 * for "particle" is the in-pack face texture; the "texture"/face instances and everything else are unchanged.
 *
 * remap = false: BlockPackModule / Materials are Hydraulic classes, not Minecraft.
 * require = 0  : if a future Hydraulic build changes this, the patch simply no-ops (never crashes).
 */
@Mixin(targets = "org.geysermc.hydraulic.block.BlockPackModule", remap = false)
public class BlockPackModuleMixin {

    private static boolean hydleaffix$loggedParticleFix = false;

    @WrapOperation(
            method = "onDefineCustomBlocks(Lorg/geysermc/hydraulic/pack/context/PackEventContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/geysermc/hydraulic/block/Materials$Material;textures()Ljava/util/Map;"
            ),
            require = 0,
            remap = false
    )
    private Map<String, String> hydleaffix$inPackBreakParticle(Materials.Material material, Operation<Map<String, String>> original) {
        Map<String, String> textures = original.call(material);
        try {
            if (textures == null) {
                return textures;
            }
            String particle = textures.get("particle");
            String texture = textures.get("texture");
            // Only repair a cross-pack/vanilla particle when the block has its own in-pack face texture to use.
            if (particle != null && texture != null
                    && particle.startsWith("minecraft:") && !particle.equals(texture)) {
                Map<String, String> copy = new LinkedHashMap<>(textures);
                copy.put("particle", texture);
                if (!hydleaffix$loggedParticleFix) {
                    hydleaffix$loggedParticleFix = true;
                    System.out.println("[HydraulicLeafFix] Redirected break-particle texture to the block's own in-pack "
                            + "texture: " + particle + " -> " + texture + " (further redirects not logged)");
                }
                return copy;
            }
        } catch (Throwable t) {
            System.out.println("[HydraulicLeafFix] Break-particle repair skipped: " + t);
            return textures;
        }
        return textures;
    }
}
