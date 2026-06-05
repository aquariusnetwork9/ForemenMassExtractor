package com.shallowplague.foreman.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.List;

/**
 * HUD element that shows the result of Foreman's one-time pre-mining chunk scan: the 3 most abundant
 * blocks and the 5 most abundant ores in the mining area, with counts. The scan itself is run by
 * {@code MassExtractor} on activation; this element only renders {@link ScanData}.
 */
public class ForemanScanHud extends HudElement {
    public static final HudElementInfo<ForemanScanHud> INFO = new HudElementInfo<>(
        Hud.GROUP, "foreman-scan",
        "Resource counts from Foreman's pre-mining chunk scan (top 3 blocks + top 5 ores).",
        ForemanScanHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow").description("Text shadow.").defaultValue(true).build());

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale").description("Text scale.").defaultValue(1).min(0.5).sliderRange(0.5, 3).build());

    private final Setting<SettingColor> titleColor = sgGeneral.add(new ColorSetting.Builder()
        .name("title-color").description("Header / label color.").defaultValue(new SettingColor(255, 200, 60)).build());

    private final Setting<SettingColor> nameColor = sgGeneral.add(new ColorSetting.Builder()
        .name("name-color").description("Block / ore name color.").defaultValue(new SettingColor(220, 220, 220)).build());

    private final Setting<SettingColor> countColor = sgGeneral.add(new ColorSetting.Builder()
        .name("count-color").description("Count color.").defaultValue(new SettingColor(120, 230, 120)).build());

    // running render cursor / measured width (set per-render so the row helper can update them)
    private double curY;
    private double maxW;

    public ForemanScanHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double sc = scale.get();
        double lh = renderer.textHeight(shadow.get(), sc) + 2;
        curY = y;
        maxW = 0;

        boolean editor = isInEditor();
        List<ScanData.Count> blocks = editor ? sampleBlocks() : ScanData.topBlocks;
        List<ScanData.Count> ores   = editor ? sampleOres()   : ScanData.topOres;
        String summary = editor ? "9 chunks · y-59..-50" : ScanData.summary;

        line(renderer, "Resource Scan", titleColor.get(), sc, lh);

        if (!editor && !ScanData.valid) {
            line(renderer, "(start mining to scan)", nameColor.get(), sc, lh);
            setSize(maxW, curY - y - 2);
            return;
        }

        if (!summary.isEmpty()) line(renderer, summary, titleColor.get(), sc, lh);

        line(renderer, "Blocks:", titleColor.get(), sc, lh);
        if (blocks.isEmpty()) line(renderer, "  (none)", nameColor.get(), sc, lh);
        else for (ScanData.Count c : blocks) row(renderer, c, sc, lh);

        line(renderer, "Ores:", titleColor.get(), sc, lh);
        if (ores.isEmpty()) line(renderer, "  (none)", nameColor.get(), sc, lh);
        else for (ScanData.Count c : ores) row(renderer, c, sc, lh);

        setSize(maxW, curY - y - 2); // drop the trailing inter-line gap
    }

    /** One full-width text line in a single color; advances the cursor and tracks width. */
    private void line(HudRenderer renderer, String text, meteordevelopment.meteorclient.utils.render.color.Color color, double sc, double lh) {
        double x2 = renderer.text(text, x, curY, color, shadow.get(), sc);
        maxW = Math.max(maxW, x2 - x);
        curY += lh;
    }

    /** One "Name  count" row, name and count in their own colors. */
    private void row(HudRenderer renderer, ScanData.Count c, double sc, double lh) {
        double x2 = renderer.text("  " + c.name() + "  ", x, curY, nameColor.get(), shadow.get(), sc);
        x2 = renderer.text(ScanData.fmt(c.count()), x2, curY, countColor.get(), shadow.get(), sc);
        maxW = Math.max(maxW, x2 - x);
        curY += lh;
    }

    private List<ScanData.Count> sampleBlocks() {
        return List.of(
            new ScanData.Count("Deepslate", 1284213),       // >6 digits -> shows as 999999+
            new ScanData.Count("Cobbled Deepslate", 4096),
            new ScanData.Count("Water", 312));
    }

    private List<ScanData.Count> sampleOres() {
        return List.of(
            new ScanData.Count("Iron Ore", 84),
            new ScanData.Count("Copper Ore", 61),
            new ScanData.Count("Redstone Ore", 40),
            new ScanData.Count("Gold Ore", 12),
            new ScanData.Count("Diamond Ore", 5));
    }
}
