package com.example.jailmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
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
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.mojang.brigadier.suggestion.SuggestionProvider;

public class JailMod implements ModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/jailmod/config.json");
    private static final File TOML_CONFIG_FILE = new File("config/jailmod/config.toml");
    private static final File LANGUAGE_FILE = new File("config/jailmod/language.txt");
    private static final File JAIL_DATA_FILE = new File("config/jailmod/jail_data.json");
    private static Config config;
    private static Map<String, String> languageStrings = new HashMap<>();
    private static Map<UUID, JailData> jailedPlayers = new HashMap<>();

    private static MinecraftServer serverInstance;
    private static final SuggestionProvider<ServerCommandSource> JAILED_PLAYERS_SUGGESTIONS = (context, builder) -> {
        MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }

        for (UUID uuid : jailedPlayers.keySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
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
                "- discord_webhook_url: Optional Discord webhook. If empty and BanHammer is installed, JailMod will reuse BanHammer's webhook.";
        public String admin_roles = "op"; // Comma-separated list of roles/tags that grant admin access. "op" refers to
                                          // operator status.
        public boolean use_previous_position = true; // Use spawn point as fallback
        public boolean return_to_last_location = true; // Return to the exact spot where jailed
        public Position release_position = new Position(100, 65, 100);
        public Position jail_position = new Position(0, 60, 0);
        public String discord_webhook_url = "";

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
        public RegistryKey<World> originalSpawnDimension;
        public boolean hadSpawnPoint;
        public String reason;
        public int remainingTicks; // Remaining time in ticks (1 second = 20 ticks)

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

        public List<StatusEffectSnapshot> savedEffects;
    }

    private static class StatusEffectSnapshot {
        public String effectId;
        public int duration;
        public int amplifier;
        public boolean ambient;
        public boolean showParticles;
        public boolean showIcon;
    }

    private static class JailUpdateResult {
        public final boolean wasAlreadyJailed;
        public final int addedSeconds;
        public final int totalSeconds;

        public JailUpdateResult(boolean wasAlreadyJailed, int addedSeconds, int totalSeconds) {
            this.wasAlreadyJailed = wasAlreadyJailed;
            this.addedSeconds = addedSeconds;
            this.totalSeconds = totalSeconds;
        }
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            Text message = Text.literal("[Jail-Mod] Loaded")
                    .styled(style -> style.withColor(0x00FF00).withBold(true));
            server.sendMessage(message);
        });

        loadConfig();
        loadLanguage();
        loadJailData();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<UUID> playersToRelease = new ArrayList<>();
            for (Map.Entry<UUID, JailData> entry : jailedPlayers.entrySet()) {
                UUID playerUUID = entry.getKey();
                JailData jailData = entry.getValue();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
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
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUUID = player.getUuid();
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
            dispatcher.register(CommandManager.literal("jail")
                    .then(CommandManager.literal("imprison")
                            .requires(source -> hasAdminPermission(source))
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .then(CommandManager.argument("time", IntegerArgumentType.integer(1))
                                            .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                                    .executes(context -> {
                                                        ServerPlayerEntity player = EntityArgumentType
                                                                .getPlayer(context, "player");
                                                        int timeInSeconds = IntegerArgumentType.getInteger(context,
                                                                "time");
                                                        String reason = StringArgumentType.getString(context, "reason");

                                                        if (player != null) {
                                                            JailUpdateResult result = jailPlayer(player, timeInSeconds,
                                                                    reason, context.getSource().getName(), true);
                                                            if (result.wasAlreadyJailed) {
                                                                context.getSource().sendFeedback(
                                                                        () -> Text.of("Added " + result.addedSeconds
                                                                                + " seconds to " + player.getName()
                                                                                        .getString()
                                                                                + ". Remaining: "
                                                                                + result.totalSeconds + " seconds."),
                                                                        true);
                                                            } else {
                                                                context.getSource().sendFeedback(
                                                                        () -> Text.of("Player "
                                                                                + player.getName().getString()
                                                                                + " jailed for " + timeInSeconds
                                                                                + " seconds."),
                                                                        true);
                                                            }
                                                        } else {
                                                            context.getSource().sendError(Text.of("Player not found!"));
                                                        }
                                                        return 1;
                                                    })))))
                    .then(CommandManager.literal("reload")
                            .requires(source -> hasAdminPermission(source))
                            .executes(context -> {
                                loadConfig();
                                loadLanguage();
                                context.getSource().sendFeedback(
                                        () -> Text.of("Configuration and language strings successfully reloaded!"),
                                        true);
                                return 1;
                            }))
                    .then(CommandManager.literal("set")
                            .requires(source -> hasAdminPermission(source))
                            .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");

                                                        config.jail_position = new Config.Position(x, y, z);
                                                        saveConfig();

                                                        context.getSource()
                                                                .sendFeedback(() -> Text.of("Jail position set to (" + x
                                                                        + ", " + y + ", " + z + ")"), true);
                                                        return 1;
                                                    })))))
                    .then(CommandManager.literal("info")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                if (player != null && isPlayerInJail(player)) {
                                    JailData jailData = jailedPlayers.get(player.getUuid());
                                    int remainingSeconds = jailData.remainingTicks / 20;
                                    String reason = jailData.reason;
                                    String message = languageStrings.get("jail_info_message")
                                            .replace("{time}", String.valueOf(remainingSeconds))
                                            .replace("{reason}", reason);
                                    player.sendMessage(Text.of(message), false);
                                    return 1;
                                } else {
                                    String notInJailMessage = languageStrings.get("not_in_jail_message");
                                    context.getSource().sendFeedback(() -> Text.of(notInJailMessage), false);
                                    return 0;
                                }
                            })));

            dispatcher.register(CommandManager.literal("unjail")
                    .requires(source -> hasAdminPermission(source))
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                            .suggests(JAILED_PLAYERS_SUGGESTIONS)
                            .executes(context -> {
                                ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                if (player != null) {
                                    if (!isPlayerInJail(player)) {
                                        context.getSource().sendError(Text.literal("Player "
                                                + player.getName().getString() + " is not jailed.")
                                                .formatted(Formatting.RED));
                                        return 0;
                                    }
                                    unjailPlayer(player, true, context.getSource().getName());
                                    context.getSource().sendFeedback(
                                            () -> Text.of("Player " + player.getName().getString()
                                                    + " has been released from jail."),
                                            true);
                                } else {
                                    context.getSource().sendError(Text.of("Player not found!"));
                                }
                                return 1;
                            })));
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> saveJailData());
    }

    private static boolean hasAdminPermission(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            // Very stable OP check: compare player name against the list of OP names
            // This bypasses mapping issues with hasPermissionLevel or isOperator
            String playerName = player.getName().getString();
            for (String opName : source.getServer().getPlayerManager().getOpList().getNames()) {
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
                    if (!trimmedRole.equalsIgnoreCase("op") && player.getCommandTags().contains(trimmedRole)) {
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
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendMessage(Text.of(languageStrings.get("block_interaction_denied")), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendMessage(Text.of(languageStrings.get("entity_interaction_denied")), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendMessage(Text.of(languageStrings.get("block_interaction_denied")), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendMessage(Text.of(languageStrings.get("entity_interaction_denied")), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                ItemStack itemStack = player.getStackInHand(hand);

                if (itemStack.isOf(Items.LAVA_BUCKET) || itemStack.isOf(Items.WATER_BUCKET)) {
                    serverPlayer.sendMessage(Text.of(languageStrings.get("bucket_use_denied")), true);
                    return ActionResult.FAIL;
                }

                serverPlayer.sendMessage(Text.of(languageStrings.get("item_use_denied")), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendMessage(Text.of(languageStrings.get("block_break_denied")), true);
                return false;
            }
            return true;
        });
    }

    public static boolean isPlayerInJail(ServerPlayerEntity player) {
        return player != null && jailedPlayers.containsKey(player.getUuid());
    }

    private JailUpdateResult jailPlayer(ServerPlayerEntity player, int timeInSeconds, String reason, String actorName,
            boolean notifyWebhook) {
        if (actorName == null || actorName.isEmpty()) {
            actorName = "system";
        }
        int addedTicks = timeInSeconds * 20; // Convert seconds to ticks
        JailData existingData = jailedPlayers.get(player.getUuid());
        if (existingData != null) {
            existingData.remainingTicks += addedTicks;
            existingData.reason = reason;

            applyFrozenStats(player, existingData);
            sendTimeAddedMessages(player, existingData, timeInSeconds);
            saveJailData();

            if (notifyWebhook) {
                sendDiscordJailNotification("Jail Extended", player.getName().getString(), reason,
                        Math.max(0, existingData.remainingTicks / 20), actorName, 0xFF0000);
            }

            return new JailUpdateResult(true, timeInSeconds, Math.max(0, existingData.remainingTicks / 20));
        }

        // Save the player's original spawn position
        BlockPos originalSpawnPos = null;
        RegistryKey<World> originalSpawnDimension = null;
        var respawn = player.getRespawn();
        if (respawn != null && respawn.respawnData() != null) {
            originalSpawnPos = respawn.respawnData().getPos();
            originalSpawnDimension = respawn.respawnData().getDimension();
        }
        boolean hadSpawnPoint = originalSpawnPos != null;

        // Capture current position before teleporting
        double lastX = player.getX();
        double lastY = player.getY();
        double lastZ = player.getZ();
        float lastYaw = player.getYaw();
        float lastPitch = player.getPitch();
        String lastDimension = player.getEntityWorld().getRegistryKey().getValue().toString();

        // Save player data
        JailData jailData = new JailData();
        jailData.playerUUID = player.getUuid();
        jailData.originalSpawnPos = originalSpawnPos;
        jailData.originalSpawnDimension = originalSpawnDimension;
        jailData.hadSpawnPoint = hadSpawnPoint;
        jailData.reason = reason;
        jailData.remainingTicks = addedTicks;
        jailData.lastX = lastX;
        jailData.lastY = lastY;
        jailData.lastZ = lastZ;
        jailData.lastYaw = lastYaw;
        jailData.lastPitch = lastPitch;
        jailData.lastDimension = lastDimension;
        captureStatSnapshot(player, jailData);

        jailedPlayers.put(player.getUuid(), jailData);

        jailPlayer(player, jailData);

        saveJailData();

        if (notifyWebhook) {
            sendDiscordJailNotification("Player Jailed", player.getName().getString(), reason,
                    Math.max(0, jailData.remainingTicks / 20), actorName, 0xFF0000);
        }

        return new JailUpdateResult(false, 0, Math.max(0, jailData.remainingTicks / 20));
    }

    private void jailPlayer(ServerPlayerEntity player, JailData jailData) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        BlockPos jailPos = new BlockPos(config.jail_position.x, config.jail_position.y, config.jail_position.z);
        player.teleport(world, jailPos.getX() + 0.5, jailPos.getY(), jailPos.getZ() + 0.5,
                EnumSet.noneOf(PositionFlag.class), player.getYaw(), player.getPitch(), false);

        applyFrozenStats(player, jailData);

        // TODO: Fix setSpawnPoint
        // player.setSpawnPoint(new ServerPlayerEntity.Respawn(new
        // SpawnPoint(world.getRegistryKey(), jailPos, 0.0f, true), true), true);

        String messageToPlayer = languageStrings.get("jail_player")
                .replace("{time}", String.valueOf(jailData.remainingTicks / 20))
                .replace("{reason}", jailData.reason);
        player.sendMessage(Text.of(messageToPlayer), false);

        String jailMessage = languageStrings.get("jail_broadcast")
                .replace("{player}", player.getName().getString())
                .replace("{time}", String.valueOf(jailData.remainingTicks / 20))
                .replace("{reason}", jailData.reason);
        serverInstance.getPlayerManager().broadcast(Text.of(jailMessage), false);
    }

    private void unjailPlayer(ServerPlayerEntity player, boolean isManual, String actorName) {
        JailData jailData = jailedPlayers.remove(player.getUuid());

        if (jailData != null) {
            // TODO: Restore spawn point logic
            /*
             * if (jailData.hadSpawnPoint && jailData.originalSpawnPos != null) {
             * player.setSpawnPoint(new ServerPlayerEntity.Respawn(new
             * SpawnPoint(jailData.originalSpawnDimension, jailData.originalSpawnPos, 0.0f,
             * true), true), false);
             * } else {
             * player.setSpawnPoint(null, false);
             * }
             */

            ServerWorld world = (ServerWorld) player.getEntityWorld();

            // Teleport the player out of jail (release position)
            if (config.return_to_last_location && jailData.lastDimension != null) {
                ServerWorld targetWorld = serverInstance.getWorld(
                        RegistryKey.of(RegistryKeys.WORLD, net.minecraft.util.Identifier.of(jailData.lastDimension)));
                if (targetWorld == null)
                    targetWorld = world;
                player.teleport(targetWorld, jailData.lastX, jailData.lastY, jailData.lastZ,
                        EnumSet.noneOf(PositionFlag.class), jailData.lastYaw, jailData.lastPitch, false);
            } else if (config.use_previous_position && jailData.hadSpawnPoint) {
                player.teleport(world, jailData.originalSpawnPos.getX(), jailData.originalSpawnPos.getY(),
                        jailData.originalSpawnPos.getZ(), EnumSet.noneOf(PositionFlag.class), player.getYaw(),
                        player.getPitch(), false);
            } else {
                BlockPos releasePos = new BlockPos(config.release_position.x, config.release_position.y,
                        config.release_position.z);
                player.teleport(world, releasePos.getX() + 0.5, releasePos.getY(), releasePos.getZ() + 0.5,
                        EnumSet.noneOf(PositionFlag.class), player.getYaw(), player.getPitch(), false);
            }

            restoreStatsAfterJail(player, jailData);

            if (isManual) {
                String messageToPlayer = languageStrings.get("unjail_player_manual");
                player.sendMessage(Text.of(messageToPlayer), false);

                String broadcastMessage = languageStrings.get("unjail_broadcast_manual")
                        .replace("{player}", player.getName().getString());
                serverInstance.getPlayerManager().broadcast(Text.of(broadcastMessage), false);
                sendDiscordJailNotification("Player Unjailed", player.getName().getString(), jailData.reason,
                        0, actorName, 0x00FF00);
            } else {
                String messageToPlayer = languageStrings.get("unjail_player_auto");
                player.sendMessage(Text.of(messageToPlayer), false);

                String broadcastMessage = languageStrings.get("unjail_broadcast_auto")
                        .replace("{player}", player.getName().getString());
                serverInstance.getPlayerManager().broadcast(Text.of(broadcastMessage), false);
                sendDiscordJailNotification("Player Unjailed (Auto)", player.getName().getString(), jailData.reason,
                        0, actorName, 0x00FF00);
            }

            saveJailData();
        }
    }

    private void captureStatSnapshot(ServerPlayerEntity player, JailData jailData) {
        jailData.savedHealth = player.getHealth();
        var hunger = player.getHungerManager();
        jailData.savedFoodLevel = hunger.getFoodLevel();
        jailData.savedSaturationLevel = hunger.getSaturationLevel();
        jailData.savedAir = player.getAir();
        jailData.savedAbsorption = player.getAbsorptionAmount();
        jailData.savedXpLevel = player.experienceLevel;
        jailData.savedXpProgress = player.experienceProgress;
        jailData.savedTotalXp = player.totalExperience;
        jailData.savedInvulnerable = player.getAbilities().invulnerable;
        jailData.savedFireTicks = player.getFireTicks();
        jailData.hasStatSnapshot = true;
        captureEffectSnapshot(player, jailData);
    }

    private void applyFrozenStats(ServerPlayerEntity player, JailData jailData) {
        if (!jailData.hasStatSnapshot) {
            captureStatSnapshot(player, jailData);
            saveJailData();
        }

        if (!player.getAbilities().invulnerable) {
            player.getAbilities().invulnerable = true;
            player.sendAbilitiesUpdate();
        }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) {
            targetHealth = player.getMaxHealth();
        }
        player.setHealth(targetHealth);

        var hunger = player.getHungerManager();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturationLevel(jailData.savedSaturationLevel);

        player.setAir(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);

        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        if (player.getFireTicks() != 0) {
            player.setFireTicks(0);
        }

        applyFrozenEffects(player, jailData);
    }

    private void restoreStatsAfterJail(ServerPlayerEntity player, JailData jailData) {
        if (!jailData.hasStatSnapshot) {
            return;
        }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) {
            targetHealth = player.getMaxHealth();
        }
        player.setHealth(targetHealth);

        var hunger = player.getHungerManager();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturationLevel(jailData.savedSaturationLevel);

        player.setAir(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);

        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        player.getAbilities().invulnerable = jailData.savedInvulnerable;
        player.sendAbilitiesUpdate();

        if (jailData.savedFireTicks > 0) {
            player.setFireTicks(jailData.savedFireTicks);
        } else if (player.getFireTicks() != 0) {
            player.setFireTicks(0);
        }

        restoreEffectsAfterJail(player, jailData);
    }

    private void captureEffectSnapshot(ServerPlayerEntity player, JailData jailData) {
        jailData.savedEffects = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            RegistryEntry<StatusEffect> effectType = effect.getEffectType();
            var effectKey = effectType.getKey().orElse(null);
            if (effectKey == null) {
                continue;
            }
            StatusEffectSnapshot snapshot = new StatusEffectSnapshot();
            snapshot.effectId = effectKey.getValue().toString();
            snapshot.duration = effect.getDuration();
            snapshot.amplifier = effect.getAmplifier();
            snapshot.ambient = effect.isAmbient();
            snapshot.showParticles = effect.shouldShowParticles();
            snapshot.showIcon = effect.shouldShowIcon();
            jailData.savedEffects.add(snapshot);
        }
    }

    private void applyFrozenEffects(ServerPlayerEntity player, JailData jailData) {
        if (jailData.savedEffects == null) {
            captureEffectSnapshot(player, jailData);
            saveJailData();
        }

        if (jailData.savedEffects == null) {
            return;
        }

        Set<Identifier> snapshotIds = new HashSet<>();
        for (StatusEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId != null) {
                snapshotIds.add(Identifier.of(snapshot.effectId));
            }
        }

        if (!player.getStatusEffects().isEmpty()) {
            List<StatusEffectInstance> currentEffects = new ArrayList<>(player.getStatusEffects());
            for (StatusEffectInstance current : currentEffects) {
                RegistryEntry<StatusEffect> currentType = current.getEffectType();
                var currentKey = currentType.getKey().orElse(null);
                Identifier currentId = currentKey != null ? currentKey.getValue() : null;
                if (currentId == null || !snapshotIds.contains(currentId)) {
                    player.removeStatusEffect(currentType);
                }
            }
        }

        for (StatusEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) {
                continue;
            }
            RegistryEntry<StatusEffect> statusEffect = Registries.STATUS_EFFECT
                    .getEntry(Identifier.of(snapshot.effectId))
                    .orElse(null);
            if (statusEffect == null) {
                continue;
            }
            StatusEffectInstance instance = new StatusEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addStatusEffect(instance);
        }
    }

    private void restoreEffectsAfterJail(ServerPlayerEntity player, JailData jailData) {
        if (jailData.savedEffects == null) {
            return;
        }

        player.clearStatusEffects();
        for (StatusEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) {
                continue;
            }
            RegistryEntry<StatusEffect> statusEffect = Registries.STATUS_EFFECT
                    .getEntry(Identifier.of(snapshot.effectId))
                    .orElse(null);
            if (statusEffect == null) {
                continue;
            }
            StatusEffectInstance instance = new StatusEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addStatusEffect(instance);
        }
    }

    private void sendTimeAddedMessages(ServerPlayerEntity player, JailData jailData, int addedSeconds) {
        int remainingSeconds = Math.max(0, jailData.remainingTicks / 20);
        String messageToPlayer = languageStrings.get("jail_time_added_player")
                .replace("{added}", String.valueOf(addedSeconds))
                .replace("{time}", String.valueOf(remainingSeconds))
                .replace("{reason}", jailData.reason);
        player.sendMessage(Text.of(messageToPlayer), false);

        String broadcastMessage = languageStrings.get("jail_time_added_broadcast")
                .replace("{player}", player.getName().getString())
                .replace("{added}", String.valueOf(addedSeconds))
                .replace("{time}", String.valueOf(remainingSeconds))
                .replace("{reason}", jailData.reason);
        serverInstance.getPlayerManager().broadcast(Text.of(broadcastMessage), false);
    }

    // [Rest of file is identical]
    private void releasePlayer(UUID playerUUID) {
        ServerPlayerEntity player = serverInstance.getPlayerManager().getPlayer(playerUUID);
        if (player != null) {
            unjailPlayer(player, false, "system");
        }
    }

    // Load jail status from file
    private void loadJailData() {
        if (JAIL_DATA_FILE.exists()) {
            try (FileReader reader = new FileReader(JAIL_DATA_FILE)) {
                JailData[] loadedData = GSON.fromJson(reader, JailData[].class);
                if (loadedData != null) {
                    for (JailData data : loadedData) {
                        jailedPlayers.put(data.playerUUID, data);
                    }
                }
                System.out.println("Jail status loaded.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Save jail status to file
    private void saveJailData() {
        if (!JAIL_DATA_FILE.getParentFile().exists()) {
            JAIL_DATA_FILE.getParentFile().mkdirs();
        }
        try (FileWriter writer = new FileWriter(JAIL_DATA_FILE)) {
            GSON.toJson(jailedPlayers.values().toArray(new JailData[0]), writer);
            System.out.println("Jail status saved.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        // Create the config directory if it doesn't exist
        if (!CONFIG_FILE.getParentFile().exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
        }

        if (TOML_CONFIG_FILE.exists()) {
            configFormat = ConfigFormat.TOML;
            config = loadTomlConfig(TOML_CONFIG_FILE);
            if (config == null) {
                config = new Config();
            }
            saveConfig(); // Save back to include new fields
            System.out.println("Configuration loaded and patched: " + TOML_CONFIG_FILE.getAbsolutePath());
            return;
        }

        configFormat = ConfigFormat.JSON;

        // If the config file exists, load it
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                StringBuilder jsonContent = new StringBuilder();
                int i;
                while ((i = reader.read()) != -1) {
                    jsonContent.append((char) i);
                }

                // Load existing config
                Config loadedConfig = GSON.fromJson(jsonContent.toString(), Config.class);
                Config defaultConfig = new Config();

                // Patch missing fields (simple manual merge for now to ensure reliability)
                if (loadedConfig != null) {
                    if (loadedConfig._config_guide == null)
                        loadedConfig._config_guide = defaultConfig._config_guide;
                    if (loadedConfig.release_position == null)
                        loadedConfig.release_position = defaultConfig.release_position;
                    if (loadedConfig.jail_position == null)
                        loadedConfig.jail_position = defaultConfig.jail_position;
                    // Note: primitives like booleans default to false if missing, which is tricky.
                    // To handle primitives correctly without complex reflection, we check if the
                    // key exists in raw JSON.
                    if (!jsonContent.toString().contains("return_to_last_location")) {
                        loadedConfig.return_to_last_location = defaultConfig.return_to_last_location;
                    }
                    if (!jsonContent.toString().contains("discord_webhook_url")) {
                        loadedConfig.discord_webhook_url = defaultConfig.discord_webhook_url;
                    }
                } else {
                    loadedConfig = defaultConfig;
                }

                config = loadedConfig;
                saveConfig(); // Save back to include new fields
                System.out.println("Configuration loaded and patched: " + CONFIG_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // If it doesn't exist, create the file with default values
            config = new Config();
            saveConfig();
            System.out.println("Default config file created: " + CONFIG_FILE.getAbsolutePath());
        }
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
        boolean updated = false;
        updated |= ensureLanguageKey("jail_player", "You have been jailed for {time} seconds! Reason: {reason}");
        updated |= ensureLanguageKey("jail_broadcast", "{player} has been jailed for {time} seconds. Reason: {reason}");
        updated |= ensureLanguageKey("unjail_player_manual", "You have been manually released from jail!");
        updated |= ensureLanguageKey("unjail_broadcast_manual", "{player} has been manually released from jail!");
        updated |= ensureLanguageKey("unjail_player_auto", "You have been released after serving your sentence.");
        updated |= ensureLanguageKey("unjail_broadcast_auto", "{player} has been released after serving their sentence.");
        updated |= ensureLanguageKey("block_interaction_denied", "You cannot interact with blocks while in jail!");
        updated |= ensureLanguageKey("entity_interaction_denied", "You cannot interact with entities while in jail!");
        updated |= ensureLanguageKey("bucket_use_denied", "You cannot use lava or water buckets while in jail!");
        updated |= ensureLanguageKey("item_use_denied", "You cannot use items while in jail!");
        updated |= ensureLanguageKey("block_break_denied", "You cannot break blocks while in jail!");
        updated |= ensureLanguageKey("jail_info_message",
                "You are in jail for another {time} seconds. Reason: {reason}.");
        updated |= ensureLanguageKey("not_in_jail_message", "You are not in jail!");
        updated |= ensureLanguageKey("jail_time_added_player",
                "Your jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}");
        updated |= ensureLanguageKey("jail_time_added_broadcast",
                "{player}'s jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}");
        return updated;
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

    private Config loadTomlConfig(File tomlFile) {
        Config loadedConfig = new Config();
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

    private String escapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private void saveTomlConfig() {
        Config defaultConfig = new Config();
        if (config.release_position == null) {
            config.release_position = defaultConfig.release_position;
        }
        if (config.jail_position == null) {
            config.jail_position = defaultConfig.jail_position;
        }

        try (FileWriter writer = new FileWriter(TOML_CONFIG_FILE)) {
            writer.write("_config_guide = \"" + escapeTomlString(config._config_guide) + "\"\n");
            writer.write("admin_roles = \"" + escapeTomlString(config.admin_roles) + "\"\n");
            writer.write("use_previous_position = " + config.use_previous_position + "\n");
            writer.write("return_to_last_location = " + config.return_to_last_location + "\n\n");
            writer.write("discord_webhook_url = \"" + escapeTomlString(config.discord_webhook_url) + "\"\n\n");

            writer.write("[release_position]\n");
            writer.write("x = " + config.release_position.x + "\n");
            writer.write("y = " + config.release_position.y + "\n");
            writer.write("z = " + config.release_position.z + "\n\n");

            writer.write("[jail_position]\n");
            writer.write("x = " + config.jail_position.x + "\n");
            writer.write("y = " + config.jail_position.y + "\n");
            writer.write("z = " + config.jail_position.z + "\n");

            System.out.println("Configuration saved: " + TOML_CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveConfig() {
        if (configFormat == ConfigFormat.TOML) {
            saveTomlConfig();
            return;
        }

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
            System.out.println("Configuration saved: " + CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String resolveActiveWebhookUrl() {
        if (config.discord_webhook_url != null && !config.discord_webhook_url.isBlank()) {
            return config.discord_webhook_url.trim();
        }
        return readBanHammerWebhookUrl();
    }

    private String readBanHammerWebhookUrl() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("banhammer")) {
                return "";
            }
            java.nio.file.Path path = Paths.get("config/banhammer/config.json");
            if (!Files.exists(path)) {
                return "";
            }
            String json = Files.readString(path);
            Map<?, ?> parsed = GSON.fromJson(json, Map.class);
            if (parsed != null) {
                Object url = parsed.get("discordWebhookUrl");
                if (url instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        } catch (Exception e) {
            System.err.println("JailMod: Failed to read BanHammer webhook: " + e.getMessage());
        }
        return "";
    }

    private void sendDiscordJailNotification(String title, String targetPlayer, String reason, int durationSeconds,
            String actor, int color) {
        String webhookUrl = resolveActiveWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        Map<String, Object> embed = new HashMap<>();
        embed.put("title", title);
        embed.put("color", color);
        embed.put("timestamp", Instant.now().toString());

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(discordField("Player", targetPlayer, true));
        if (actor != null && !actor.isBlank()) {
            fields.add(discordField("Actor", actor, true));
        }
        if (durationSeconds > 0) {
            fields.add(discordField("Duration", durationSeconds + "s", true));
        }
        if (reason != null && !reason.isBlank()) {
            fields.add(discordField("Reason", reason, false));
        }
        embed.put("fields", fields);

        Map<String, Object> payload = new HashMap<>();
        List<Map<String, Object>> embeds = new ArrayList<>();
        embeds.add(embed);
        payload.put("embeds", embeds);

        final String jsonBody = GSON.toJson(payload);
        CompletableFuture.runAsync(() -> postWebhook(webhookUrl, jsonBody));
    }

    private Map<String, Object> discordField(String name, String value, boolean inline) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    private void postWebhook(String webhookUrl, String body) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(webhookUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(payload.length));
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }
            connection.getResponseCode(); // trigger send
        } catch (Exception e) {
            System.err.println("JailMod: Failed to post Discord webhook: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
