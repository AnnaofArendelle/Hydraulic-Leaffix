package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.geysermc.hydraulic.block.Materials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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
 * ---------------------------------------------------------------------------------------------------------------
 *
 * SECOND FIX (1.2.3): Bedrock survival players cannot break Hydraulic custom blocks (Waystones), while creative
 * break and survival placement both work. Root cause is a units bug + a Geyser break-sync detail:
 *
 *   - Hydraulic registers the Bedrock destroy timer as
 *         .destructibleByMining(Float.valueOf(block.method_36555()))   // method_36555() = Java HARDNESS
 *     i.e. it uses the block's HARDNESS as the "seconds_to_destroy" of minecraft:destructible_by_mining. Hardness
 *     is NOT seconds: a waystone has hardness 5.0, but its real Java break time is ~0.75-1.5 s with a pickaxe and
 *     ~25 s by bare hand (vanilla: bareHandSeconds = hardness * 100 ticks / 20 tps = hardness * 5).
 *   - Because these blocks register as non-vanilla overrides, Geyser's BlockBreakHandler sets
 *     serverSideBlockBreaking = true and only completes the break when its OWN tool-aware progress reaches 1.0
 *     (or >= 0.65 at the moment the client predicts the break). The Bedrock client, however, autonomously fires
 *     BLOCK_PREDICT_DESTROY after its flat destructible_by_mining time (5 s, tool-UNAWARE). With bare hand / a
 *     wrong tool the server progress at 5 s is only ~0.2 (< 0.65), so Geyser RESTORES the block
 *     (BlockBreakHandler#handleContinueDestroy) — the block visibly "fails to break" and stays. With a correct
 *     pickaxe the server reaches 1.0 in ~1-4 s (before 5 s), which is why it sometimes appears to work.
 *
 * The fix: lengthen the Bedrock destroy timer to the SLOWEST real Java break time (bare hand = hardness * 5 s),
 * so the client's autonomous predict-destroy can never fire before Geyser's server-side break has completed.
 * Fast tools still break fast — Geyser drives the actual break speed via BLOCK_UPDATE_BREAK and force-destroys
 * via sendBedrockBlockDestroy when server progress hits 1.0, so the longer timer is only an upper bound that is
 * never reached when a tool is used. We deliberately do NOT touch JavaBlockState.blockHardness (the value Geyser
 * uses for its server-side progress); only the Bedrock client's display/predict timer is corrected.
 *
 * Implemented by @ModifyArg on the destructibleByMining(Float) call so we only need java.lang.Float in the
 * handler (no compile-time dependency on the Geyser API type; the target is matched by descriptor string).
 *
 * remap = false: BlockPackModule / Materials are Hydraulic classes, not Minecraft.
 * require = 0  : if a future Hydraulic build changes this, the patch simply no-ops (never crashes).
 */
@Mixin(targets = "org.geysermc.hydraulic.block.BlockPackModule", remap = false)
public class BlockPackModuleMixin {

    private static boolean hydleaffix$loggedParticleFix = false;
    private static boolean hydleaffix$loggedDestroyTimeFix = false;

    /**
     * Vanilla bare-hand break time (seconds) = hardness * 100 ticks / 20 ticks-per-second = hardness * 5.
     * This is the slowest possible Java break time for a block, so using it as the Bedrock destroy timer
     * guarantees Geyser's (always at-least-as-fast) server-side break completes before the client predicts.
     */
    private static final float HYDLEAFFIX_BARE_HAND_FACTOR = 5.0f;

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

    /**
     * Lengthen the Bedrock {@code minecraft:destructible_by_mining} time from the raw Java hardness (wrong: hardness
     * is not seconds) to the bare-hand Java break time (hardness * 5 s), so the Bedrock client never auto-predicts
     * the break before Geyser's tool-aware server-side break completes. Without this, survival Bedrock players
     * mining a Waystone with a bare hand / wrong tool see Geyser restore the block (it "fails to break"). Only the
     * client-facing destroy timer is changed; JavaBlockState.blockHardness (Geyser's server-side break math) is
     * left untouched, so actual break speed and drops still follow the held tool exactly as on Java.
     */
    @ModifyArg(
            method = "onDefineCustomBlocks(Lorg/geysermc/hydraulic/pack/context/PackEventContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/geysermc/geyser/api/block/custom/component/CustomBlockComponents$Builder;destructibleByMining(Ljava/lang/Float;)Lorg/geysermc/geyser/api/block/custom/component/CustomBlockComponents$Builder;",
                    remap = false
            ),
            require = 0,
            remap = false
    )
    private Float hydleaffix$bedrockHandBreakTime(Float javaHardness) {
        try {
            // Leave instant-break (0) and unbreakable (-1) blocks alone; only scale a real, positive hardness.
            if (javaHardness == null || javaHardness <= 0.0f
                    || Float.isNaN(javaHardness) || Float.isInfinite(javaHardness)) {
                return javaHardness;
            }
            float bareHandSeconds = javaHardness * HYDLEAFFIX_BARE_HAND_FACTOR;
            if (!hydleaffix$loggedDestroyTimeFix) {
                hydleaffix$loggedDestroyTimeFix = true;
                System.out.println("[HydraulicLeafFix] Lengthened Bedrock destructible_by_mining so survival breaking "
                        + "isn't cancelled by the client's early predict-destroy: " + javaHardness + "s -> "
                        + bareHandSeconds + "s (= bare-hand Java break time = hardness x 5; further adjustments not logged)");
            }
            return bareHandSeconds;
        } catch (Throwable t) {
            System.out.println("[HydraulicLeafFix] destructible_by_mining adjustment skipped: " + t);
            return javaHardness;
        }
    }
}
