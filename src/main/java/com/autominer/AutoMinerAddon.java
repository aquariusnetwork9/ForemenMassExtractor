package com.autominer;

import com.autominer.modules.AutoMiner;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AutoMinerAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Mining");

    @Override
    public void onInitialize() {
        LOG.info("Initializing AutoMiner addon");

        // Modules
        Modules.get().add(new AutoMiner());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.autominer";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("aquariusnetwork9", "auto-miner");
    }
}
