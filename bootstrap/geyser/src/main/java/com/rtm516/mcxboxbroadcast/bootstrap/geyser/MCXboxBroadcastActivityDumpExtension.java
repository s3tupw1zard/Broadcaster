package com.rtm516.mcxboxbroadcast.bootstrap.geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rtm516.mcxboxbroadcast.core.Constants;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * MCXboxBroadcast extension entry point with an additional debugging command
 * for dumping another Xbox user's active Minecraft session.
 */
public class MCXboxBroadcastActivityDumpExtension extends MCXboxBroadcastExtension {
    private static final String HANDLES_QUERY = "https://sessiondirectory.xboxlive.com/handles/query";
    private static final String SESSION_URL = "https://sessiondirectory.xboxlive.com/serviceconfigs/%s/sessionTemplates/%s/sessions/%s";

    @Override
    @Subscribe
    public void onCommandDefine(GeyserDefineCommandsEvent event) {
        super.onCommandDefine(event);

        event.register(Command.builder(this)
            .source(CommandSource.class)
            .name("dumpactivity")
            .description("Dump an Xbox user's active Minecraft session to json files.")
            .executor((source, command, args) -> {
                if (!source.isConsole()) {
                    source.sendMessage("This command can only be ran from the console.");
                    return;
                }

                if (args.length != 1 || !args[0].matches("\\d+")) {
                    source.sendMessage("Usage: dumpactivity <xuid>");
                    return;
                }

                String xuid = args[0];
                logger.info("Dumping active Minecraft session for XUID " + xuid);

                // Do the Xbox Live requests off the server thread.
                sessionManager.scheduledThread().execute(() -> dumpActivity(xuid));
            })
            .build());
    }

    /*
     * Explicitly forward the other lifecycle events so the replacement entry
     * point does not depend on the event bus discovering inherited handlers.
     */
    @Override
    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        super.onPostInitialize(event);
    }

    @Override
    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        super.onShutdown(event);
    }

    @Override
    @Subscribe
    public void onBedrockPing(GeyserBedrockPingEvent event) {
        super.onBedrockPing(event);
    }

    private void dumpActivity(String xuid) {
        HttpClient client = HttpClient.newHttpClient();
        Path dataFolder = dataFolder();

        try {
            Files.createDirectories(dataFolder);
            deleteOldSessionDumps(dataFolder, xuid);

            String queryBody = Constants.GSON.toJson(Map.of(
                "type", "activity",
                "scid", Constants.SERVICE_CONFIG_ID
            ));

            HttpRequest handlesRequest = HttpRequest.newBuilder()
                .uri(URI.create(HANDLES_QUERY + "?xuid=" + xuid))
                .header("Content-Type", "application/json")
                .header("Authorization", sessionManager.getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .POST(HttpRequest.BodyPublishers.ofString(queryBody))
                .build();

            HttpResponse<String> handlesResponse = client.send(handlesRequest, HttpResponse.BodyHandlers.ofString());
            Path handlesFile = dataFolder.resolve("activityHandles-" + xuid + ".json");
            Files.writeString(handlesFile, handlesResponse.body(), StandardCharsets.UTF_8);

            if (handlesResponse.statusCode() < 200 || handlesResponse.statusCode() >= 300) {
                logger.error("Xbox activity query failed with HTTP " + handlesResponse.statusCode()
                    + ". Response saved to '" + handlesFile.getFileName() + "'.");
                return;
            }

            JsonArray results = getResults(handlesResponse.body());
            if (results == null || results.isEmpty()) {
                logger.info("No active Minecraft activity handles found for XUID " + xuid
                    + ". Raw response saved to '" + handlesFile.getFileName() + "'.");
                return;
            }

            int sessionIndex = 0;
            for (JsonElement element : results) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject handle = element.getAsJsonObject();
                JsonObject sessionRef = object(handle, "sessionRef");
                if (sessionRef == null) {
                    continue;
                }

                String scid = string(sessionRef, "scid");
                String templateName = string(sessionRef, "templateName");
                String sessionName = string(sessionRef, "name");
                if (scid == null || templateName == null || sessionName == null) {
                    logger.warn("Skipping activity handle with an incomplete sessionRef.");
                    continue;
                }

                String sessionUrl = SESSION_URL.formatted(
                    pathSegment(scid),
                    pathSegment(templateName),
                    pathSegment(sessionName)
                );

                HttpRequest sessionRequest = HttpRequest.newBuilder()
                    .uri(URI.create(sessionUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", sessionManager.getTokenHeader())
                    .header("x-xbl-contract-version", "107")
                    .GET()
                    .build();

                HttpResponse<String> sessionResponse = client.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
                sessionIndex++;

                Path sessionFile = dataFolder.resolve("activitySession-" + xuid + "-" + sessionIndex + ".json");
                Files.writeString(sessionFile, sessionResponse.body(), StandardCharsets.UTF_8);

                String handleId = string(handle, "id");
                logger.info("Dumped activity session " + sessionIndex
                    + " (handle=" + (handleId == null ? "unknown" : handleId)
                    + ", template=" + templateName
                    + ", name=" + sessionName
                    + ", HTTP " + sessionResponse.statusCode()
                    + ") to '" + sessionFile.getFileName() + "'.");
            }

            logger.info("Activity dump complete for XUID " + xuid + ": " + sessionIndex
                + " session document(s), plus '" + handlesFile.getFileName() + "'.");
        } catch (IOException e) {
            logger.error("Failed to dump Xbox activity for XUID " + xuid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while dumping Xbox activity for XUID " + xuid, e);
        } catch (JsonParseException | IllegalStateException e) {
            logger.error("Failed to parse Xbox activity response for XUID " + xuid, e);
        }
    }

    private JsonArray getResults(String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonObject()) {
            return null;
        }

        JsonElement results = root.getAsJsonObject().get("results");
        return results != null && results.isJsonArray() ? results.getAsJsonArray() : null;
    }

    private JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void deleteOldSessionDumps(Path dataFolder, String xuid) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dataFolder, "activitySession-" + xuid + "-*.json")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }
}
