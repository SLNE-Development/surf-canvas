package dev.slne.surf.testplugin;

import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReconfigurationReproducer extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onBlockBreak(final BlockBreakEvent event) {
        event.getPlayer().getConnection().reenterConfiguration();
    }

    @EventHandler
    public void onReconfigure(final PlayerConnectionReconfigureEvent event) {
        event.getConnection().completeReconfiguration();
    }
}
