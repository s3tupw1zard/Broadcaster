package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

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
 * Debug helper for dumping another Xbox user's active Minecraft session.
 */
public final class ActivityDumper {
    private static final String HANDLES_QUERY = "https://sessiondirectory.xboxlive.com/handles/query";
    private static final String SESSION_URL = "https://sessiondirectory.xboxlive.com/serviceconfigs/%s/sessionTemplates/%s/sessions/%s";

    private ActivityDumper() {
    }

    public static void dump(SessionManager sessionManager, Logger logger, Path outputDirectory, String xuid) {
        HttpClient client = HttpClient.newHttpClient();

        try {
            Files.createDirectories(outputDirectory);
            deleteOldSessionDumps(outputDirectory, xuid);

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
            Path handlesFile = outputDirectory.resolve("activityHandles-" + xuid + ".json");
            Files.writeString(handlesFile, handlesResponse.body(), StandardCharsets.UTF_8);

            if (handlesResponse.statusCode() < 200 || handlesResponse.statusCode() >= 300) {
                logger.error("Xbox activity query failed with HTTP " + handlesResponse.statusCode()
                    + ". Response saved to '" + handlesFile + "'.");
                return;
            }

            JsonArray results = getResults(handlesResponse.body());
            if (results == null || results.size() == 0) {
                logger.info("No active Minecraft activity handles found for XUID " + xuid
                    + ". Raw response saved to '" + handlesFile + "'.");
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

                Path sessionFile = outputDirectory.resolve("activitySession-" + xuid + "-" + sessionIndex + ".json");
                Files.writeString(sessionFile, sessionResponse.body(), StandardCharsets.UTF_8);

                String handleId = string(handle, "id");
                logger.info("Dumped activity session " + sessionIndex
                    + " (handle=" + (handleId == null ? "unknown" : handleId)
                    + ", template=" + templateName
                    + ", name=" + sessionName
                    + ", HTTP " + sessionResponse.statusCode()
                    + ") to '" + sessionFile + "'.");
            }

            logger.info("Activity dump complete for XUID " + xuid + ": " + sessionIndex
                + " session document(s), plus '" + handlesFile + "'.");
        } catch (IOException e) {
            logger.error("Failed to dump Xbox activity for XUID " + xuid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while dumping Xbox activity for XUID " + xuid, e);
        } catch (JsonParseException | IllegalStateException e) {
            logger.error("Failed to parse Xbox activity response for XUID " + xuid, e);
        }
    }

    private static JsonArray getResults(String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonObject()) {
            return null;
        }

        JsonElement results = root.getAsJsonObject().get("results");
        return results != null && results.isJsonArray() ? results.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void deleteOldSessionDumps(Path outputDirectory, String xuid) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(outputDirectory, "activitySession-" + xuid + "-*.json")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }
}
