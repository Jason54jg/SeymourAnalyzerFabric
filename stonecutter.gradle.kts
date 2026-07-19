plugins {
	id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
	swaps["mod_version"] = "\"${property("mod.version")}\";"
	swaps["minecraft"] = "\"${node.metadata.version}\";"
	constants["release"] = true
	dependencies["fapi"] = node.project.property("deps.fabric_api") as String

	replacements {
		// Mojang split GuiGraphics into a render-state extraction API (GuiGraphicsExtractor)
		// starting with 26.1, renaming several methods in the process. Verified against
		// Mojang's official client mappings for 1.21.11 (our only <26.1 target).
		string(current.parsed < "26.1") {
			replace("GuiGraphicsExtractor", "GuiGraphics")
			// Quote-scoped: these are mixin @Inject method target strings. Bare "render" is too
			// generic to search-and-replace safely - it collides with package names, the plain
			// English word "render(er)/rendering" in comments, etc. (caught during testing: it
			// mangled "renderer" imports and even our own "render" package name for versions
			// where this rule's condition is false and the reverse direction runs). Scoping to
			// the quoted annotation string keeps the match unique.
			replace("\"extractRenderState\"", "\"render\"")
			replace("\"extractSlot\"", "\"renderSlot\"")
			replace("\"extractTooltip\"", "\"renderTooltip\"")
			replace(".centeredText(", ".drawCenteredString(")
			replace(".text(", ".drawString(")
			replace(".item(", ".renderItem(")
			replace("afterExtract", "afterRender")
			replace("resizeGui", "resizeDisplay")
			// RenderTypes' package/class name is handled directly with //? if in BlockHighlighter.java -
			// it moved packages at 1.21.11 and was pluralized separately at 26.1, and chaining two
			// global replacements with different thresholds against the same text isn't reliable.
		}

		// Fabric API renamed a handful of client helper classes/packages in the same
		// 26.1 refactor wave. All safe whole-token renames (no collision risk).
		string(current.parsed < "26.1") {
			replace("net.fabricmc.fabric.api.client.keymapping.v1", "net.fabricmc.fabric.api.client.keybinding.v1")
			replace("KeyMappingHelper", "KeyBindingHelper")
			replace("registerKeyMapping", "registerKeyBinding")
			replace("ClientCommands", "ClientCommandManager")
		}

		// Minecraft.setScreen(Screen) was replaced by setScreenAndShow(Screen) in 26.2, and the
		// Minecraft.screen field moved to Minecraft.gui.screen() - both confirmed against the
		// real 26.2 client jar via javap. Suffix-scoped (space/paren/semicolon) because bare
		// "client.screen" is a substring of the unrelated Fabric API package
		// "net.fabricmc.fabric.api.client.screen.v1".
		string(current.parsed >= "26.2") {
			replace(".setScreen(", ".setScreenAndShow(")
			replace("client.screen ", "client.gui.screen() ")
			replace("client.screen)", "client.gui.screen())")
			replace("client.screen;", "client.gui.screen();")
			replace("mc.screen ", "mc.gui.screen() ")
		}
	}
}
