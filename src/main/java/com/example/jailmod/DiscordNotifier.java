package com.example.jailmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DiscordNotifier {
    private static final File BANHAMMER_CONFIG_FILE = new File("config/banhammer/config.json");
    private static final File DISCORD_MESSAGES_FILE = new File("config/jailmod/discord-messages.json");
    private static final int COLOR_RED = 0xED4245;
    private static final int COLOR_GREEN = 0x57F287;
    private static final int COLOR_ORANGE = 0xFEE75C;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String cachedWebhookUrl = null;
    private DiscordMessagesConfig discordMessagesConfig = new DiscordMessagesConfig();

    public void reload() {
        cachedWebhookUrl = null;
        loadDiscordMessagesConfig();
    }

    public void sendJailMessage(JailMod.Config config, String playerName, String reason, int durationSeconds, String actor) {
        if (config == null || !discordMessagesConfig.sendJailMessage) {
            return;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("banned", safe(playerName));
        vars.put("reason", safe(reason));
        vars.put("operator", safe(actor));
        vars.put("expiration_time", durationSeconds + " second(s)");
        sendTemplate(config, discordMessagesConfig.jailMessage, vars);
    }

    public void sendUnjailMessage(JailMod.Config config, String playerName, String reason, String actor, boolean manual) {
        if (config == null) {
            return;
        }
        if (manual && !discordMessagesConfig.sendUnjailMessage) {
            return;
        }
        if (!manual && !discordMessagesConfig.sendAutoUnjailMessage) {
            return;
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("banned", safe(playerName));
        vars.put("reason", safe(reason));
        vars.put("operator", safe(actor));
        vars.put("expiration_time", "0 second(s)");
        sendTemplate(config, manual ? discordMessagesConfig.unjailMessage : discordMessagesConfig.autoUnjailMessage, vars);
    }

    private void sendTemplate(JailMod.Config config, MessageTemplate template, Map<String, String> vars) {
        if (template == null) {
            return;
        }
        String webhookUrl = resolveActiveWebhookUrl(config);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        if (!template.name.isBlank()) {
            payload.put("username", replaceVars(template.name, vars));
        }
        if (!template.avatar.isBlank()) {
            payload.put("avatar_url", replaceVars(template.avatar, vars));
        }

        if (template.embed) {
            Map<String, Object> embed = new HashMap<>();
            String description = joinLines(template.embedMessage, vars);
            if (!description.isBlank()) {
                embed.put("description", description);
            }
            if (!template.embedTitle.isBlank()) {
                embed.put("title", replaceVars(template.embedTitle, vars));
            }
            if (!template.embedTitleUrl.isBlank()) {
                embed.put("url", replaceVars(template.embedTitleUrl, vars));
            }
            if (!template.embedAuthor.isBlank()) {
                Map<String, Object> author = new HashMap<>();
                author.put("name", replaceVars(template.embedAuthor, vars));
                if (!template.embedAuthorUrl.isBlank()) {
                    author.put("url", replaceVars(template.embedAuthorUrl, vars));
                }
                if (!template.embedAuthorIconUrl.isBlank()) {
                    author.put("icon_url", replaceVars(template.embedAuthorIconUrl, vars));
                }
                embed.put("author", author);
            }
            if (!template.embedImage.isBlank()) {
                embed.put("image", Map.of("url", replaceVars(template.embedImage, vars)));
            }
            if (!template.embedThumbnail.isBlank()) {
                embed.put("thumbnail", Map.of("url", replaceVars(template.embedThumbnail, vars)));
            }
            if (!template.embedFooter.isBlank()) {
                Map<String, Object> footer = new HashMap<>();
                footer.put("text", replaceVars(template.embedFooter, vars));
                if (!template.embedFooterIconUrl.isBlank()) {
                    footer.put("icon_url", replaceVars(template.embedFooterIconUrl, vars));
                }
                embed.put("footer", footer);
            }
            embed.put("color", colorFromName(template.embedColor));
            if (!template.embedFields.isEmpty()) {
                List<Map<String, Object>> fields = new ArrayList<>();
                for (EmbedField field : template.embedFields) {
                    if (field == null || field.name == null || field.value == null) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", replaceVars(field.name, vars));
                    item.put("value", replaceVars(field.value, vars));
                    item.put("inline", field.inline);
                    fields.add(item);
                }
                if (!fields.isEmpty()) {
                    embed.put("fields", fields);
                }
            }
            payload.put("embeds", List.of(embed));
        } else {
            String content = joinLines(template.message, vars);
            if (content.isBlank()) {
                return;
            }
            payload.put("content", content);
        }

        final String body = GSON.toJson(payload);
        CompletableFuture.runAsync(() -> postWebhook(webhookUrl, body));
    }

    private String resolveActiveWebhookUrl(JailMod.Config config) {
        if (config.discord_webhook_url != null && !config.discord_webhook_url.isBlank()) {
            cachedWebhookUrl = config.discord_webhook_url.trim();
            return cachedWebhookUrl;
        }
        if (cachedWebhookUrl != null) {
            return cachedWebhookUrl;
        }
        if (!config.useBanhammerWebhook) {
            cachedWebhookUrl = "";
            return cachedWebhookUrl;
        }
        cachedWebhookUrl = readBanHammerWebhookUrl();
        return cachedWebhookUrl;
    }

    private String readBanHammerWebhookUrl() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("banhammer")) {
                return "";
            }
            if (!BANHAMMER_CONFIG_FILE.exists()) {
                return "";
            }
            try (FileReader reader = new FileReader(BANHAMMER_CONFIG_FILE)) {
                Map<?, ?> parsed = GSON.fromJson(reader, Map.class);
                if (parsed == null) {
                    return "";
                }
                Object urls = parsed.get("discordWebhookUrls");
                if (urls instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String s && !s.isBlank()) {
                            return s.trim();
                        }
                    }
                }
                Object single = parsed.get("discordWebhookUrl");
                if (single instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        } catch (Exception e) {
            System.err.println("JailMod: Failed to read BanHammer webhook URL: " + e.getMessage());
        }
        return "";
    }

    private void loadDiscordMessagesConfig() {
        try {
            File parent = DISCORD_MESSAGES_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            DiscordMessagesConfig loaded;
            if (!DISCORD_MESSAGES_FILE.exists()) {
                loaded = new DiscordMessagesConfig();
            } else {
                try (FileReader reader = new FileReader(DISCORD_MESSAGES_FILE)) {
                    loaded = GSON.fromJson(reader, DiscordMessagesConfig.class);
                }
                if (loaded == null) {
                    loaded = new DiscordMessagesConfig();
                }
            }

            loaded.patch();
            this.discordMessagesConfig = loaded;

            try (FileWriter writer = new FileWriter(DISCORD_MESSAGES_FILE)) {
                GSON.toJson(loaded, writer);
            }
        } catch (Exception e) {
            System.err.println("JailMod: Failed to load discord-messages.json: " + e.getMessage());
            this.discordMessagesConfig = new DiscordMessagesConfig();
        }
    }

    private String joinLines(List<String> lines, Map<String, String> vars) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(replaceVars(lines.get(i), vars));
            if (i < lines.size() - 1) {
                out.append("\n");
            }
        }
        return out.toString().trim();
    }

    private String replaceVars(String input, Map<String, String> vars) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private int colorFromName(String color) {
        if (color == null) {
            return COLOR_RED;
        }
        return switch (color.trim().toLowerCase()) {
            case "green" -> COLOR_GREEN;
            case "orange" -> COLOR_ORANGE;
            case "red" -> COLOR_RED;
            default -> COLOR_RED;
        };
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
            connection.getResponseCode();
        } catch (Exception e) {
            System.err.println("JailMod: Failed to post Discord webhook: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public static class DiscordMessagesConfig {
        public boolean sendJailMessage = true;
        public MessageTemplate jailMessage = MessageTemplate.defaultJailMessage();
        public boolean sendUnjailMessage = true;
        public MessageTemplate unjailMessage = MessageTemplate.defaultManualUnjailMessage();
        public boolean sendAutoUnjailMessage = true;
        public MessageTemplate autoUnjailMessage = MessageTemplate.defaultAutoUnjailMessage();

        public void patch() {
            if (jailMessage == null) {
                jailMessage = MessageTemplate.defaultJailMessage();
            }
            if (unjailMessage == null) {
                unjailMessage = MessageTemplate.defaultManualUnjailMessage();
            }
            if (autoUnjailMessage == null) {
                autoUnjailMessage = MessageTemplate.defaultAutoUnjailMessage();
            }
            jailMessage.patch();
            unjailMessage.patch();
            autoUnjailMessage.patch();
        }
    }

    public static class MessageTemplate {
        public boolean embed = true;
        public List<String> message = new ArrayList<>();
        public String avatar = "";
        public String name = "";
        public String embedAuthor = "";
        public String embedAuthorUrl = "";
        public String embedAuthorIconUrl = "";
        public String embedTitle = "";
        public String embedTitleUrl = "";
        public String embedColor = "red";
        public String embedImage = "";
        public String embedThumbnail = "";
        public String embedFooter = "";
        public String embedFooterIconUrl = "";
        public List<String> embedMessage = new ArrayList<>();
        public List<EmbedField> embedFields = new ArrayList<>();

        static MessageTemplate defaultJailMessage() {
            MessageTemplate t = new MessageTemplate();
            t.embedColor = "red";
            t.embedMessage = List.of(
                    "${banned} has been jailed!",
                    "",
                    "**Reason**: ${reason}",
                    "**Expires in**: ${expiration_time}",
                    "**By**: ${operator}");
            return t;
        }

        static MessageTemplate defaultManualUnjailMessage() {
            MessageTemplate t = new MessageTemplate();
            t.embedColor = "red";
            t.embedMessage = List.of(
                    "${banned} has been unjailed!",
                    "",
                    "**Reason**: ${reason}",
                    "**By**: ${operator}");
            return t;
        }

        static MessageTemplate defaultAutoUnjailMessage() {
            MessageTemplate t = new MessageTemplate();
            t.embedColor = "green";
            t.embedMessage = List.of(
                    "${banned} has been unjailed!",
                    "",
                    "**Reason**: ${reason}",
                    "**By**: ${operator}");
            return t;
        }

        void patch() {
            if (message == null) {
                message = new ArrayList<>();
            }
            if (avatar == null) {
                avatar = "";
            }
            if (name == null) {
                name = "";
            }
            if (embedAuthor == null) {
                embedAuthor = "";
            }
            if (embedAuthorUrl == null) {
                embedAuthorUrl = "";
            }
            if (embedAuthorIconUrl == null) {
                embedAuthorIconUrl = "";
            }
            if (embedTitle == null) {
                embedTitle = "";
            }
            if (embedTitleUrl == null) {
                embedTitleUrl = "";
            }
            if (embedColor == null || embedColor.isBlank()) {
                embedColor = "red";
            }
            if (embedImage == null) {
                embedImage = "";
            }
            if (embedThumbnail == null) {
                embedThumbnail = "";
            }
            if (embedFooter == null) {
                embedFooter = "";
            }
            if (embedFooterIconUrl == null) {
                embedFooterIconUrl = "";
            }
            if (embedMessage == null) {
                embedMessage = new ArrayList<>();
            }
            if (embedFields == null) {
                embedFields = new ArrayList<>();
            }
        }
    }

    public static class EmbedField {
        public String name = "";
        public String value = "";
        public boolean inline = false;
    }
}
