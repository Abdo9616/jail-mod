package com.example.jailmod.client.config;

import com.example.jailmod.JailMod;
import com.example.jailmod.mixin.ScreenAccessor;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;

public final class JailModClothConfigScreen {
    private static final Component CLIENT_DEFAULTS_CATEGORY = Component.literal("Client Defaults");
    private static final Component THIS_WORLD_CATEGORY = Component.literal("This World");
    private static final Component HELP_CATEGORY = Component.literal("Help")
            .withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(ChatFormatting.BOLD);
    private static final Map<Screen, ConfigFileButtons> CONFIG_FILE_BUTTONS = new WeakHashMap<>();
    private static final Pattern COMMAND_ARGUMENT_PATTERN = Pattern.compile("(<[^>]+>|\\[[^\\]]+\\])");

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

        ConfigCategory clientDefaults = builder.getOrCreateCategory(CLIENT_DEFAULTS_CATEGORY);
        clientDefaults.setDescription(new FormattedText[] {
                Component.literal("These settings are defaults for new worlds and for worlds opened for the first time.")
        });
        addSettings(clientDefaults, entries, clientDraft, true);

        ConfigCategory worldSettings = builder.getOrCreateCategory(THIS_WORLD_CATEGORY);
        String worldDescription = worldAvailable
                ? "These settings belong to the open singleplayer world and override its client defaults."
                : "Open a singleplayer world to enable its settings. Dedicated servers use their own server config.";
        worldSettings.setDescription(new FormattedText[] { Component.literal(worldDescription) });
        addSettings(worldSettings, entries, worldDraft, worldAvailable);

        ConfigCategory help = builder.getOrCreateCategory(HELP_CATEGORY);
        addConfigGuide(help, entries);

        builder.setSavingRunnable(() -> {
            JailMod.saveClientDefaultsFromScreen(clientDraft.toConfig());
            if (worldAvailable) {
                JailMod.saveWorldConfigFromScreen(worldDraft.toConfig());
            }
        });
        builder.setAfterInitConsumer(JailModClothConfigScreen::addConfigFileButtons);
        return builder.build();
    }

    private static void addConfigGuide(ConfigCategory category, ConfigEntryBuilder entries) {
        addHelpText(category, entries, Component.literal("Jail Mod Configuration Guide")
                .withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD));
        addHelpText(category, entries, Component.literal(
                "\"Client Defaults\" apply to new worlds. \"This World\" settings override them for the open world.")
                .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC));

        addHelpSection(category, entries, "General");
        addHelpItem(category, entries, "Admin roles",
                "Comma-separated list of roles or tags that grant /jail access. Use 'op' to include server operators.");
        addHelpItem(category, entries, "Allow admin roles to set jail position",
                "When enabled, configured admin-role tags can use /jail set. Operators can always use it.");
        addHelpItem(category, entries, "Use previous spawn position",
                "If true, released players will be teleported to their original spawn point if Return to last location is false or unavailable.");
        addHelpItem(category, entries, "Return to last location",
                "If true, released players will be teleported back to the exact spot where they were jailed.");

        addHelpSection(category, entries, "Positions");
        addHelpItem(category, entries, "Jail position",
                "The coordinates where players are held while in jail.");
        addHelpItem(category, entries, "Release position",
                "The fallback coordinates for releasing players if no other location (spawn or last location) is used.");

        addHelpSection(category, entries, "Discord");
        addHelpItem(category, entries, "Discord webhook URL",
                "Optional Discord webhook. If empty, BanHammer's webhook can be reused when enabled in this world's settings.");
        addHelpItem(category, entries, "Use BanHammer webhook",
                "If true and the Discord webhook URL is empty, JailMod may reuse BanHammer's server-side webhook.");

        addHelpSection(category, entries, "Commands");
        addHelpText(category, entries, Component.literal("Jail and moderation")
                .withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD));
        addCommandItem(category, entries, "/jail imprison <player> <time> [reason]",
                "Jails a player. The reason is optional and defaults to Unknown reason. Requires jail staff permission.");
        addCommandItem(category, entries, "/jail info",
                "Shows your remaining sentence and reason if you are jailed.");
        addCommandItem(category, entries, "/jail list",
                "Lists jailed players with their remaining time, reason, and the staff member who jailed them. Requires jail staff permission.");
        addCommandItem(category, entries, "/jail unjail <player name|UUID>",
                "Releases an online or offline jailed player by name or UUID. Offline players are released when they next join. Requires jail staff permission.");
        addCommandItem(category, entries, "/unjail <player name|UUID>",
                "Alias for /jail unjail; also accepts offline players by name or UUID.");

        addHelpText(category, entries, Component.literal("Configuration and locations")
                .withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD));
        addCommandItem(category, entries, "/jail set",
                "Sets the jail position to your current block position. Operators can always use this; admin-role access is controlled by the setting above.");
        addCommandItem(category, entries, "/jail set <x> <y> <z>",
                "Sets the jail coordinates explicitly. Operators can always use this; admin-role access is controlled by the setting above.");
        addCommandItem(category, entries, "/jail reload",
                "Reloads config, language strings, and Discord message templates. Requires jail staff permission.");

        addHelpSection(category, entries, "Jail time format");
        addHelpItem(category, entries, "Duration",
                "Write a positive number followed immediately by a unit. A number without a unit means seconds; an unknown unit also falls back to seconds.");
        addHelpText(category, entries, Component.literal("Examples: ")
                .append(inlineCode("10"))
                .append(Component.literal("  "))
                .append(inlineCode("10s"))
                .append(Component.literal("  "))
                .append(inlineCode("10minutes"))
                .append(Component.literal("  "))
                .append(inlineCode("2h"))
                .append(Component.literal("  "))
                .append(inlineCode("1d"))
                .append(Component.literal("  "))
                .append(inlineCode("1mo"))
                .append(Component.literal("  "))
                .append(inlineCode("1wk"))
                .append(Component.literal("  "))
                .append(inlineCode("1yr")));
        addHelpItem(category, entries, "Units",
                "s/second(s), m/minute(s), h/hour(s), d/day(s), mo or mth/month(s), w or wk/week(s), and y or yr/year(s).");
    }

    private static void addHelpSection(ConfigCategory category, ConfigEntryBuilder entries, String heading) {
        addHelpText(category, entries,
                Component.literal(heading).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD));
    }

    private static void addHelpItem(ConfigCategory category, ConfigEntryBuilder entries, String name,
            String description) {
        Component text = Component.literal("• ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW).withStyle(ChatFormatting.BOLD))
                .append(Component.literal(" — " + description));
        addHelpText(category, entries, text);
    }

    private static void addHelpText(ConfigCategory category, ConfigEntryBuilder entries, Component text) {
        AbstractConfigListEntry<?> entry = entries.startTextDescription(text).build();
        entry.appendSearchTags(List.of("help", text.getString()));
        category.addEntry(entry);
    }

    private static void addCommandItem(ConfigCategory category, ConfigEntryBuilder entries, String syntax,
            String description) {
        Component text = Component.literal("• ").withStyle(ChatFormatting.GOLD)
                .append(inlineCode(syntax))
                .append(Component.literal(" — " + description));
        addHelpText(category, entries, text);
    }

    private static Component inlineCode(String code) {
        return Component.literal("`").withStyle(ChatFormatting.DARK_GRAY)
                .append(formatCommandSyntax(code))
                .append(Component.literal("`").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component formatCommandSyntax(String syntax) {
        MutableComponent formatted = Component.empty();
        Matcher matcher = COMMAND_ARGUMENT_PATTERN.matcher(syntax);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                formatted.append(Component.literal(syntax.substring(cursor, matcher.start()))
                        .withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.BOLD));
            }
            formatted.append(Component.literal(matcher.group())
                    .withStyle(ChatFormatting.YELLOW).withStyle(ChatFormatting.ITALIC));
            cursor = matcher.end();
        }
        if (cursor < syntax.length()) {
            formatted.append(Component.literal(syntax.substring(cursor))
                    .withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.BOLD));
        }
        return formatted;
    }

    private static void addConfigFileButtons(Screen screen) {
        if (!(screen instanceof ClothConfigScreen clothScreen)) {
            return;
        }

        int buttonWidth = 120;
        int buttonHeight = 20;
        int buttonX = Math.max(180, screen.width - buttonWidth - 24);
        int buttonY = 43;

        Button worldButton = Button.builder(Component.literal("Open config file"), button ->
                        openConfigFile(JailMod.getWorldConfigFile(), "world config"))
                .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                .tooltip(Tooltip.create(Component.literal("Open this world's config file")))
                .build();
        worldButton.active = JailMod.hasWorldConfigLoaded() && JailMod.getWorldConfigFile() != null;

        Button clientButton = Button.builder(Component.literal("Open config file"), button ->
                        openConfigFile(JailMod.getClientDefaultsConfigFile(), "client config"))
                .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                .tooltip(Tooltip.create(Component.literal("Open the client defaults config file")))
                .build();

        ScreenAccessor accessor = (ScreenAccessor) screen;
        accessor.jailmod$addRenderableWidget(worldButton);
        accessor.jailmod$addRenderableWidget(clientButton);
        CONFIG_FILE_BUTTONS.put(screen, new ConfigFileButtons(worldButton, clientButton));
        updateConfigFileButtonVisibility(screen);
    }

    public static void updateConfigFileButtonVisibility(Screen screen) {
        if (!(screen instanceof ClothConfigScreen clothScreen)) {
            return;
        }
        ConfigFileButtons buttons = CONFIG_FILE_BUTTONS.get(screen);
        if (buttons == null) {
            return;
        }

        Component selectedCategory = clothScreen.getSelectedCategory();
        buttons.clientButton.visible = CLIENT_DEFAULTS_CATEGORY.equals(selectedCategory);
        buttons.worldButton.visible = THIS_WORLD_CATEGORY.equals(selectedCategory);
    }

    private static void openConfigFile(File file, String description) {
        if (file == null) {
            return;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Could not create the config directory");
            }
            if (!file.exists() && !file.createNewFile()) {
                throw new IOException("Could not create the config file");
            }

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return;
            }

            String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder opener;
            if (osName.contains("win")) {
                opener = new ProcessBuilder("explorer.exe", file.getAbsolutePath());
            } else if (osName.contains("mac")) {
                opener = new ProcessBuilder("open", file.getAbsolutePath());
            } else {
                opener = new ProcessBuilder("xdg-open", file.getAbsolutePath());
            }
            opener.start();
        } catch (IOException | UnsupportedOperationException exception) {
            System.err.println("[Jail Mod] Could not open " + description + " file: " + exception.getMessage());
        }
    }

    private static void addSettings(ConfigCategory category, ConfigEntryBuilder entries, ConfigDraft draft,
            boolean editable) {
        SubCategoryBuilder general = entries.startSubCategory(Component.literal("General")).setExpanded(true);
        addEntry(general, entries.startStrField(Component.literal("Admin roles"), draft.adminRoles[0])
                .setDefaultValue("op")
                .setTooltip(Component.literal("Comma-separated player tags that can use jail commands. Use 'op' for operators."))
                .setSaveConsumer(value -> draft.adminRoles[0] = value)
                .build(), editable);
        addEntry(general, entries.startBooleanToggle(Component.literal("Allow admin roles to set jail position"),
                draft.allowAdminRoleSetJailPosition[0])
                .setDefaultValue(false)
                .setTooltip(Component.literal(
                        "Allows players with a configured admin role to use /jail set. Operators can always use it."))
                .setSaveConsumer(value -> draft.allowAdminRoleSetJailPosition[0] = value)
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

    private record ConfigFileButtons(Button worldButton, Button clientButton) {
    }

    private static final class ConfigDraft {
        private final String[] adminRoles;
        private final boolean[] allowAdminRoleSetJailPosition;
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
            allowAdminRoleSetJailPosition = new boolean[] { config.allow_admin_role_set_jail_position };
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
            config.allow_admin_role_set_jail_position = allowAdminRoleSetJailPosition[0];
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
