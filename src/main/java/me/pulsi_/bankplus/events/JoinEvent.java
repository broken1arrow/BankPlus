package me.pulsi_.bankplus.events;

import me.pulsi_.bankplus.BankPlus;
import me.pulsi_.bankplus.utils.ChatUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;

public class JoinEvent implements Listener {

    private BankPlus plugin;
    public JoinEvent(BankPlus plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) throws IOException {

        Player p = e.getPlayer();

        long startAmount = plugin.getConfiguration().getLong("General.Join-Start-Amount");

        if (plugin.getConfiguration().getBoolean("General.Use-UUID")) {
            if (plugin.getPlayers().getString("Players." + p.getUniqueId() + ".Money") == null) {

                plugin.getServer().getConsoleSender().sendMessage(ChatUtils.c("&a&lBank&9&lPlus &aSuccessfully registered " + p.getName()));
                plugin.getPlayers().set("Players." + p.getUniqueId() + ".Money", startAmount);
                plugin.savePlayers();
            }
            if (plugin.getPlayers().getString("Players." + p.getUniqueId() + ".Name") == null) {
                plugin.getPlayers().set("Players." + p.getUniqueId() + ".Name", p.getName());
                plugin.savePlayers();
            }
        } else {
            if (plugin.getPlayers().getString("Players." + p.getName() + ".Money") == null) {

                plugin.getServer().getConsoleSender().sendMessage(ChatUtils.c("&a&lBank&9&lPlus &aSuccessfully registered " + p.getName()));
                plugin.getPlayers().set("Players." + p.getName() + ".Money", startAmount);
                plugin.savePlayers();
            }
        }
    }
}