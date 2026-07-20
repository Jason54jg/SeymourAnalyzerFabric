package schnerry.seymouranalyzer.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import schnerry.seymouranalyzer.config.ConfigScreen;

/**
 * Only loaded by Fabric Loader if Mod Menu is installed (queried lazily via the
 * "modmenu" entrypoint) - never referenced otherwise, so Mod Menu stays optional.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::createConfigScreen;
    }
}
