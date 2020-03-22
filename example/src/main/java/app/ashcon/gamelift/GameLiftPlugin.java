package app.ashcon.gamelift;

import com.amazonaws.services.gamelift.AmazonGameLiftServer;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsRequest;
import com.amazonaws.services.gamelift.model.GameSession;
import com.amazonaws.services.gamelift.model.PlayerSession;
import com.amazonaws.services.gamelift.model.ProcessParameters;
import com.amazonaws.services.gamelift.model.UpdateGameSession;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** An simple integration between {@link AmazonGameLiftServer} and {@link Bukkit}. */
public class GameLiftPlugin extends JavaPlugin implements Listener {

  private Logger logger;
  private AmazonGameLiftServer gamelift;

  @Override
  public void onLoad() {
    logger = getLogger();
    gamelift = AmazonGameLiftServer.get();

    try {
      Handler handler = new FileHandler(new File("logs", "gamelift.log").getPath());
      handler.setLevel(Level.ALL);

      Logger logger = Logger.getLogger("GameLift");
      logger.setLevel(Level.ALL);
      logger.addHandler(handler);

      gamelift.setLogger(logger);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Unable to setup log file", e);
    }
  }

  @Override
  public void onEnable() {
    if (gamelift.initSdk()) {
      Runtime.getRuntime().addShutdownHook(new Thread(gamelift::processEnding));
    } else {
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    onGameReady();

    getServer().getPluginManager().registerEvents(this, this);
  }

  @Override
  public void onDisable() {
    if (gamelift != null && gamelift.getGameSessionId() != null) {
      gamelift.terminateGameSession();
    }

    HandlerList.unregisterAll((Plugin) this);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onEvent(PlayerJoinEvent event) {
    getServer().getScheduler().runTaskAsynchronously(this, () -> onPlayerLogin(event.getPlayer()));
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onEvent(PlayerQuitEvent event) {
    onPlayerQuit(event.getPlayer());
  }

  private void onGameReady() {
    if (gamelift.getGameSessionId() != null) {
      gamelift.terminateGameSession();
    }

    gamelift.processReady(
        new ProcessParameters()
            .withPort(getServer().getPort())
            .withLogPathsToUpload(
                Lists.newArrayList(
                    new File("logs", "latest.log").getPath(),
                    new File("logs", "gamelift.log").getPath()))
            .withGameSessionStart(this::onGameStart)
            .withGameSessionUpdate(this::onGameUpdate)
            .withProcessTerminate(this::onShutdown));
  }

  private void onGameStart(GameSession game) {
    logger.log(
        Level.INFO,
        String.format(
            "Assigned game session %s at %s:%d with %d players",
            game.getGameSessionId(),
            game.getIpAddress(),
            game.getPort(),
            game.getMaximumPlayerSessionCount()));

    gamelift.activateGameSession();

    String data = game.getGameSessionData();
    if (data != null) {
      CommandSender console = getServer().getConsoleSender();
      for (String command : Splitter.on(";").splitToList(data)) {
        getServer()
            .getScheduler()
            .runTask(
                this,
                () -> {
                  logger.log(
                      Level.INFO, String.format("Executing game session command /%s", command));
                  getServer().dispatchCommand(console, command);
                });
      }
    }

    for (Player player : getServer().getOnlinePlayers()) {
      onPlayerLogin(player);
    }
  }

  private void onGameUpdate(UpdateGameSession update) {
    logger.log(
        Level.INFO,
        String.format(
            "Updated game session %s from ticket %s due to %s",
            update.getGameSession().getGameSessionId(),
            update.getBackfillTicketId(),
            update.getUpdateReason()));
  }

  private void onShutdown() {
    Date terminationTime = new Date(gamelift.getTerminationTime() * 1000L);

    logger.log(Level.INFO, String.format("Received signal to shutdown at %tr", terminationTime));

    long tickDelay = (System.currentTimeMillis() - terminationTime.getTime()) / 1000L * 20L;
    getServer()
        .getScheduler()
        .runTaskLaterAsynchronously(this, () -> getServer().shutdown(), Math.max(1, tickDelay));
  }

  private void onPlayerLogin(Player player) {
    if (gamelift.getGameSessionId() == null) {
      logger.log(
          Level.WARNING,
          String.format("No game has been assigned to find session for %s", player.getName()));
      return;
    }

    for (PlayerSession session :
        gamelift
            .describePlayerSessions(
                new DescribePlayerSessionsRequest()
                    .withPlayerId(player.getUniqueId().toString())
                    .withPlayerSessionStatusFilter("RESERVED")
                    .withLimit(5))
            .getPlayerSessions()) {
      if (!Objects.equals(gamelift.getGameSessionId(), session.getGameSessionId())) continue;

      String sessionId = session.getPlayerSessionId();
      gamelift.acceptPlayerSession(sessionId);
      logger.log(
          Level.INFO,
          String.format("Accepted player session %s for %s", sessionId, player.getName()));

      player.setMetadata("gamelift", new FixedMetadataValue(this, sessionId));
      return;
    }

    logger.log(Level.WARNING, String.format("Unable to find session for %s", player.getName()));
  }

  private void onPlayerQuit(Player player) {
    for (MetadataValue metadata : player.getMetadata("gamelift")) {
      if (metadata.getOwningPlugin() != this) continue;

      String sessionId = metadata.asString();
      gamelift.removePlayerSession(sessionId);
      logger.log(
          Level.INFO,
          String.format("Removed player session %s for %s", sessionId, player.getName()));
    }
  }
}
