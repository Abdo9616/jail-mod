package com.example.jailmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

public class JailMod implements ModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/jailmod/config.json");
    private static final File TOML_CONFIG_FILE = new File("config/jailmod/config.toml");
    private static final File LANGUAGE_FILE = new File("config/jailmod/language.txt");
    private static final File DEFAULT_JAIL_DATA_FILE = new File("config/jailmod/jail_data.json");
    private static volatile Config config = new Config();
    private static volatile Config clientDefaultsConfig = new Config();
    private static volatile Config worldConfig;
    private static File activeConfigFile = CONFIG_FILE;
    private static File activeTomlConfigFile = TOML_CONFIG_FILE;
    private static volatile File worldConfigFile;
    private static volatile File worldTomlConfigFile;
    private static volatile File activeJailDataFile;
    private static Map<String, String> languageStrings = new HashMap<>();
    private static Map<UUID, JailData> jailedPlayers = new HashMap<>();

    private static MinecraftServer serverInstance;
    private static DiscordNotifier discordNotifier;
    private static final SuggestionProvider<CommandSourceStack> JAILED_PLAYERS_SUGGESTIONS = (context, builder) -> {
        MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }

        for (UUID uuid : jailedPlayers.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                builder.suggest(player.getName().getString());
            }
        }
        return builder.buildFuture();
    };
    private static ConfigFormat configFormat = ConfigFormat.JSON;

    private enum ConfigFormat {
        JSON,
        TOML
    }

    // Configuration class
    public static class Config {
        public String _config_guide = "JailMod Configuration Guide: \n" +
                "- admin_roles: Comma-separated list of roles or tags that grant /jail access. Use 'op' to include server operators.\n"
                +
                "- use_previous_position: If true, released players will be teleported to their original spawn point (if return_to_last_location is false or unavailable).\n"
                +
                "- return_to_last_location: If true, released players will be teleported back to the exact spot where they were jailed.\n"
                +
                "- jail_position: The coordinates where players are held while in jail.\n" +
                "- release_position: The fallback coordinates for releasing players if no other location (spawn/last) is used.\n" +
                "- discord_webhook_url: Optional Discord webhook. If empty, BanHammer's webhook can be reused when enabled in this world's settings.\n" +
                "- use_banhammer_webhook: If true and discord_webhook_url is empty, JailMod may reuse BanHammer's server-side webhook.";
        public String admin_roles = "op"; // Comma-separated list of roles/tags that grant admin access. "op" refers to
                                          // operator status.
        public boolean use_previous_position = true; // Use spawn point as fallback
        public boolean return_to_last_location = true; // Return to the exact spot where jailed
        public Position release_position = new Position(100, 65, 100);
        public Position jail_position = new Position(0, 60, 0);
        public String discord_webhook_url = "";
        // Keep config key as use_banhammer_webhook while also accepting old sendJailMessage.
        @SerializedName(value = "use_banhammer_webhook", alternate = { "sendJailMessage" })
        public boolean useBanhammerWebhook = false;

        public static class Position {
            public int x;
            public int y;
            public int z;

            public Position(int x, int y, int z) {
                this.x = x;
                this.y = y;
                this.z = z;
            }
        }
    }

    // Class to store jailed players with release time in ticks
    private static class JailData {
        public UUID playerUUID;
        public BlockPos originalSpawnPos;
        public ResourceKey<Level> originalSpawnDimension;
        public boolean hadSpawnPoint;
        public String reason;
        public String jailedBy;
        public long remainingTicks; // Remaining time in ticks (1 second = 20 ticks)

        // Last location data
        public double lastX;
        public double lastY;
        public double lastZ;
        public float lastYaw;
        public float lastPitch;
        public String lastDimension;

        // Frozen stats snapshot (captured on first jail)
        public boolean hasStatSnapshot;
        public float savedHealth;
        public int savedFoodLevel;
        public float savedSaturationLevel;
        public int savedAir;
        public float savedAbsorption;
        public int savedXpLevel;
        public float savedXpProgress;
        public int savedTotalXp;
        public boolean savedInvulnerable;
        public int savedFireTicks;

        public List<MobEffectSnapshot> savedEffects;
    }

    private static class MobEffectSnapshot {
        public String effectId;
        public int duration;
        public int amplifier;
        public boolean ambient;
        public boolean showParticles;
        public boolean showIcon;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            if (!server.isDedicatedServer()) {
                loadWorldConfig(server);
            }
            activeJailDataFile = server.isDedicatedServer()
                    ? DEFAULT_JAIL_DATA_FILE
                    : server.getWorldPath(LevelResource.ROOT)
                            .resolve("serverconfig/jailmod/jail_data.json").toFile();
            loadJailData();
            Component message = Component.literal("[Jail-Mod] Loaded")
                    .withStyle(style -> style.withColor(0x00FF00).withBold(true));
            server.sendSystemMessage(message);
        });

        loadConfig();
        loadLanguage();
        discordNotifier = new DiscordNotifier();
        discordNotifier.reload();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<UUID> playersToRelease = new ArrayList<>();
            for (Map.Entry<UUID, JailData> entry : jailedPlayers.entrySet()) {
                UUID playerUUID = entry.getKey();
                JailData jailData = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                if (player != null) {
                    jailData.remainingTicks--;
                    applyFrozenStats(player, jailData);
                    if (jailData.remainingTicks <= 0) {
                        playersToRelease.add(playerUUID);
                    }
                }
            }
            for (UUID playerUUID : playersToRelease) {
                releasePlayer(playerUUID);
            }
        });

        registerInteractionListeners();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID playerUUID = player.getUUID();
            if (jailedPlayers.containsKey(playerUUID)) {
                JailData jailData = jailedPlayers.get(playerUUID);
                if (jailData.remainingTicks > 0) {
                    jailPlayer(player, jailData);
                } else {
                    releasePlayer(playerUUID);
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("jail")
                    .then(Commands.literal("imprison")
                            .requires(source -> hasAdminPermission(source))
                            .then(Commands.argument("player", EntityArgument.player())
                                    .then(Commands.argument("time", StringArgumentType.word())
                                            .executes(context -> executeImprisonCommand(context, null))
                                            .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                    .executes(context -> executeImprisonCommand(context,
                                                            StringArgumentType.getString(context, "reason")))))))
                    .then(Commands.literal("reload")
                            .requires(source -> hasAdminPermission(source))
                            .executes(context -> {
                                loadConfig();
                                loadLanguage();
                                discordNotifier.reload();
                                context.getSource().sendSuccess(
                                        () -> Component.literal(
                                                "Configuration, language strings, and Discord message templates successfully reloaded!"),
                                        true);
                                return 1;
                            }))
                    .then(Commands.literal("set")
                            .requires(source -> hasAdminPermission(source))
                            .then(Commands.argument("x", IntegerArgumentType.integer())
                                    .then(Commands.argument("y", IntegerArgumentType.integer())
                                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");

                                                        config.jail_position = new Config.Position(x, y, z);
                                                        if (worldConfigFile != null) {
                                                            worldConfig = copyConfig(config);
                                                        }
                                                        saveConfig();

                                                        context.getSource()
                                                                .sendSuccess(() -> Component.literal("Jail position set to (" + x
                                                                        + ", " + y + ", " + z + ")"), true);
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("info")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null && isPlayerInJail(player)) {
                                    JailData jailData = jailedPlayers.get(player.getUUID());
                                    String remainingTime = formatDuration(jailData.remainingTicks / 20L);
                                    String reason = jailData.reason == null ? "Unknown reason" : jailData.reason;
                                    String jailedBy = jailData.jailedBy == null ? "Unknown" : jailData.jailedBy;
                                    Component message = styledTemplate(languageStrings.get("jail_info_message"),
                                            Map.of("time", remainingTime, "actor", jailedBy, "reason", reason),
                                            Map.of("time", ChatFormatting.YELLOW, "actor", ChatFormatting.GOLD,
                                                    "reason", ChatFormatting.YELLOW));
                                    player.sendSystemMessage(message, false);
                                    return 1;
                                } else {
                                    String notInJailMessage = languageStrings.get("not_in_jail_message");
                                    context.getSource().sendSuccess(() -> Component.literal(notInJailMessage), false);
                                    return 0;
                                }
                            })));

            dispatcher.register(Commands.literal("unjail")
                    .requires(source -> hasAdminPermission(source))
                    .then(Commands.argument("player", EntityArgument.player())
                            .suggests(JAILED_PLAYERS_SUGGESTIONS)
                            .executes(context -> {
                                ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                if (player != null) {
                                    if (!isPlayerInJail(player)) {
                                        context.getSource().sendFailure(Component.literal("Player "
                                                + player.getName().getString() + " is not jailed.")
                                                .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    unjailPlayer(player, true, context.getSource().getTextName(),
                                            context.getSource());
                                } else {
                                    context.getSource().sendFailure(Component.literal("Player not found!"));
                                }
                                return 1;
                            })));
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (serverInstance == server) {
                saveJailData();
                jailedPlayers.clear();
                activeJailDataFile = null;
                if (!server.isDedicatedServer()) {
                    if (worldConfigFile != null) {
                        saveConfig();
                    }
                    worldConfig = null;
                    worldConfigFile = null;
                    worldTomlConfigFile = null;
                    activeConfigFile = CONFIG_FILE;
                    activeTomlConfigFile = TOML_CONFIG_FILE;
                    configFormat = TOML_CONFIG_FILE.exists() ? ConfigFormat.TOML : ConfigFormat.JSON;
                    config = copyConfig(clientDefaultsConfig);
                }
                serverInstance = null;
            }
        });
    }

    private int executeImprisonCommand(CommandContext<CommandSourceStack> context, String suppliedReason)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String durationText = StringArgumentType.getString(context, "time");
        long durationSeconds;
        try {
            durationSeconds = parseDurationSeconds(durationText);
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }

        String reason = suppliedReason == null || suppliedReason.isBlank() ? "Unknown reason" : suppliedReason;
        try {
            jailPlayer(player, durationSeconds, reason, context.getSource().getTextName(), true,
                    context.getSource());
        } catch (ArithmeticException e) {
            context.getSource().sendFailure(Component.literal("That jail duration is too large."));
            return 0;
        }
        return 1;
    }

    private static long parseDurationSeconds(String input) {
        String duration = input.toLowerCase(Locale.ROOT);
        int unitStart = 0;
        while (unitStart < duration.length() && Character.isDigit(duration.charAt(unitStart))) {
            unitStart++;
        }
        if (unitStart == 0) {
            throw invalidDuration(input);
        }

        long amount;
        try {
            amount = Long.parseLong(duration.substring(0, unitStart));
        } catch (NumberFormatException e) {
            throw invalidDuration(input);
        }
        if (amount <= 0) {
            throw invalidDuration(input);
        }

        String unit = duration.substring(unitStart);
        long secondsPerUnit = switch (unit) {
            case "", "s", "second", "seconds" -> 1L;
            case "m", "minute", "minutes" -> 60L;
            case "h", "hour", "hours" -> 60L * 60L;
            case "d", "day", "days" -> 24L * 60L * 60L;
            case "mo", "mos", "mth", "mths", "month", "months" -> 30L * 24L * 60L * 60L;
            case "w", "wk", "wks", "week", "weeks" -> 7L * 24L * 60L * 60L;
            case "y", "yr", "yrs", "year", "years" -> 365L * 24L * 60L * 60L;
            default -> 1L; // Unknown suffixes are treated as seconds.
        };

        try {
            long seconds = Math.multiplyExact(amount, secondsPerUnit);
            Math.multiplyExact(seconds, 20L); // Make sure the duration fits in stored ticks.
            return seconds;
        } catch (ArithmeticException e) {
            throw invalidDuration(input);
        }
    }

    private static IllegalArgumentException invalidDuration(String input) {
        return new IllegalArgumentException("Invalid jail duration '" + input
                + "'. Use a positive number followed by a supported unit; bare numbers mean seconds.");
    }

    static String formatDuration(long durationSeconds) {
        long remainingSeconds = Math.max(0L, durationSeconds);
        List<String> parts = new ArrayList<>();
        remainingSeconds = appendDurationPart(parts, remainingSeconds, 365L * 24L * 60L * 60L, "year");
        remainingSeconds = appendDurationPart(parts, remainingSeconds, 30L * 24L * 60L * 60L, "month");
        remainingSeconds = appendDurationPart(parts, remainingSeconds, 24L * 60L * 60L, "day");
        remainingSeconds = appendDurationPart(parts, remainingSeconds, 60L * 60L, "hour");
        remainingSeconds = appendDurationPart(parts, remainingSeconds, 60L, "minute");
        appendDurationPart(parts, remainingSeconds, 1L, "second");
        return parts.isEmpty() ? "0 seconds" : String.join(" ", parts);
    }

    private static long appendDurationPart(List<String> parts, long remainingSeconds, long secondsPerUnit,
            String unit) {
        long amount = remainingSeconds / secondsPerUnit;
        if (amount > 0) {
            parts.add(amount + " " + unit + (amount == 1 ? "" : "s"));
        }
        return remainingSeconds % secondsPerUnit;
    }

    private static Component styledTemplate(String template, Map<String, String> replacements,
            Map<String, ChatFormatting> colors) {
        MutableComponent message = Component.empty();
        int cursor = 0;
        while (cursor < template.length()) {
            int placeholderStart = template.indexOf('{', cursor);
            if (placeholderStart < 0) {
                message.append(Component.literal(template.substring(cursor)).withStyle(ChatFormatting.WHITE));
                break;
            }

            int placeholderEnd = template.indexOf('}', placeholderStart + 1);
            if (placeholderEnd < 0) {
                message.append(Component.literal(template.substring(cursor)).withStyle(ChatFormatting.WHITE));
                break;
            }

            if (placeholderStart > cursor) {
                message.append(Component.literal(template.substring(cursor, placeholderStart))
                        .withStyle(ChatFormatting.WHITE));
            }

            String placeholder = template.substring(placeholderStart + 1, placeholderEnd);
            String replacement = replacements.get(placeholder);
            if (replacement == null) {
                message.append(Component.literal(template.substring(placeholderStart, placeholderEnd + 1))
                        .withStyle(ChatFormatting.WHITE));
            } else {
                message.append(Component.literal(replacement)
                        .withStyle(colors.getOrDefault(placeholder, ChatFormatting.WHITE)));
            }
            cursor = placeholderEnd + 1;
        }
        return message;
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            // Includes singleplayer hosts and dedicated-server operators.
            if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                return true;
            }

            // Very stable OP check: compare player name against the list of OP names
            // This bypasses mapping issues with hasPermissionLevel or isOperator
            String playerName = player.getName().getString();
            for (String opName : source.getServer().getPlayerList().getOpNames()) {
                if (opName.equalsIgnoreCase(playerName)) {
                    return true;
                }
            }

            // Check for custom admin roles/tags from config
            String rolesString = config.admin_roles;
            if (rolesString != null && !rolesString.isEmpty()) {
                String[] roles = rolesString.split(",");
                for (String role : roles) {
                    String trimmedRole = role.trim();
                    if (!trimmedRole.equalsIgnoreCase("op") && player.entityTags().contains(trimmedRole)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // Allow console and non-player sources by default
        return true;
    }

    private void registerInteractionListeners() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("entity_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("entity_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                ItemStack itemStack = player.getItemInHand(hand);

                if (itemStack.is(Items.LAVA_BUCKET) || itemStack.is(Items.WATER_BUCKET)) {
                    serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("bucket_use_denied")), true);
                    return InteractionResult.FAIL;
                }

                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("item_use_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_break_denied")), true);
                return false;
            }
            return true;
        });
    }

    public static boolean isPlayerInJail(ServerPlayer player) {
        return player != null && jailedPlayers.containsKey(player.getUUID());
    }

    public static Config getConfig() {
        return copyConfig(config);
    }

    public static Config getClientDefaultsConfig() {
        return copyConfig(clientDefaultsConfig);
    }

    public static Config getWorldConfig() {
        return worldConfig == null || worldConfigFile == null ? null : copyConfig(worldConfig);
    }

    public static boolean hasWorldConfigLoaded() {
        return worldConfig != null && worldConfigFile != null;
    }

    public static void saveClientDefaultsFromScreen(Config updatedConfig) {
        if (updatedConfig == null) {
            return;
        }
        clientDefaultsConfig = copyConfig(updatedConfig);
        if (isClientEnvironment()) {
            clientDefaultsConfig.useBanhammerWebhook = false;
        }
        saveConfigToFiles(clientDefaultsConfig, CONFIG_FILE, TOML_CONFIG_FILE);
    }

    public static void saveWorldConfigFromScreen(Config updatedConfig) {
        if (updatedConfig == null || worldConfigFile == null || worldTomlConfigFile == null) {
            return;
        }
        worldConfig = copyConfig(updatedConfig);
        config = copyConfig(worldConfig);
        configFormat = worldTomlConfigFile.exists() ? ConfigFormat.TOML : ConfigFormat.JSON;
        saveConfigToFiles(worldConfig, worldConfigFile, worldTomlConfigFile);
    }

    private void jailPlayer(ServerPlayer player, long timeInSeconds, String reason, String actorName,
            boolean notifyWebhook, CommandSourceStack commandSource) {
        if (actorName == null || actorName.isEmpty()) {
            actorName = "system";
        }
        ServerPlayer actor = commandSource != null && commandSource.getEntity() instanceof ServerPlayer commandPlayer
                ? commandPlayer
                : null;
        long addedTicks = Math.multiplyExact(timeInSeconds, 20L); // Convert seconds to ticks
        JailData existingData = jailedPlayers.get(player.getUUID());
        if (existingData != null) {
            existingData.remainingTicks = Math.addExact(existingData.remainingTicks, addedTicks);
            existingData.reason = reason;
            existingData.jailedBy = actorName;

            applyFrozenStats(player, existingData);
            saveJailData();
            sendJailConfirmation(commandSource, player, timeInSeconds, true);
            sendTimeAddedMessages(player, existingData, timeInSeconds, actor);

            if (notifyWebhook) {
                discordNotifier.sendJailMessage(config, player.getName().getString(), reason,
                        Math.max(0L, existingData.remainingTicks / 20), actorName);
            }

            return;
        }

        // Save the player's original spawn position
        BlockPos originalSpawnPos = null;
        ResourceKey<Level> originalSpawnDimension = null;
        var respawn = player.getRespawnConfig();
        if (respawn != null && respawn.respawnData() != null) {
            originalSpawnPos = respawn.respawnData().pos();
            originalSpawnDimension = respawn.respawnData().dimension();
        }
        boolean hadSpawnPoint = originalSpawnPos != null;

        // Capture current position before teleporting
        double lastX = player.getX();
        double lastY = player.getY();
        double lastZ = player.getZ();
        float lastYaw = player.getYRot();
        float lastPitch = player.getXRot();
        String lastDimension = player.level().dimension().identifier().toString();

        // Save player data
        JailData jailData = new JailData();
        jailData.playerUUID = player.getUUID();
        jailData.originalSpawnPos = originalSpawnPos;
        jailData.originalSpawnDimension = originalSpawnDimension;
        jailData.hadSpawnPoint = hadSpawnPoint;
        jailData.reason = reason;
        jailData.jailedBy = actorName;
        jailData.remainingTicks = addedTicks;
        jailData.lastX = lastX;
        jailData.lastY = lastY;
        jailData.lastZ = lastZ;
        jailData.lastYaw = lastYaw;
        jailData.lastPitch = lastPitch;
        jailData.lastDimension = lastDimension;
        captureStatSnapshot(player, jailData);

        jailedPlayers.put(player.getUUID(), jailData);

        jailPlayer(player, jailData, commandSource, actor, timeInSeconds);

        saveJailData();

        if (notifyWebhook) {
            discordNotifier.sendJailMessage(config, player.getName().getString(), reason,
                    Math.max(0L, jailData.remainingTicks / 20), actorName);
        }

    }

    private void jailPlayer(ServerPlayer player, JailData jailData) {
        jailPlayer(player, jailData, null, null, 0L);
    }

    private void jailPlayer(ServerPlayer player, JailData jailData, CommandSourceStack commandSource,
            ServerPlayer actor, long addedSeconds) {
        ServerLevel world = (ServerLevel) player.level();

        BlockPos jailPos = new BlockPos(config.jail_position.x, config.jail_position.y, config.jail_position.z);
        player.teleportTo(world, jailPos.getX() + 0.5, jailPos.getY(), jailPos.getZ() + 0.5,
                EnumSet.noneOf(Relative.class), player.getYRot(), player.getXRot(), false);

        applyFrozenStats(player, jailData);

        sendJailConfirmation(commandSource, player, addedSeconds, false);

        // TODO: Fix setSpawnPoint
        // player.setSpawnPoint(new ServerPlayer.Respawn(new
        // SpawnPoint(world.getRegistryKey(), jailPos, 0.0f, true), true), true);

        String reason = jailData.reason == null ? "Unknown reason" : jailData.reason;
        String jailedBy = jailData.jailedBy == null ? "Unknown" : jailData.jailedBy;
        String remainingTime = formatDuration(jailData.remainingTicks / 20L);
        Component messageToPlayer = styledTemplate(languageStrings.get("jail_player"),
                Map.of("time", remainingTime, "reason", reason, "actor", jailedBy),
                Map.of("time", ChatFormatting.YELLOW, "reason", ChatFormatting.YELLOW, "actor", ChatFormatting.GOLD));
        player.sendSystemMessage(messageToPlayer, false);

        Component jailMessage = styledTemplate(languageStrings.get("jail_broadcast"),
                Map.of("player", player.getName().getString(), "actor", jailedBy, "time", remainingTime,
                        "reason", reason),
                Map.of("player", ChatFormatting.RED, "actor", ChatFormatting.GOLD, "time", ChatFormatting.YELLOW,
                        "reason", ChatFormatting.YELLOW));
        broadcastToOtherPlayers(jailMessage, player, actor);
    }

    private void sendJailConfirmation(CommandSourceStack commandSource, ServerPlayer jailedPlayer,
            long addedSeconds, boolean wasAlreadyJailed) {
        if (commandSource == null || commandSource.getEntity() == jailedPlayer) {
            return;
        }

        String confirmation = wasAlreadyJailed
                ? "Updated jail time for {player} by {time}."
                : "Jailed {player} for {time}.";
        commandSource.sendSuccess(
                () -> styledTemplate(confirmation,
                        Map.of("player", jailedPlayer.getName().getString(), "time", formatDuration(addedSeconds)),
                        Map.of("player", ChatFormatting.RED, "time", ChatFormatting.YELLOW)),
                false);
    }

    private void broadcastToOtherPlayers(Component message, ServerPlayer jailedPlayer, ServerPlayer actor) {
        for (ServerPlayer recipient : serverInstance.getPlayerList().getPlayers()) {
            if (recipient != jailedPlayer && recipient != actor) {
                recipient.sendSystemMessage(message, false);
            }
        }
    }

    private void unjailPlayer(ServerPlayer player, boolean isManual, String actorName) {
        unjailPlayer(player, isManual, actorName, null);
    }

    private void unjailPlayer(ServerPlayer player, boolean isManual, String actorName,
            CommandSourceStack commandSource) {
        JailData jailData = jailedPlayers.remove(player.getUUID());

        if (jailData != null) {
            // TODO: Restore spawn point logic
            /*
             * if (jailData.hadSpawnPoint && jailData.originalSpawnPos != null) {
             * player.setSpawnPoint(new ServerPlayer.Respawn(new
             * SpawnPoint(jailData.originalSpawnDimension, jailData.originalSpawnPos, 0.0f,
             * true), true), false);
             * } else {
             * player.setSpawnPoint(null, false);
             * }
             */

            ServerLevel world = (ServerLevel) player.level();

            // Teleport the player out of jail (release position)
            if (config.return_to_last_location && jailData.lastDimension != null) {
                ServerLevel targetWorld = null;
                Identifier lastDimensionId = Identifier.tryParse(jailData.lastDimension);
                if (lastDimensionId != null) {
                    // TODO(JM-261): Re-verify DIMENSION registry mapping on future Mojang mapping updates.
                    targetWorld = serverInstance.getLevel(ResourceKey.create(Registries.DIMENSION, lastDimensionId));
                }
                if (targetWorld == null) {
                    targetWorld = world;
                }
                player.teleportTo(targetWorld, jailData.lastX, jailData.lastY, jailData.lastZ,
                        EnumSet.noneOf(Relative.class), jailData.lastYaw, jailData.lastPitch, false);
            } else if (config.use_previous_position && jailData.hadSpawnPoint) {
                ServerLevel spawnWorld = world;
                if (jailData.originalSpawnDimension != null) {
                    ServerLevel configuredSpawnWorld = serverInstance.getLevel(jailData.originalSpawnDimension);
                    if (configuredSpawnWorld != null) {
                        spawnWorld = configuredSpawnWorld;
                    }
                }
                player.teleportTo(spawnWorld, jailData.originalSpawnPos.getX(), jailData.originalSpawnPos.getY(),
                        jailData.originalSpawnPos.getZ(), EnumSet.noneOf(Relative.class), player.getYRot(),
                        player.getXRot(), false);
            } else {
                BlockPos releasePos = new BlockPos(config.release_position.x, config.release_position.y,
                        config.release_position.z);
                player.teleportTo(world, releasePos.getX() + 0.5, releasePos.getY(), releasePos.getZ() + 0.5,
                        EnumSet.noneOf(Relative.class), player.getYRot(), player.getXRot(), false);
            }

            restoreStatsAfterJail(player, jailData);
            ServerPlayer actor = commandSource != null && commandSource.getEntity() instanceof ServerPlayer commandPlayer
                    ? commandPlayer
                    : null;

            if (isManual) {
                sendUnjailConfirmation(commandSource, player);

                String messageToPlayer = languageStrings.get("unjail_player_manual");
                player.sendSystemMessage(Component.literal(messageToPlayer), false);

                Component broadcastMessage = styledTemplate(languageStrings.get("unjail_broadcast_manual"),
                        Map.of("player", player.getName().getString()),
                        Map.of("player", ChatFormatting.RED));
                broadcastToOtherPlayers(broadcastMessage, player, actor);
                discordNotifier.sendUnjailMessage(config, player.getName().getString(), jailData.reason, actorName,
                        true);
            } else {
                String messageToPlayer = languageStrings.get("unjail_player_auto");
                player.sendSystemMessage(Component.literal(messageToPlayer), false);

                Component broadcastMessage = styledTemplate(languageStrings.get("unjail_broadcast_auto"),
                        Map.of("player", player.getName().getString()),
                        Map.of("player", ChatFormatting.RED));
                broadcastToOtherPlayers(broadcastMessage, player, null);
                discordNotifier.sendUnjailMessage(config, player.getName().getString(), jailData.reason, actorName,
                        false);
            }

            saveJailData();
        }
    }

    private void captureStatSnapshot(ServerPlayer player, JailData jailData) {
        jailData.savedHealth = player.getHealth();
        var hunger = player.getFoodData();
        jailData.savedFoodLevel = hunger.getFoodLevel();
        jailData.savedSaturationLevel = hunger.getSaturationLevel();
        jailData.savedAir = player.getAirSupply();
        jailData.savedAbsorption = player.getAbsorptionAmount();
        jailData.savedXpLevel = player.experienceLevel;
        jailData.savedXpProgress = player.experienceProgress;
        jailData.savedTotalXp = player.totalExperience;
        jailData.savedInvulnerable = player.getAbilities().invulnerable;
        jailData.savedFireTicks = player.getRemainingFireTicks();
        jailData.hasStatSnapshot = true;
        captureEffectSnapshot(player, jailData);
    }

    private void applyFrozenStats(ServerPlayer player, JailData jailData) {
        if (!jailData.hasStatSnapshot) {
            captureStatSnapshot(player, jailData);
            saveJailData();
        }

        if (!player.getAbilities().invulnerable) {
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
        }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) {
            targetHealth = player.getMaxHealth();
        }
        player.setHealth(targetHealth);

        var hunger = player.getFoodData();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturation(jailData.savedSaturationLevel);

        player.setAirSupply(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);

        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        if (player.getRemainingFireTicks() != 0) {
            player.setRemainingFireTicks(0);
        }

        applyFrozenEffects(player, jailData);
    }

    private void restoreStatsAfterJail(ServerPlayer player, JailData jailData) {
        if (!jailData.hasStatSnapshot) {
            return;
        }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) {
            targetHealth = player.getMaxHealth();
        }
        player.setHealth(targetHealth);

        var hunger = player.getFoodData();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturation(jailData.savedSaturationLevel);

        player.setAirSupply(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);

        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        player.getAbilities().invulnerable = jailData.savedInvulnerable;
        player.onUpdateAbilities();

        if (jailData.savedFireTicks > 0) {
            player.setRemainingFireTicks(jailData.savedFireTicks);
        } else if (player.getRemainingFireTicks() != 0) {
            player.setRemainingFireTicks(0);
        }

        restoreEffectsAfterJail(player, jailData);
    }

    private void captureEffectSnapshot(ServerPlayer player, JailData jailData) {
        jailData.savedEffects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            Holder<MobEffect> effectType = effect.getEffect();
            var effectKey = effectType.unwrapKey().orElse(null);
            if (effectKey == null) {
                continue;
            }
            MobEffectSnapshot snapshot = new MobEffectSnapshot();
            snapshot.effectId = effectKey.identifier().toString();
            snapshot.duration = effect.getDuration();
            snapshot.amplifier = effect.getAmplifier();
            snapshot.ambient = effect.isAmbient();
            snapshot.showParticles = effect.isVisible();
            snapshot.showIcon = effect.showIcon();
            jailData.savedEffects.add(snapshot);
        }
    }

    private void applyFrozenEffects(ServerPlayer player, JailData jailData) {
        if (jailData.savedEffects == null) {
            captureEffectSnapshot(player, jailData);
            saveJailData();
        }

        if (jailData.savedEffects == null) {
            return;
        }

        Set<Identifier> snapshotIds = new HashSet<>();
        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId != null) {
                Identifier snapshotId = Identifier.tryParse(snapshot.effectId);
                if (snapshotId != null) {
                    snapshotIds.add(snapshotId);
                }
            }
        }

        if (!player.getActiveEffects().isEmpty()) {
            List<MobEffectInstance> currentEffects = new ArrayList<>(player.getActiveEffects());
            for (MobEffectInstance current : currentEffects) {
                Holder<MobEffect> currentType = current.getEffect();
                var currentKey = currentType.unwrapKey().orElse(null);
                Identifier currentId = currentKey != null ? currentKey.identifier() : null;
                if (currentId == null || !snapshotIds.contains(currentId)) {
                    player.removeEffect(currentType);
                }
            }
        }

        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) {
                continue;
            }
            Identifier effectId = Identifier.tryParse(snapshot.effectId);
            if (effectId == null) {
                continue;
            }
            Holder<MobEffect> statusEffect = BuiltInRegistries.MOB_EFFECT.get(effectId).orElse(null);
            if (statusEffect == null) {
                continue;
            }
            MobEffectInstance instance = new MobEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addEffect(instance);
        }
    }

    private void restoreEffectsAfterJail(ServerPlayer player, JailData jailData) {
        if (jailData.savedEffects == null) {
            return;
        }

        player.removeAllEffects();
        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) {
                continue;
            }
            Identifier effectId = Identifier.tryParse(snapshot.effectId);
            if (effectId == null) {
                continue;
            }
            Holder<MobEffect> statusEffect = BuiltInRegistries.MOB_EFFECT.get(effectId).orElse(null);
            if (statusEffect == null) {
                continue;
            }
            MobEffectInstance instance = new MobEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addEffect(instance);
        }
    }

    private void sendTimeAddedMessages(ServerPlayer player, JailData jailData, long addedSeconds,
            ServerPlayer actor) {
        long remainingSeconds = Math.max(0L, jailData.remainingTicks / 20);
        String reason = jailData.reason == null ? "Unknown reason" : jailData.reason;
        String jailedBy = jailData.jailedBy == null ? "Unknown" : jailData.jailedBy;
        Component messageToPlayer = styledTemplate(languageStrings.get("jail_time_added_player"),
                Map.of("added", formatDuration(addedSeconds), "time", formatDuration(remainingSeconds),
                        "reason", reason, "actor", jailedBy),
                Map.of("added", ChatFormatting.YELLOW, "time", ChatFormatting.YELLOW,
                        "reason", ChatFormatting.YELLOW, "actor", ChatFormatting.GOLD));
        player.sendSystemMessage(messageToPlayer, false);

        Component broadcastMessage = styledTemplate(languageStrings.get("jail_time_added_broadcast"),
                Map.of("player", player.getName().getString(), "added", formatDuration(addedSeconds),
                        "time", formatDuration(remainingSeconds), "reason", reason, "actor", jailedBy),
                Map.of("player", ChatFormatting.RED, "added", ChatFormatting.YELLOW, "time", ChatFormatting.YELLOW,
                        "reason", ChatFormatting.YELLOW, "actor", ChatFormatting.GOLD));
        broadcastToOtherPlayers(broadcastMessage, player, actor);
    }

    // [Rest of file is identical]
    private void releasePlayer(UUID playerUUID) {
        ServerPlayer player = serverInstance.getPlayerList().getPlayer(playerUUID);
        if (player != null) {
            unjailPlayer(player, false, "system");
        }
    }

    // Load jail status from file
    private void loadJailData() {
        File dataFile = activeJailDataFile;
        jailedPlayers.clear();
        if (dataFile == null || !dataFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            JailData[] loadedData = GSON.fromJson(reader, JailData[].class);
            if (loadedData != null) {
                for (JailData data : loadedData) {
                    if (data != null && data.playerUUID != null) {
                        jailedPlayers.put(data.playerUUID, data);
                    }
                }
            }
            System.out.println("Jail status loaded: " + dataFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendUnjailConfirmation(CommandSourceStack commandSource, ServerPlayer releasedPlayer) {
        if (commandSource == null || commandSource.getEntity() == releasedPlayer) {
            return;
        }

        commandSource.sendSuccess(
                () -> styledTemplate("Player {player} has been released from jail.",
                        Map.of("player", releasedPlayer.getName().getString()),
                        Map.of("player", ChatFormatting.RED)),
                false);
    }

    // Save jail status to file
    private void saveJailData() {
        File dataFile = activeJailDataFile;
        if (dataFile == null) {
            return;
        }
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(dataFile)) {
            GSON.toJson(jailedPlayers.values().toArray(new JailData[0]), writer);
            System.out.println("Jail status saved: " + dataFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        Config defaults = worldConfigFile == null ? new Config() : clientDefaultsConfig;
        loadConfigFromFiles(activeConfigFile, activeTomlConfigFile, defaults);
        if (worldConfigFile == null) {
            if (isClientEnvironment()) {
                // BanHammer only runs on the server. Do not carry an old client-side toggle forward.
                config.useBanhammerWebhook = false;
                saveConfig();
            }
            clientDefaultsConfig = copyConfig(config);
        } else {
            worldConfig = copyConfig(config);
        }
    }

    private void loadWorldConfig(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        worldConfigFile = worldRoot.resolve("serverconfig/jailmod/config.json").toFile();
        worldTomlConfigFile = worldRoot.resolve("serverconfig/jailmod/config.toml").toFile();
        loadConfigFromFiles(worldConfigFile, worldTomlConfigFile, clientDefaultsConfig);
        worldConfig = copyConfig(config);
        System.out.println("World configuration loaded: " + activeConfigFile.getAbsolutePath());
    }

    private void loadConfigFromFiles(File jsonFile, File tomlFile, Config defaults) {
        activeConfigFile = jsonFile;
        activeTomlConfigFile = tomlFile;
        File parent = jsonFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Config defaultConfig = copyConfig(defaults);
        if (tomlFile.exists()) {
            configFormat = ConfigFormat.TOML;
            config = loadTomlConfig(tomlFile, defaultConfig);
            if (config == null) {
                config = defaultConfig;
            }
            saveConfig();
            System.out.println("Configuration loaded and patched: " + tomlFile.getAbsolutePath());
            return;
        }

        configFormat = ConfigFormat.JSON;
        if (!jsonFile.exists()) {
            config = defaultConfig;
            saveConfig();
            System.out.println("Default config file created: " + jsonFile.getAbsolutePath());
            return;
        }

        try (FileReader reader = new FileReader(jsonFile)) {
            StringBuilder jsonContent = new StringBuilder();
            int i;
            while ((i = reader.read()) != -1) {
                jsonContent.append((char) i);
            }

            Config loadedConfig = GSON.fromJson(jsonContent.toString(), Config.class);
            if (loadedConfig == null) {
                loadedConfig = defaultConfig;
            } else {
                if (loadedConfig._config_guide == null) {
                    loadedConfig._config_guide = defaultConfig._config_guide;
                }
                if (loadedConfig.admin_roles == null) {
                    loadedConfig.admin_roles = defaultConfig.admin_roles;
                }
                if (loadedConfig.jail_position == null) {
                    loadedConfig.jail_position = copyPosition(defaultConfig.jail_position);
                }
                if (loadedConfig.release_position == null) {
                    loadedConfig.release_position = copyPosition(defaultConfig.release_position);
                }
                String json = jsonContent.toString();
                if (!json.contains("use_previous_position")) {
                    loadedConfig.use_previous_position = defaultConfig.use_previous_position;
                }
                if (!json.contains("return_to_last_location")) {
                    loadedConfig.return_to_last_location = defaultConfig.return_to_last_location;
                }
                if (loadedConfig.discord_webhook_url == null) {
                    loadedConfig.discord_webhook_url = defaultConfig.discord_webhook_url;
                }
                if (json.contains("use_banhammer_webhook")) {
                    loadedConfig.useBanhammerWebhook = extractBooleanFromJson(json,
                            "use_banhammer_webhook", defaultConfig.useBanhammerWebhook);
                } else if (json.contains("sendJailMessage")) {
                    loadedConfig.useBanhammerWebhook = extractBooleanFromJson(json,
                            "sendJailMessage", defaultConfig.useBanhammerWebhook);
                } else {
                    loadedConfig.useBanhammerWebhook = defaultConfig.useBanhammerWebhook;
                }
            }

            config = loadedConfig;
            saveConfig();
            System.out.println("Configuration loaded and patched: " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            config = defaultConfig;
        }
    }

    private static boolean isClientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    private static Config copyConfig(Config source) {
        Config copy = new Config();
        if (source == null) {
            return copy;
        }
        copy._config_guide = source._config_guide;
        copy.admin_roles = source.admin_roles;
        copy.use_previous_position = source.use_previous_position;
        copy.return_to_last_location = source.return_to_last_location;
        copy.jail_position = copyPosition(source.jail_position);
        copy.release_position = copyPosition(source.release_position);
        copy.discord_webhook_url = source.discord_webhook_url;
        copy.useBanhammerWebhook = source.useBanhammerWebhook;
        return copy;
    }

    private static Config.Position copyPosition(Config.Position position) {
        return position == null ? null : new Config.Position(position.x, position.y, position.z);
    }

    private void loadLanguage() {
        // Create the config directory if it doesn't exist
        if (!LANGUAGE_FILE.getParentFile().exists()) {
            LANGUAGE_FILE.getParentFile().mkdirs();
        }

        // If the language file exists, load it
        if (LANGUAGE_FILE.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(LANGUAGE_FILE))) {
                languageStrings.clear();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        languageStrings.put(parts[0].trim(), parts[1].trim());
                    }
                }
                if (ensureLanguageDefaults()) {
                    saveLanguage();
                }
                System.out.println("Language file loaded: " + LANGUAGE_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // If it doesn't exist, create the file with default values
            createDefaultLanguageFile();
            System.out.println("Default language file created: " + LANGUAGE_FILE.getAbsolutePath());
        }
    }

    private void createDefaultLanguageFile() {
        languageStrings.clear();
        ensureLanguageDefaults();
        saveLanguage();
    }

    private boolean ensureLanguageDefaults() {
        boolean updated = migrateDurationLanguageTemplates();
        updated |= ensureLanguageKey("jail_player",
                "You have been jailed by {actor} for {time}! Reason: {reason}");
        updated |= ensureLanguageKey("jail_broadcast",
                "Player: {player} has been jailed by {actor}! Reason: {reason}. Expires in {time}");
        updated |= ensureLanguageKey("unjail_player_manual", "You have been manually released from jail!");
        updated |= ensureLanguageKey("unjail_broadcast_manual", "{player} has been manually released from jail!");
        updated |= ensureLanguageKey("unjail_player_auto", "You have been released after serving your sentence.");
        updated |= ensureLanguageKey("unjail_broadcast_auto", "{player} has been released after serving their sentence.");
        updated |= ensureLanguageKey("block_interaction_denied", "You cannot interact with blocks while in jail!");
        updated |= ensureLanguageKey("entity_interaction_denied", "You cannot interact with entities while in jail!");
        updated |= ensureLanguageKey("bucket_use_denied", "You cannot use lava or water buckets while in jail!");
        updated |= ensureLanguageKey("item_use_denied", "You cannot use items while in jail!");
        updated |= ensureLanguageKey("block_break_denied", "You cannot break blocks while in jail!");
        String jailInfoDefault = "You are jailed for {time} by {actor}. Reason: {reason}";
        updated |= ensureLanguageKey("jail_info_message", jailInfoDefault);
        updated |= ensureLanguageKey("not_in_jail_message", "You are not in jail!");
        updated |= ensureLanguageKey("jail_time_added_player",
                "Your jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");
        updated |= ensureLanguageKey("jail_time_added_broadcast",
                "{player}'s jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");
        return updated;
    }

    private boolean migrateDurationLanguageTemplates() {
        boolean updated = false;
        updated |= migrateLanguageDefault("jail_player",
                "You have been jailed for {time} seconds! Reason: {reason}",
                "You have been jailed by {actor} for {time}! Reason: {reason}");
        updated |= migrateLanguageDefault("jail_player",
                "You have been jailed for {time}! Reason: {reason}",
                "You have been jailed by {actor} for {time}! Reason: {reason}");
        updated |= migrateLanguageDefault("jail_broadcast",
                "{player} has been jailed for {time} seconds. Reason: {reason}",
                "Player: {player} has been jailed by {actor}! Reason: {reason}. Expires in {time}");
        updated |= migrateLanguageDefault("jail_broadcast",
                "{player} has been jailed for {time}. Reason: {reason}",
                "Player: {player} has been jailed by {actor}! Reason: {reason}. Expires in {time}");
        updated |= migrateLanguageDefault("jail_info_message",
                "You are in jail for another {time} seconds. Reason: {reason}.",
                "You are jailed for {time} by {actor}. Reason: {reason}");
        updated |= migrateLanguageDefault("jail_time_added_player",
                "Your jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}",
                "Your jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");
        updated |= migrateLanguageDefault("jail_time_added_player",
                "Your jail time has been extended by {added}. Remaining: {time}. Reason: {reason}",
                "Your jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");
        updated |= migrateLanguageDefault("jail_time_added_broadcast",
                "{player}'s jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}",
                "{player}'s jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");
        updated |= migrateLanguageDefault("jail_time_added_broadcast",
                "{player}'s jail time has been extended by {added}. Remaining: {time}. Reason: {reason}",
                "{player}'s jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}");

        for (Map.Entry<String, String> entry : languageStrings.entrySet()) {
            String value = entry.getValue();
            String migratedValue = value.replaceAll(
                    "(?i)(\\{(?:time|added)\\})\\s+(?:seconds?|second\\(s\\)|secs?)", "$1");
            if (!migratedValue.equals(value)) {
                entry.setValue(migratedValue);
                updated = true;
            }
        }
        return updated;
    }

    private boolean migrateLanguageDefault(String key, String previousDefault, String newDefault) {
        if (previousDefault.equals(languageStrings.get(key))) {
            languageStrings.put(key, newDefault);
            return true;
        }
        return false;
    }

    private boolean ensureLanguageKey(String key, String value) {
        if (!languageStrings.containsKey(key)) {
            languageStrings.put(key, value);
            return true;
        }
        return false;
    }

    private void saveLanguage() {
        try (FileWriter writer = new FileWriter(LANGUAGE_FILE)) {
            for (Map.Entry<String, String> entry : languageStrings.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            System.out.println("Language file saved: " + LANGUAGE_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Config loadTomlConfig(File tomlFile, Config defaults) {
        Config loadedConfig = copyConfig(defaults);
        try (BufferedReader reader = new BufferedReader(new FileReader(tomlFile))) {
            String line;
            String currentSection = "";
            boolean inMultiline = false;
            StringBuilder multilineValue = new StringBuilder();
            String multilineKey = null;
            String multilineSection = "";

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inMultiline) {
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                        continue;
                    }

                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                        continue;
                    }

                    int equalsIndex = trimmed.indexOf('=');
                    if (equalsIndex < 0) {
                        continue;
                    }

                    String key = trimmed.substring(0, equalsIndex).trim();
                    String rawValue = trimmed.substring(equalsIndex + 1).trim();

                    if (rawValue.startsWith("\"\"\"")) {
                        String remainder = rawValue.substring(3);
                        int endIndex = remainder.indexOf("\"\"\"");
                        if (endIndex >= 0) {
                            String value = remainder.substring(0, endIndex);
                            applyTomlValue(loadedConfig, currentSection, key, value, true);
                        } else {
                            inMultiline = true;
                            multilineKey = key;
                            multilineSection = currentSection;
                            multilineValue.setLength(0);
                            multilineValue.append(remainder);
                        }
                        continue;
                    }

                    rawValue = stripTomlComment(rawValue);
                    applyTomlValue(loadedConfig, currentSection, key, rawValue, false);
                } else {
                    int endIndex = trimmed.indexOf("\"\"\"");
                    if (endIndex >= 0) {
                        multilineValue.append("\n").append(trimmed, 0, endIndex);
                        applyTomlValue(loadedConfig, multilineSection, multilineKey,
                                multilineValue.toString(), true);
                        inMultiline = false;
                        multilineKey = null;
                        multilineSection = "";
                        multilineValue.setLength(0);
                    } else {
                        multilineValue.append("\n").append(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return loadedConfig;
    }

    private void applyTomlValue(Config loadedConfig, String section, String key, String rawValue,
            boolean isMultiline) {
        if (loadedConfig == null || key == null) {
            return;
        }

        String effectiveSection = section == null ? "" : section.trim();
        String effectiveKey = key.trim();
        if (effectiveSection.isEmpty() && effectiveKey.contains(".")) {
            int dotIndex = effectiveKey.indexOf('.');
            effectiveSection = effectiveKey.substring(0, dotIndex).trim();
            effectiveKey = effectiveKey.substring(dotIndex + 1).trim();
        }

        if (effectiveSection.isEmpty()) {
            switch (effectiveKey) {
                case "_config_guide" -> loadedConfig._config_guide = isMultiline
                        ? rawValue
                        : parseTomlString(rawValue);
                case "admin_roles" -> loadedConfig.admin_roles = parseTomlString(rawValue);
                case "use_previous_position" -> loadedConfig.use_previous_position = parseTomlBoolean(rawValue,
                        loadedConfig.use_previous_position);
                case "return_to_last_location" -> loadedConfig.return_to_last_location = parseTomlBoolean(rawValue,
                        loadedConfig.return_to_last_location);
                case "discord_webhook_url" -> loadedConfig.discord_webhook_url = parseTomlString(rawValue);
                case "sendJailMessage" -> loadedConfig.useBanhammerWebhook = parseTomlBoolean(rawValue,
                        loadedConfig.useBanhammerWebhook);
                case "use_banhammer_webhook" -> loadedConfig.useBanhammerWebhook = parseTomlBoolean(rawValue,
                        loadedConfig.useBanhammerWebhook);
                default -> {
                }
            }
            return;
        }

        switch (effectiveSection) {
            case "release_position" -> applyTomlPosition(loadedConfig.release_position, effectiveKey, rawValue);
            case "jail_position" -> applyTomlPosition(loadedConfig.jail_position, effectiveKey, rawValue);
            default -> {
            }
        }
    }

    private void applyTomlPosition(Config.Position position, String key, String rawValue) {
        if (position == null || key == null) {
            return;
        }
        int value = parseTomlInt(rawValue, 0);
        switch (key) {
            case "x" -> position.x = value;
            case "y" -> position.y = value;
            case "z" -> position.z = value;
            default -> {
            }
        }
    }

    private String stripTomlComment(String rawValue) {
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < rawValue.length(); i++) {
            char c = rawValue.charAt(i);
            if (inQuotes) {
                if (c == '\\') {
                    i++;
                } else if (c == quoteChar) {
                    inQuotes = false;
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (c == '#') {
                    return rawValue.substring(0, i).trim();
                }
            }
        }
        return rawValue.trim();
    }

    private String parseTomlString(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        trimmed = trimmed.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        return trimmed;
    }

    private boolean parseTomlBoolean(String rawValue, boolean fallback) {
        if (rawValue == null) {
            return fallback;
        }
        String trimmed = rawValue.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return fallback;
    }

    private int parseTomlInt(String rawValue, int fallback) {
        if (rawValue == null) {
            return fallback;
        }
        String cleaned = rawValue.trim().replace("_", "");
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String escapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void saveTomlConfig(Config configToSave, File tomlFile) {
        Config defaultConfig = new Config();
        if (configToSave.release_position == null) {
            configToSave.release_position = defaultConfig.release_position;
        }
        if (configToSave.jail_position == null) {
            configToSave.jail_position = defaultConfig.jail_position;
        }

        File parent = tomlFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(tomlFile)) {
            writer.write("_config_guide = \"" + escapeTomlString(configToSave._config_guide) + "\"\n");
            writer.write("admin_roles = \"" + escapeTomlString(configToSave.admin_roles) + "\"\n");
            writer.write("use_previous_position = " + configToSave.use_previous_position + "\n");
            writer.write("return_to_last_location = " + configToSave.return_to_last_location + "\n\n");
            writer.write("discord_webhook_url = \"" + escapeTomlString(configToSave.discord_webhook_url) + "\"\n\n");
            writer.write("use_banhammer_webhook = " + configToSave.useBanhammerWebhook + "\n\n");

            writer.write("[release_position]\n");
            writer.write("x = " + configToSave.release_position.x + "\n");
            writer.write("y = " + configToSave.release_position.y + "\n");
            writer.write("z = " + configToSave.release_position.z + "\n\n");

            writer.write("[jail_position]\n");
            writer.write("x = " + configToSave.jail_position.x + "\n");
            writer.write("y = " + configToSave.jail_position.y + "\n");
            writer.write("z = " + configToSave.jail_position.z + "\n");

            System.out.println("Configuration saved: " + tomlFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        saveConfigToFiles(config, activeConfigFile, activeTomlConfigFile);
    }

    private static void saveConfigToFiles(Config configToSave, File jsonFile, File tomlFile) {
        ConfigFormat format = tomlFile.exists() ? ConfigFormat.TOML : ConfigFormat.JSON;
        File targetFile = format == ConfigFormat.TOML ? tomlFile : jsonFile;
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (format == ConfigFormat.TOML) {
            saveTomlConfig(configToSave, tomlFile);
            return;
        }

        try (FileWriter writer = new FileWriter(jsonFile)) {
            GSON.toJson(configToSave, writer);
            System.out.println("Configuration saved: " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean extractBooleanFromJson(String json, String key, boolean fallback) {
        try {
            Map<?, ?> root = GSON.fromJson(json, Map.class);
            if (root == null) {
                return fallback;
            }
            Object value = root.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

}


