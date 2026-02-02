package com.example.jailmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class JailMod implements ModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/jailmod/config.json");
    private static final File LANGUAGE_FILE = new File("config/jailmod/language.txt");
    private static final File JAIL_DATA_FILE = new File("config/jailmod/jail_data.json");
    private static Config config;
    private static Map<String, String> languageStrings = new HashMap<>();
    private static Map<UUID, JailData> jailedPlayers = new HashMap<>();

    private static MinecraftServer serverInstance;

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
                "- release_position: The fallback coordinates for releasing players if no other location (spawn/last) is used.";
        public String admin_roles = "op"; // Comma-separated list of roles/tags that grant admin access. "op" refers to
                                          // operator status.
        public boolean use_previous_position = true; // Use spawn point as fallback
        public boolean return_to_last_location = true; // Return to the exact spot where jailed
        public Position release_position = new Position(100, 65, 100);
        public Position jail_position = new Position(0, 60, 0);

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

        public JailData(UUID playerUUID, BlockPos originalSpawnPos, RegistryKey<World> originalSpawnDimension,
                boolean hadSpawnPoint, String reason, int remainingTicks,
                double lastX, double lastY, double lastZ, float lastYaw, float lastPitch, String lastDimension) {
            this.playerUUID = playerUUID;
            this.originalSpawnPos = originalSpawnPos;
            this.originalSpawnDimension = originalSpawnDimension;
            this.hadSpawnPoint = hadSpawnPoint;
            this.reason = reason;
            this.remainingTicks = remainingTicks;
            this.lastX = lastX;
            this.lastY = lastY;
            this.lastZ = lastZ;
            this.lastYaw = lastYaw;
            this.lastPitch = lastPitch;
            this.lastDimension = lastDimension;
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
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                    .then(CommandManager.argument("time", IntegerArgumentType.integer(1))
                                            .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                                    .executes(context -> {
                                                        String playerName = StringArgumentType.getString(context,
                                                                "player");
                                                        int timeInSeconds = IntegerArgumentType.getInteger(context,
                                                                "time");
                                                        String reason = StringArgumentType.getString(context, "reason");
                                                        ServerPlayerEntity player = context.getSource().getServer()
                                                                .getPlayerManager().getPlayer(playerName);

                                                        if (player != null) {
                                                            jailPlayer(player, timeInSeconds, reason);
                                                            context.getSource().sendFeedback(
                                                                    () -> Text
                                                                            .of("Player " + playerName + " jailed for "
                                                                                    + timeInSeconds + " seconds."),
                                                                    true);
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
                    .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(context -> {
                                String playerName = StringArgumentType.getString(context, "player");
                                ServerPlayerEntity player = context.getSource().getServer().getPlayerManager()
                                        .getPlayer(playerName);
                                if (player != null) {
                                    unjailPlayer(player, true);
                                    context.getSource().sendFeedback(
                                            () -> Text.of("Player " + playerName + " has been released from jail."),
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

    private boolean isPlayerInJail(ServerPlayerEntity player) {
        return jailedPlayers.containsKey(player.getUuid());
    }

    private void jailPlayer(ServerPlayerEntity player, int timeInSeconds, String reason) {
        // Save the player's original spawn position
        BlockPos originalSpawnPos = null;
        RegistryKey<World> originalSpawnDimension = null;
        var respawn = player.getRespawn();
        if (respawn != null && respawn.respawnData() != null) {
            originalSpawnPos = respawn.respawnData().getPos();
            originalSpawnDimension = respawn.respawnData().getDimension();
        }
        boolean hadSpawnPoint = originalSpawnPos != null;

        // Calculate remaining time in ticks
        int remainingTicks = timeInSeconds * 20; // Convert seconds to ticks

        // Capture current position before teleporting
        double lastX = player.getX();
        double lastY = player.getY();
        double lastZ = player.getZ();
        float lastYaw = player.getYaw();
        float lastPitch = player.getPitch();
        String lastDimension = player.getEntityWorld().getRegistryKey().getValue().toString();

        // Save player data
        JailData jailData = new JailData(player.getUuid(), originalSpawnPos, originalSpawnDimension, hadSpawnPoint,
                reason, remainingTicks, lastX, lastY, lastZ, lastYaw, lastPitch, lastDimension);
        jailedPlayers.put(player.getUuid(), jailData);

        jailPlayer(player, jailData);

        saveJailData();
    }

    private void jailPlayer(ServerPlayerEntity player, JailData jailData) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        BlockPos jailPos = new BlockPos(config.jail_position.x, config.jail_position.y, config.jail_position.z);
        player.teleport(world, jailPos.getX() + 0.5, jailPos.getY(), jailPos.getZ() + 0.5,
                EnumSet.noneOf(PositionFlag.class), player.getYaw(), player.getPitch(), false);

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

    private void unjailPlayer(ServerPlayerEntity player, boolean isManual) {
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

            if (isManual) {
                String messageToPlayer = languageStrings.get("unjail_player_manual");
                player.sendMessage(Text.of(messageToPlayer), false);

                String broadcastMessage = languageStrings.get("unjail_broadcast_manual")
                        .replace("{player}", player.getName().getString());
                serverInstance.getPlayerManager().broadcast(Text.of(broadcastMessage), false);
            } else {
                String messageToPlayer = languageStrings.get("unjail_player_auto");
                player.sendMessage(Text.of(messageToPlayer), false);

                String broadcastMessage = languageStrings.get("unjail_broadcast_auto")
                        .replace("{player}", player.getName().getString());
                serverInstance.getPlayerManager().broadcast(Text.of(broadcastMessage), false);
            }

            saveJailData();
        }
    }

    // [Rest of file is identical]
    private void releasePlayer(UUID playerUUID) {
        ServerPlayerEntity player = serverInstance.getPlayerManager().getPlayer(playerUUID);
        if (player != null) {
            unjailPlayer(player, false);
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
        languageStrings.put("jail_player", "You have been jailed for {time} seconds! Reason: {reason}");
        languageStrings.put("jail_broadcast", "{player} has been jailed for {time} seconds. Reason: {reason}");
        languageStrings.put("unjail_player_manual", "You have been manually released from jail!");
        languageStrings.put("unjail_broadcast_manual", "{player} has been manually released from jail!");
        languageStrings.put("unjail_player_auto", "You have been released after serving your sentence.");
        languageStrings.put("unjail_broadcast_auto", "{player} has been released after serving their sentence.");
        languageStrings.put("block_interaction_denied", "You cannot interact with blocks while in jail!");
        languageStrings.put("entity_interaction_denied", "You cannot interact with entities while in jail!");
        languageStrings.put("bucket_use_denied", "You cannot use lava or water buckets while in jail!");
        languageStrings.put("item_use_denied", "You cannot use items while in jail!");
        languageStrings.put("block_break_denied", "You cannot break blocks while in jail!");

        // New strings for /jail info command
        languageStrings.put("jail_info_message", "You are in jail for another {time} seconds. Reason: {reason}.");
        languageStrings.put("not_in_jail_message", "You are not in jail!");

        saveLanguage();
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

    private void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
            System.out.println("Configuration saved: " + CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
