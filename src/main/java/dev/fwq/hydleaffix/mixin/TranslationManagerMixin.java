package dev.fwq.hydleaffix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.List;
import lol.sylvie.bedframe.geyser.translator.DisplayEntityTranslator;
import lol.sylvie.bedframe.geyser.translator.ModEntityTranslator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fixes the server failing to boot on Geyser builds that no longer ship the custom-entity API.
 *
 * <p><b>Symptom (this startup log):</b>
 * <pre>
 *   Could not execute entrypoint stage 'main' ... provided by 'bedframe'
 *   Caused by: java.lang.NoClassDefFoundError: org/geysermc/geyser/api/entity/definition/GeyserEntityDefinition
 *       at lol.sylvie.bedframe.geyser.TranslationManager.&lt;init&gt;(TranslationManager.java:29)
 *       at lol.sylvie.bedframe.BedframeInitializer.&lt;init&gt;(BedframeInitializer.java:25)
 * </pre>
 *
 * <p><b>Root cause:</b> Bedframe 0.1.0 was built against a newer Geyser that exposed the custom-entity API
 * ({@code GeyserEntityDefinition}, {@code CustomEntityDefinition}, {@code CustomJavaEntityType},
 * {@code GeyserDefineEntitiesEvent}, {@code ServerSpawnEntityEvent}). Geyser 2.9.6 removed/refactored that API
 * (the server ran fine on 2.9.5 — confirmed by the log history). Bedframe's {@code TranslationManager} eagerly
 * constructs two entity translators in its field initializers:
 * <pre>
 *   DisplayEntityTranslator displayEntityTranslator = new DisplayEntityTranslator();
 *   ModEntityTranslator     modEntityTranslator     = new ModEntityTranslator();
 * </pre>
 * Both classes reference the now-missing entity API, so merely constructing them throws
 * {@code NoClassDefFoundError}, which aborts Bedframe's {@code main} entrypoint and kills the whole server boot.
 * The two entity translators only translate furniture / mod entities (on 2.9.5 they did nothing useful here:
 * {@code "ModEntityTranslator registered 0 auto entities"}); the Waystones block/item conversion we actually
 * rely on is done by the {@code BlockTranslator} / {@code ItemTranslator}, which are unaffected.
 *
 * <p><b>The fix (version-aware):</b> only when the entity API is <em>absent</em>, skip constructing the two
 * entity translators (leave the fields {@code null}) and drop them from the translator list / late-generate
 * call so nothing NPEs. When the entity API <em>is</em> present (e.g. Geyser 2.9.5), every wrapper simply
 * delegates to the original, so Bedframe's entity features keep working exactly as before. This is what makes
 * the patch portable across Geyser versions.
 *
 * <p>{@code remap = false}: {@code TranslationManager} is a Bedframe class, not Minecraft.
 * {@code require = 0}: if a future Bedframe build changes these call sites, the wrappers simply no-op.
 */
@Mixin(targets = "lol.sylvie.bedframe.geyser.TranslationManager", remap = false)
public class TranslationManagerMixin {

    /** Cached tri-state: null = not yet checked, TRUE/FALSE = entity API present/absent. */
    private static Boolean hydleaffix$entityApiPresent;

    private static boolean hydleaffix$loggedNeutralize = false;

    /**
     * True if Geyser still exposes the custom-entity API that Bedframe's entity translators need. Checked once
     * and cached. {@code GeyserEntityDefinition} is the type whose absence triggers the boot crash, so it is the
     * exact signal for whether the entity translators can be constructed safely.
     */
    private static boolean hydleaffix$entityApiPresent() {
        Boolean cached = hydleaffix$entityApiPresent;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName("org.geysermc.geyser.api.entity.definition.GeyserEntityDefinition", false,
                    TranslationManagerMixin.class.getClassLoader());
            present = true;
        } catch (Throwable t) {
            present = false;
        }
        hydleaffix$entityApiPresent = present;
        if (!present && !hydleaffix$loggedNeutralize) {
            hydleaffix$loggedNeutralize = true;
            System.out.println("[HydraulicLeafFix] Geyser custom-entity API is absent on this build; disabling "
                    + "Bedframe's entity/furniture translators so the server can boot. Waystones block/item "
                    + "conversion is unaffected. (This is the fix for the 'NoClassDefFoundError: GeyserEntityDefinition' "
                    + "startup crash.)");
        }
        return present;
    }

    @WrapOperation(
            method = "<init>",
            at = @At(value = "NEW", target = "()Llol/sylvie/bedframe/geyser/translator/DisplayEntityTranslator;"),
            require = 0,
            remap = false
    )
    private DisplayEntityTranslator hydleaffix$maybeDisplayTranslator(Operation<DisplayEntityTranslator> original) {
        // Only construct it (which loads the entity-API-referencing class) when that API actually exists.
        return hydleaffix$entityApiPresent() ? original.call() : null;
    }

    @WrapOperation(
            method = "<init>",
            at = @At(value = "NEW", target = "()Llol/sylvie/bedframe/geyser/translator/ModEntityTranslator;"),
            require = 0,
            remap = false
    )
    private ModEntityTranslator hydleaffix$maybeModEntityTranslator(Operation<ModEntityTranslator> original) {
        return hydleaffix$entityApiPresent() ? original.call() : null;
    }

    /**
     * registerHooks() builds {@code List.of(blockTranslator, itemTranslator, displayEntityTranslator,
     * modEntityTranslator)}. When the entity API is absent the last two are now {@code null}, and
     * {@code List.of} rejects nulls — so filter them out, leaving only the block/item translators that do the
     * Waystones conversion.
     */
    @WrapOperation(
            method = "registerHooks",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"
            ),
            require = 0,
            remap = false
    )
    private List<Object> hydleaffix$dropNullTranslators(Object a, Object b, Object c, Object d, Operation<List<Object>> original) {
        if (a != null && b != null && c != null && d != null) {
            return original.call(a, b, c, d); // entity API present: keep Bedframe's original list verbatim
        }
        List<Object> kept = new ArrayList<>(4);
        if (a != null) kept.add(a);
        if (b != null) kept.add(b);
        if (c != null) kept.add(c);
        if (d != null) kept.add(d);
        return kept;
    }

    /**
     * ensureLateGenerated() calls {@code this.displayEntityTranslator.ensureGenerated()}. Guard against the
     * field being null (entity API absent) so the late-generate hook becomes a no-op instead of an NPE.
     */
    @WrapOperation(
            method = "ensureLateGenerated",
            at = @At(
                    value = "INVOKE",
                    target = "Llol/sylvie/bedframe/geyser/translator/DisplayEntityTranslator;ensureGenerated()V"
            ),
            require = 0,
            remap = false
    )
    private void hydleaffix$skipNullLateGenerate(DisplayEntityTranslator translator, Operation<Void> original) {
        if (translator != null) {
            original.call(translator);
        }
    }
}
