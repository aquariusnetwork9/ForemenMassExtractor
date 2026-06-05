package com.shallowplague.foreman;

import com.shallowplague.foreman.hud.ForemanScanHud;
import com.shallowplague.foreman.modules.MassExtractor;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ForemanAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Foreman");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Foreman MassExtractor addon");

        // Modules
        Modules.get().add(new MassExtractor());

        // HUD — the pre-mining resource scan readout (Systems are already initialised by now)
        Hud.get().register(ForemanScanHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.shallowplague.foreman";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("aquariusnetwork9", "foreman-massextractor");
    }
}
