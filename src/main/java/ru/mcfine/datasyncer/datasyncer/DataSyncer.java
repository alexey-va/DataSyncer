package ru.mcfine.datasyncer.datasyncer;

import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.JedisPooled;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DataSyncer extends JavaPlugin implements Listener {

    public boolean mainServer;
    JedisPooled pool;
    public String ip;
    public int port;
    public List<String> serverNames = new ArrayList<>();
    long interval = 10L;
    public String name;
    public boolean load = true;
    public String username = "none";
    public String password = "none";
    public boolean writeHistory;
    public boolean em;
    public boolean aurelium;
    public boolean cmi;
    public boolean jobs;
    public Map<Player, List<Sync>> playerSyncs = new HashMap<>();
    BukkitTask task;

    public static DataSyncer plugin;

    @Override
    public void onEnable() {
        try {
            this.saveDefaultConfig();
            plugin = this;
            ip = getConfig().getString("ip", "localhost");
            port = getConfig().getInt("port", 25565);
            serverNames = getConfig().getStringList("server-names");
            name = getConfig().getString("server-name", "lobby");
            mainServer = getConfig().getBoolean("main-server", true);
            interval = getConfig().getLong("interval", 10L);
            load = getConfig().getBoolean("load-data", true);
            username = getConfig().getString("username", "none");
            password = getConfig().getString("password", "none");
            writeHistory = getConfig().getBoolean("write-history", true);
            em = getConfig().getBoolean("elite-mobs", Bukkit.getPluginManager().isPluginEnabled("EliteMobs"));
            aurelium = getConfig().getBoolean("elite-mobs", Bukkit.getPluginManager().isPluginEnabled("AureliumSkills"));
            cmi = getConfig().getBoolean("cmi", Bukkit.getPluginManager().isPluginEnabled("CMI"));
            jobs = getConfig().getBoolean("jobs", Bukkit.getPluginManager().isPluginEnabled("Jobs"));


            if(!Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && em){
                em = false;
                System.out.println("EM not detected! Disabling integration.");
            }

            if(!Bukkit.getPluginManager().isPluginEnabled("AureliumSkills") && aurelium){
                aurelium = false;
                System.out.println("Aurelium Skills not detected! Disabling integration.");
            }

            if(!Bukkit.getPluginManager().isPluginEnabled("CMI") && cmi){
                cmi = false;
                System.out.println("CMI not detected! Disabling integration.");
            }

            if(!Bukkit.getPluginManager().isPluginEnabled("Jobs") && jobs){
                jobs = false;
                System.out.println("Jobs not detected! Disabling integration.");
            }

            Bukkit.getPluginManager().registerEvents(this, this);

            pool = new JedisPooled();
            if (!password.equals("none")) {
                pool = new JedisPooled(ip, port, username, password);
            } else {
                pool = new JedisPooled(ip, port);
            }

            getCommand("datasync").setExecutor((sender, command, label, args) -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    onDisable();
                    onEnable();
                    return true;
                } else {
                    sender.sendMessage("Wrong command! Type /datasyncer reload to reload config.");
                }
                return false;
            });

            getCommand("datasync").setTabCompleter(new TabCompleter() {
                @Override
                public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
                    List<String> res = new ArrayList<>();
                    if (args.length == 1) {
                        res.add("reload");
                    }
                    return res;
                }
            });



            for(Player player : Bukkit.getServer().getOnlinePlayers()) setupPlayerSync(player);
            // Setup main task
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    List<Player> removeList = new ArrayList<>();
                    for(Map.Entry<Player, List<Sync>> entry : playerSyncs.entrySet()){
                        if(entry.getKey() == null || !entry.getKey().isOnline()){
                            removeList.add(entry.getKey());
                        }
                    }
                    for(Player player : removeList) playerSyncs.remove(player);

                    for(Map.Entry<Player, List<Sync>> entry : playerSyncs.entrySet()){
                        for(Sync sync : entry.getValue()){
                            sync.run();
                        }
                    }
                }
            }.runTaskTimerAsynchronously(this, 0L, interval);
        } catch (Exception ex){
            ex.printStackTrace();
            Bukkit.getServer().shutdown();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                setupPlayerSync(event.getPlayer());
            }
        }.runTaskAsynchronously(this);
    }

    private void setupPlayerSync(Player player){
        List<Sync> syncs = new ArrayList<>();
        if(aurelium){
            Sync sync = new AureliumSync(player);
            syncs.add(sync);
        }
        if(em){
            Sync sync = new EliteMobsSync(player);
            syncs.add(sync);
        }
        if(cmi){
            Sync sync = new CMISync(player);
            syncs.add(sync);
        }
        if(jobs){
            Sync sync = new JobsSync(player);
            syncs.add(sync);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.playerSyncs.put(player, syncs);
            }
        }.runTask(this);
    }

    private void clearPlayerSync(Player player){
        List<Sync> syncs = playerSyncs.get(player);
        new BukkitRunnable() {
            @Override
            public void run() {
                for(Sync sync : syncs){
                    sync.uploadData();
                }
            }
        }.runTaskAsynchronously(this);

        playerSyncs.remove(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerSync(event.getPlayer());
    }



    @Override
    public void onDisable() {
        for(Player player : playerSyncs.keySet()){
            for(Sync sync : playerSyncs.get(player)){
                sync.uploadData();
            }
        }
        playerSyncs = new HashMap<>();

        if(em) EliteMobsSync.clearTask();
        if(aurelium) AureliumSync.clearTask();
        if(cmi) CMISync.clearTask();
        if(jobs) JobsSync.clearTask();

        task.cancel();
    }
}
