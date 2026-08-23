package com.rtm516.mcxboxbroadcast.bootstrap.geyser;

import com.rtm516.mcxboxbroadcast.core.ActivityDumper;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;

/**
 * MCXboxBroadcast extension entry point with an additional debugging command
 * for dumping another Xbox user's active Minecraft session.
 */
public class MCXboxBroadcastActivityDumpExtension extends MCXboxBroadcastExtension {
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

                if (args.length != 1 || args[0].isBlank()) {
                    source.sendMessage("Usage: dumpactivity <xuid|gamertag>");
                    return;
                }

                String user = args[0];
                logger.info("Dumping active Minecraft session for Xbox user " + user);
                sessionManager.scheduledThread().execute(() ->
                    ActivityDumper.dump(sessionManager, logger, dataFolder(), user)
                );
            })
            .build());
    }

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
}
