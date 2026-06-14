package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Map;
import java.util.Optional;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_2960;
import net.minecraft.class_3222;
import net.minecraft.class_7923;
import net.minecraft.class_9326;
import net.minecraft.class_9331;
import org.geysermc.geyser.api.GeyserApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Fixes Bedrock (Geyser) players seeing empty/garbled inventories and chests in survival — the slot is occupied
 * but the item does not render — AND the follow-on desync where picking items up spawns a ghost icon that can't
 * be placed, and survival custom-block breaking fails again.
 *
 * <p><b>Symptom (server log, once per affected item per inventory refresh):</b>
 * <pre>
 *   [localSession-.../WARN]: 下游数据包错误！Exception while reading components for item 1549
 * </pre>
 *
 * <p><b>Root cause (the "data conversion is incorrect"):</b> Geyser reads the Java server's packets through its
 * bundled MCProtocolLib, whose {@code DataComponentTypes.read(buf)} only knows the <em>vanilla</em> data-component
 * type list. When an item carries a <b>modded</b> data component (e.g. {@code waystones:attuned_shard} carrying
 * {@code waystones:attunement}), its component-type network id is out of range, the read throws, and because
 * clientbound item components are written without per-component length prefixes that throw corrupts the framing
 * of the <em>entire</em> inventory packet — so every item in that container fails to render.
 *
 * <p>Why it reaches Geyser at all: {@code waystones:attuned_shard} (and similar) are plain (non-Polymer) modded
 * items, so Polymer's {@code getPolymerItemStack} returns them <em>raw</em> (its final {@code return itemStack;}
 * branch) instead of converting them — keeping their modded components.
 *
 * <p><b>The fix, and why the naive "just strip the components" version was wrong:</b> Polymer keeps the server's
 * authoritative item and the client-facing item consistent by patching the ItemStack network codec — every
 * outgoing item runs {@code getPolymerItemStack} and every <em>incoming</em> item runs {@code getRealItemStack},
 * which restores the real server item from the {@code "$polymer:stack"} ({@code POLYMER_STACK}) NBT that
 * {@link #createItemStack} embeds. An item that is sent raw has <b>no</b> {@code POLYMER_STACK}, so the server
 * can't restore it on inbound; if we merely strip its modded components on the wire, the client/Geyser view
 * diverges from the server's authoritative item, which desyncs inventory interactions (ghost cursor item, can't
 * place) and the player's held-tool state (so the server-driven survival break uses the wrong tool and the
 * {@code destructible_by_mining} timing breaks again).
 *
 * <p>So instead of hand-stripping, we route these raw modded items — <b>only for Bedrock players</b> — through
 * Polymer's own {@link #createItemStack}, exactly as Polymer does for its own items: it drops the modded
 * components (it copies only a fixed vanilla component set), keeps the same item id (so Geyser still maps it to
 * the right Bedrock item), and embeds the {@code POLYMER_STACK} round-trip so the server restores the real item
 * on inbound. The item then behaves like every other Polymer item the server already handles correctly in
 * survival. Java players are untouched (the {@code isBedrockPlayer} guard). If {@code createItemStack} ever
 * fails we fall back to a plain component strip (display at least works), and if everything throws we return the
 * original stack — this safety net can never break item encoding.
 *
 * <p>{@code remap = false}: {@code PolymerItemUtils} is a Polymer class (its descriptor already uses the
 * intermediary {@code class_*} names that exist at runtime).
 */
@Mixin(targets = "eu.pb4.polymer.core.api.item.PolymerItemUtils", remap = false)
public abstract class PolymerBedrockItemFixMixin {

    private static boolean hydleaffix$loggedFix = false;

    /** Polymer's own server-item -> client-item converter: strips modded components and embeds the
     *  {@code POLYMER_STACK} round-trip NBT. Shadowed so we reuse Polymer's exact, tested logic. */
    @Shadow
    private static native class_1799 createItemStack(class_1799 itemStack, class_1836 tooltipContext, PacketContext context);

    @ModifyReturnValue(
            method = "getPolymerItemStack(Lnet/minecraft/class_1799;Lnet/minecraft/class_1836;Lxyz/nucleoid/packettweaker/PacketContext;)Lnet/minecraft/class_1799;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private static class_1799 hydleaffix$fixModdedComponentsForBedrock(class_1799 result, class_1799 itemStack,
                                                                       class_1836 tooltipContext, PacketContext context) {
        try {
            if (result == null) {
                return result;
            }
            // Only touch items destined for a Bedrock (Geyser) player; Java players must keep modded components.
            class_3222 player = context.getPlayer();
            if (player == null || !hydleaffix$isBedrockPlayer(player)) {
                return result;
            }
            // Fast path: only act when the wire item's component patch actually carries a non-minecraft type id
            // (the thing Geyser can't read). Normal Polymer items and vanilla items pass through untouched.
            if (!hydleaffix$hasModdedPatchComponent(result)) {
                return result;
            }

            // Preferred fix: convert via Polymer's own path -> drops modded components AND adds the POLYMER_STACK
            // round-trip, so the server restores the real item on inbound and inventory/break state stays in sync.
            class_1799 converted = createItemStack(itemStack, tooltipContext, context);
            if (converted != null && !hydleaffix$hasModdedPatchComponent(converted)) {
                hydleaffix$logOnce();
                return converted;
            }

            // Fallback: at least strip the modded components so the inventory renders (no round-trip, but better
            // than an unreadable packet) if createItemStack was unavailable or didn't clear them.
            class_1799 stripped = hydleaffix$stripModded(result);
            hydleaffix$logOnce();
            return stripped != null ? stripped : result;
        } catch (Throwable t) {
            // Never break item encoding because of this safety net.
            return result;
        }
    }

    private static boolean hydleaffix$hasModdedPatchComponent(class_1799 stack) {
        class_9326 patch = stack.method_57380();
        if (patch == null || patch.method_57848()) {
            return false;
        }
        for (Map.Entry<class_9331<?>, Optional<?>> entry : patch.method_57846()) {
            if (!hydleaffix$isVanilla(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Rebuild a fresh stack re-applying only the {@code minecraft:} component-patch entries, dropping every
     *  non-minecraft added AND removed patch entry (so neither is encoded). Used only as a fallback. */
    private static class_1799 hydleaffix$stripModded(class_1799 stack) {
        class_9326 patch = stack.method_57380();
        class_1792 item = stack.method_7909();
        class_1799 rebuilt = new class_1799(item, stack.method_7947());
        for (Map.Entry<class_9331<?>, Optional<?>> entry : patch.method_57846()) {
            class_9331<?> type = entry.getKey();
            if (!hydleaffix$isVanilla(type)) {
                continue;
            }
            Optional<?> value = entry.getValue();
            if (value.isPresent()) {
                hydleaffix$applyComponent(rebuilt, type, value.get());
            } else {
                rebuilt.method_57381(type);
            }
        }
        return rebuilt;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void hydleaffix$applyComponent(class_1799 stack, class_9331<?> type, Object value) {
        stack.method_57379((class_9331) type, value);
    }

    /** A component type is "vanilla" iff it is registered under the {@code minecraft} namespace. */
    private static boolean hydleaffix$isVanilla(class_9331<?> type) {
        try {
            class_2960 id = class_7923.field_49658.method_10221(type);
            return id != null && "minecraft".equals(id.method_12836());
        } catch (Throwable t) {
            return false; // unknown/unregistered -> treat as non-vanilla and strip, to be safe
        }
    }

    private static boolean hydleaffix$isBedrockPlayer(class_3222 player) {
        try {
            return GeyserApi.api().isBedrockPlayer(player.method_5667());
        } catch (Throwable t) {
            return false; // Geyser not ready / API changed -> behave as if Java (do nothing)
        }
    }

    private static void hydleaffix$logOnce() {
        if (!hydleaffix$loggedFix) {
            hydleaffix$loggedFix = true;
            System.out.println("[HydraulicLeafFix] Converted item(s) with non-vanilla data components to a "
                    + "Polymer round-trip stack for a Bedrock player so Geyser can read them and inventory/break "
                    + "state stays in sync (fixes 'Exception while reading components for item ...' and the "
                    + "follow-on pickup/place desync; further conversions not logged).");
        }
    }
}
