package com.example.jailmod.client.config;

import com.example.jailmod.JailMod;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

public final class JailModClothConfigScreen {
    private JailModClothConfigScreen() {
    }

    public static Screen create(Screen parent) {
        JailMod.Config clientConfig = JailMod.getClientDefaultsConfig();
        JailMod.Config currentWorldConfig = JailMod.getWorldConfig();
        boolean worldAvailable = JailMod.hasWorldConfigLoaded();
        if (currentWorldConfig == null) {
            currentWorldConfig = clientConfig;
        }

        ConfigDraft clientDraft = new ConfigDraft(clientConfig);
        ConfigDraft worldDraft = new ConfigDraft(currentWorldConfig);
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Jail Mod Settings"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory clientDefaults = builder.getOrCreateCategory(Component.literal("Client Defaults"));
        clientDefaults.setDescription(new FormattedText[] {
                Component.literal("These settings are defaults for new worlds and for worlds opened for the first time.")
        });
        addSettings(clientDefaults, entries, clientDraft, true);

        ConfigCategory worldSettings = builder.getOrCreateCategory(Component.literal("This World"));
        String worldDescription = worldAvailable
                ? "These settings belong to the open singleplayer world and override its client defaults."
                : "Open a singleplayer world to enable its settings. Dedicated servers use their own server config.";
        worldSettings.setDescription(new FormattedText[] { Component.literal(worldDescription) });
        addSettings(worldSettings, entries, worldDraft, worldAvailable);

        builder.setSavingRunnable(() -> {
            JailMod.saveClientDefaultsFromScreen(clientDraft.toConfig());
            if (worldAvailable) {
                JailMod.saveWorldConfigFromScreen(worldDraft.toConfig());
            }
        });
        return builder.build();
    }

    private static void addSettings(ConfigCategory category, ConfigEntryBuilder entries, ConfigDraft draft,
            boolean editable) {
        SubCategoryBuilder general = entries.startSubCategory(Component.literal("General")).setExpanded(true);
        addEntry(general, entries.startStrField(Component.literal("Admin roles"), draft.adminRoles[0])
                .setDefaultValue("op")
                .setTooltip(Component.literal("Comma-separated player tags that can use jail commands. Use 'op' for operators."))
                .setSaveConsumer(value -> draft.adminRoles[0] = value)
                .build(), editable);
        addEntry(general, entries.startBooleanToggle(Component.literal("Use previous spawn position"),
                draft.usePreviousPosition[0])
                .setDefaultValue(true)
                .setTooltip(Component.literal("Use the player's bed or respawn anchor as the release fallback."))
                .setSaveConsumer(value -> draft.usePreviousPosition[0] = value)
                .build(), editable);
        addEntry(general, entries.startBooleanToggle(Component.literal("Return to last location"),
                draft.returnToLastLocation[0])
                .setDefaultValue(true)
                .setTooltip(Component.literal("Return players to the exact location where they were jailed."))
                .setSaveConsumer(value -> draft.returnToLastLocation[0] = value)
                .build(), editable);
        addSection(category, general, editable);

        SubCategoryBuilder positions = entries.startSubCategory(Component.literal("Positions")).setExpanded(true);
        addEntry(positions, entries.startIntField(Component.literal("Jail X"), draft.jailX[0])
                .setDefaultValue(0)
                .setSaveConsumer(value -> draft.jailX[0] = value).build(), editable);
        addEntry(positions, entries.startIntField(Component.literal("Jail Y"), draft.jailY[0])
                .setDefaultValue(60)
                .setSaveConsumer(value -> draft.jailY[0] = value).build(), editable);
        addEntry(positions, entries.startIntField(Component.literal("Jail Z"), draft.jailZ[0])
                .setDefaultValue(0)
                .setSaveConsumer(value -> draft.jailZ[0] = value).build(), editable);
        addEntry(positions, entries.startIntField(Component.literal("Release X"), draft.releaseX[0])
                .setDefaultValue(100)
                .setSaveConsumer(value -> draft.releaseX[0] = value).build(), editable);
        addEntry(positions, entries.startIntField(Component.literal("Release Y"), draft.releaseY[0])
                .setDefaultValue(65)
                .setSaveConsumer(value -> draft.releaseY[0] = value).build(), editable);
        addEntry(positions, entries.startIntField(Component.literal("Release Z"), draft.releaseZ[0])
                .setDefaultValue(100)
                .setSaveConsumer(value -> draft.releaseZ[0] = value).build(), editable);
        addSection(category, positions, editable);

        SubCategoryBuilder discord = entries.startSubCategory(Component.literal("Discord")).setExpanded(true);
        addEntry(discord, entries.startStrField(Component.literal("Discord webhook URL"), draft.discordWebhookUrl[0])
                .setDefaultValue("")
                .setTooltip(Component.literal("Webhook used for jail and release notifications in this settings scope."))
                .setSaveConsumer(value -> draft.discordWebhookUrl[0] = value)
                .build(), editable);
        addSection(category, discord, editable);
    }

    private static void addEntry(ConfigCategory category, AbstractConfigListEntry<?> entry, boolean editable) {
        entry.setEditable(editable);
        category.addEntry(entry);
    }

    private static void addEntry(SubCategoryBuilder category, AbstractConfigListEntry<?> entry, boolean editable) {
        entry.setEditable(editable);
        category.add(entry);
    }

    private static void addSection(ConfigCategory category, SubCategoryBuilder section, boolean editable) {
        AbstractConfigListEntry<?> sectionEntry = section.build();
        sectionEntry.setEditable(editable);
        category.addEntry(sectionEntry);
    }

    private static final class ConfigDraft {
        private final String[] adminRoles;
        private final boolean[] usePreviousPosition;
        private final boolean[] returnToLastLocation;
        private final int[] jailX;
        private final int[] jailY;
        private final int[] jailZ;
        private final int[] releaseX;
        private final int[] releaseY;
        private final int[] releaseZ;
        private final String[] discordWebhookUrl;
        private final boolean[] useBanhammerWebhook;

        private ConfigDraft(JailMod.Config config) {
            JailMod.Config.Position jailPosition = config.jail_position == null
                    ? new JailMod.Config.Position(0, 60, 0)
                    : config.jail_position;
            JailMod.Config.Position releasePosition = config.release_position == null
                    ? new JailMod.Config.Position(100, 65, 100)
                    : config.release_position;
            adminRoles = new String[] { config.admin_roles == null ? "op" : config.admin_roles };
            usePreviousPosition = new boolean[] { config.use_previous_position };
            returnToLastLocation = new boolean[] { config.return_to_last_location };
            jailX = new int[] { jailPosition.x };
            jailY = new int[] { jailPosition.y };
            jailZ = new int[] { jailPosition.z };
            releaseX = new int[] { releasePosition.x };
            releaseY = new int[] { releasePosition.y };
            releaseZ = new int[] { releasePosition.z };
            discordWebhookUrl = new String[] {
                    config.discord_webhook_url == null ? "" : config.discord_webhook_url
            };
            useBanhammerWebhook = new boolean[] { config.useBanhammerWebhook };
        }

        private JailMod.Config toConfig() {
            JailMod.Config config = new JailMod.Config();
            config.admin_roles = adminRoles[0];
            config.use_previous_position = usePreviousPosition[0];
            config.return_to_last_location = returnToLastLocation[0];
            config.jail_position = new JailMod.Config.Position(jailX[0], jailY[0], jailZ[0]);
            config.release_position = new JailMod.Config.Position(releaseX[0], releaseY[0], releaseZ[0]);
            config.discord_webhook_url = discordWebhookUrl[0];
            config.useBanhammerWebhook = useBanhammerWebhook[0];
            return config;
        }
    }
}
