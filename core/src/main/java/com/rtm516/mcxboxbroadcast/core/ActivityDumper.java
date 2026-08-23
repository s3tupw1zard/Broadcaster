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

/**
 * Debug helper for dumping another Xbox user's active Minecraft session.
 */
public final class ActivityDumper {
    private static final String PEOPLE_LOOKUP = "https://social.xboxlive.com/users/me/people/gt(%s)";
    private static final String SESSIONS_QUERY = "https://sessiondirectory.xboxlive.com/serviceconfigs/%s/sessions?xuid=%s&take=100";
    private static final String SESSION_URL = "https://sessiondirectory.xboxlive.com/serviceconfigs/%s/sessionTemplates/%s/sessions/%s";

    private ActivityDumper() {
    }

    /**
     * Dump active Minecraft sessions for a numeric XUID or a gamertag.
     */
    public static void dump(SessionManager sessionManager, Logger logger, Path outputDirectory, String user) {
        HttpClient client = HttpClient.newHttpClient();

        try {
            Files.createDirectories(outputDirectory);

            String xuid = user.matches("\\d+")
                ? user
                : resolveXuid(client, sessionManager, logger, outputDirectory, user);
            if (xuid == null) {
                return;
            }

            logger.info("Querying active Minecraft sessions for XUID " + xuid
                + (user.equals(xuid) ? "" : " (gamertag: " + user + ")"));

            deleteOldSessionDumps(outputDirectory, xuid);

            String queryUrl = SESSIONS_QUERY.formatted(
                pathSegment(Constants.SERVICE_CONFIG_ID),
                pathSegment(xuid)
            );

            HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create(queryUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", sessionManager.getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .GET()
                .build();

            HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
            Path listFile = outputDirectory.resolve("activitySessions-" + xuid + ".json");
            Files.writeString(listFile, listResponse.body(), StandardCharsets.UTF_8);

            if (listResponse.statusCode() < 200 || listResponse.statusCode() >= 300) {
                logger.error("Xbox session query failed with HTTP " + listResponse.statusCode()
                    + ". Response saved to '" + listFile + "'.");
                return;
            }

            JsonArray results = getResults(listResponse.body());
            if (results == null || results.size() == 0) {
                logger.info("No active Minecraft sessions found for XUID " + xuid
                    + ". Raw response saved to '" + listFile + "'.");
                return;
            }

            int sessionIndex = 0;
            for (JsonElement element : results) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject result = element.getAsJsonObject();
                JsonObject sessionRef = object(result, "sessionRef");
                if (sessionRef == null) {
                    continue;
                }

                String scid = string(sessionRef, "scid");
                String templateName = string(sessionRef, "templateName");
                String sessionName = string(sessionRef, "name");
                if (scid == null || templateName == null || sessionName == null) {
                    logger.warn("Skipping session result with an incomplete sessionRef.");
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

                logger.info("Dumped activity session " + sessionIndex
                    + " (template=" + templateName
                    + ", name=" + sessionName
                    + ", HTTP " + sessionResponse.statusCode()
                    + ") to '" + sessionFile + "'.");
            }

            logger.info("Activity dump complete for XUID " + xuid + ": " + sessionIndex
                + " session document(s), plus '" + listFile + "'.");
        } catch (IOException e) {
            logger.error("Failed to dump Xbox activity for '" + user + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while dumping Xbox activity for '" + user + "'", e);
        } catch (JsonParseException | IllegalStateException e) {
            logger.error("Failed to parse Xbox response while dumping activity for '" + user + "'", e);
        }
    }

    private static String resolveXuid(HttpClient client, SessionManager sessionManager, Logger logger,
                                      Path outputDirectory, String gamertag) throws IOException, InterruptedException {
        String lookupUrl = PEOPLE_LOOKUP.formatted(pathSegment(gamertag));
        HttpRequest lookupRequest = HttpRequest.newBuilder()
            .uri(URI.create(lookupUrl))
            .header("Accept", "application/json")
            .header("Authorization", sessionManager.getTokenHeader())
            .GET()
            .build();

        HttpResponse<String> lookupResponse = client.send(lookupRequest, HttpResponse.BodyHandlers.ofString());
        String safeName = safeFileComponent(gamertag);
        Path lookupFile = outputDirectory.resolve("activityUser-" + safeName + ".json");
        Files.writeString(lookupFile, lookupResponse.body(), StandardCharsets.UTF_8);

        if (lookupResponse.statusCode() < 200 || lookupResponse.statusCode() >= 300) {
            logger.error("Failed to resolve gamertag '" + gamertag + "' with HTTP "
                + lookupResponse.statusCode() + ". The target must be visible in the authenticated account's People list. "
                + "Response saved to '" + lookupFile + "'.");
            return null;
        }

        JsonElement root = JsonParser.parseString(lookupResponse.body());
        if (!root.isJsonObject()) {
            logger.error("Xbox People lookup for gamertag '" + gamertag + "' returned an unexpected response. "
                + "Saved to '" + lookupFile + "'.");
            return null;
        }

        String xuid = string(root.getAsJsonObject(), "xuid");
        if (xuid == null || !xuid.matches("\\d+")) {
            logger.error("Xbox People lookup for gamertag '" + gamertag + "' did not return a valid XUID. "
                + "Saved to '" + lookupFile + "'.");
            return null;
        }

        logger.info("Resolved gamertag '" + gamertag + "' to XUID " + xuid + ".");
        return xuid;
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

    private static String safeFileComponent(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteOldSessionDumps(Path outputDirectory, String xuid) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(outputDirectory, "activitySession-" + xuid + "-*.json")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }
}
