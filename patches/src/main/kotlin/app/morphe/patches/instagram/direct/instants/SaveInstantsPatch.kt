/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef

internal const val SAVE_INSTANTS_PATCH = "Save Instants"
internal const val SAVE_INSTANTS_HOOK =
    "$EXTENSION_PACKAGE/direct/InstantsVault;->noteMedia(Ljava/lang/Object;)V"

/** Finds Instagram's per-Instant item class by the view modes shared with Piko's original fingerprint. */
internal fun BytecodePatchContext.findInstantItemType(): String {
    val candidates = HashSet<ClassDef>()
    for (marker in listOf("NUX_VIDEO", "ONCE", "PERMANENT", "REPLAYABLE")) {
        val holders = classesLoadingString(marker).map { it.type }
        if (candidates.isEmpty()) candidates.addAll(holders.mapNotNull { classDefByOrNull(it) })
        else candidates.retainAll { it.type in holders }
    }

    var matches = candidates.filter { classDef ->
        classDef.methods.any { method ->
            method.name == "hashCode" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "I"
        }
    }.map { it.type }

    if (matches.isEmpty()) {
        matches = classesLoadingString("NUX_VIDEO").map { it.type }.filter { type ->
            classDefByOrNull(type)?.fields?.any { field ->
                field.type == MEDIA
            } == true
        }.filter { type ->
            classDefByOrNull(type)?.methods?.any { method ->
                method.name == "hashCode" && method.parameterTypes.isEmpty() && method.returnType == "I"
            } == true
        }
    }

    return matches.singleOrNull()
        ?: throw PatchException("$SAVE_INSTANTS_PATCH: expected one Instant item class, found ${matches.size}")
}

/** Hooks the Instant item's constructor, handing its first Media argument to the extension. */
internal fun BytecodePatchContext.hookInstantItem(itemType: String) {
    val constructors = mutableClassDefBy(itemType).methods.filter {
        it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == MEDIA
    }
    if (constructors.size != 1) {
        throw PatchException(
            "$SAVE_INSTANTS_PATCH: expected one $itemType constructor taking $MEDIA first, found ${constructors.size}",
        )
    }

    val constructor = constructors.single()
    constructor.requireLocals(SAVE_INSTANTS_PATCH, 2)
    constructor.addInstruction(0, "invoke-static/range { p1 .. p1 }, $SAVE_INSTANTS_HOOK")
}

@Suppress("unused")
val saveInstantsPatch = bytecodePatch(
    name = "Save Instants",
    description = "Captures received Instants while you view them, then keeps their media links so you can " +
        "reopen or download them later. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("saveInstants")

        val writeMediaBridges = mediaBridges(SAVE_INSTANTS_PATCH)
        val writeImageBridges = imageBridges(SAVE_INSTANTS_PATCH)
        writeMediaBridges()
        writeImageBridges()

        hookInstantItem(findInstantItemType())
        enableStatus("saveInstants")
    }
}
