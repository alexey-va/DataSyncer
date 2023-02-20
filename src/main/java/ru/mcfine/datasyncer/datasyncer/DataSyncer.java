package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.events.CMIUserBalanceChangeEvent;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.Job;
import com.magmaguy.elitemobs.api.CustomEventStartEvent;
import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import io.github.thebusybiscuit.slimefun4.api.events.ResearchUnlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.JedisPooled;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DataSyncer extends JavaPlugin implements Listener {

    public boolean mainServer;
    public int delay;
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
    public boolean stats;
    public Map<Player, List<Sync>> playerSyncs = new ConcurrentHashMap<>();
    BukkitTask task;

    public static DataSyncer plugin;
    static BalanceSync balanceSync = null;
    static SlimefunSync slimefunSync = null;

    @Override
    public void onEnable() {
        try {
            this.saveDefaultConfig();
            plugin = this;
            ip = getConfig().getString("redis.ip", "localhost");
            port = getConfig().getInt("redis.port", 25565);
            serverNames = getConfig().getStringList("servers.server-names");
            name = getConfig().getString("servers.this-server", "lobby");
            mainServer = getConfig().getBoolean("servers.main-server", true);
            interval = getConfig().getLong("general.ticks-per-cycle", 10L);
            username = getConfig().getString("redis.username", "none");
            password = getConfig().getString("redis.password", "none");
            writeHistory = getConfig().getBoolean("general.write-history", true);
            em = getConfig().getBoolean("integrations.elite-mobs", Bukkit.getPluginManager().isPluginEnabled("EliteMobs"));
            aurelium = getConfig().getBoolean("integrations.aurelium-skills", Bukkit.getPluginManager().isPluginEnabled("AureliumSkills"));
            cmi = getConfig().getBoolean("integrations.cmi", Bukkit.getPluginManager().isPluginEnabled("CMI"));
            jobs = getConfig().getBoolean("integrations.jobs", Bukkit.getPluginManager().isPluginEnabled("Jobs"));
            stats = getConfig().getBoolean("integrations.stats", true);
            delay = getConfig().getInt("general.delay-cycles", 2);

            if (!serverNames.contains(name)) {
                getLogger().severe("Server names don't have THIS server's name.");
                Bukkit.getPluginManager().disablePlugin(this);
            }


            if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && em) {
                em = false;
                System.out.println("EM not detected! Disabling integration.");
            }

            if (!Bukkit.getPluginManager().isPluginEnabled("AureliumSkills") && aurelium) {
                aurelium = false;
                System.out.println("Aurelium Skills not detected! Disabling integration.");
            }

            if (!Bukkit.getPluginManager().isPluginEnabled("CMI") && cmi) {
                cmi = false;
                System.out.println("CMI not detected! Disabling integration.");
            }

            if (!Bukkit.getPluginManager().isPluginEnabled("Jobs") && jobs) {
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

            if (balanceSync == null) balanceSync = new BalanceSync();
            if (slimefunSync == null) slimefunSync = new SlimefunSync();

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


            for (Player player : Bukkit.getServer().getOnlinePlayers()) setupPlayerSync(player);
            // Setup main task
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    List<Player> removeList = new ArrayList<>();
                    for (Map.Entry<Player, List<Sync>> entry : playerSyncs.entrySet()) {
                        if (entry.getKey() == null || !entry.getKey().isOnline()) {
                            removeList.add(entry.getKey());
                        }
                    }
                    for (Player player : removeList) playerSyncs.remove(player);

                    for (Map.Entry<Player, List<Sync>> entry : playerSyncs.entrySet()) {
                        if (entry.getValue() == null) continue;
                        for (Sync sync : entry.getValue()) {
                            sync.run();
                        }
                    }
                }
            }.runTaskTimerAsynchronously(this, 0L, interval);
        } catch (Exception ex) {
            ex.printStackTrace();
            Bukkit.getServer().shutdown();
        }
    }

    @EventHandler
    public void onBalanceChange(CMIUserBalanceChangeEvent event) {
        var transactionInfoSet = BalanceSync.transMap.get(event.getUser().getOfflinePlayer().getUniqueId().toString());
        boolean trans = false;
        if (transactionInfoSet != null) {
            BalanceSync.TransactionInfo transaction = null;
            for (var tr : transactionInfoSet) {
                if(tr == null) continue;
                if (Math.abs(tr.from - event.getFrom()) < 0.000001 && Math.abs(tr.to - event.getTo()) < 0.000001) {
                    trans = true;
                    transaction = tr;
                    break;
                }
            }
            if (transaction != null) transactionInfoSet.remove(transaction);
        }

        if (trans) return;
        String source = null;
        if (event.getSource() != null && event.getSource().getName() != null) source = event.getSource().getName();
        balanceSync.sendChangeMessage(event.getUser().getOfflinePlayer().getUniqueId().toString(),
                event.getFrom(), event.getTo(), event.getActionType(), source);
    }

    @EventHandler
    public void onResearch(ResearchUnlockEvent event){
        if(event.isCancelled()) return;
        var researchSet = SlimefunSync.researchMap.get(event.getPlayer().getUniqueId().toString());
        if(researchSet != null){
            SlimefunSync.ResearchInfo found = null;
            for(var res : researchSet){
                if(res.namespace.equals(event.getResearch().getKey().getNamespace()) &&
                res.key.equals(event.getResearch().getKey().getKey())){
                    found = res;
                    break;
                }
            }
            if(found != null){
                researchSet.remove(found);
                return;
            }
        }
        slimefunSync.sendChangeMessage(event.getPlayer().getUniqueId().toString(), event.getResearch());
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

    private void setupPlayerSync(Player player) {
        List<Sync> syncs = new ArrayList<>();
        if (aurelium) {
            Sync sync = new AureliumSync(player);
            syncs.add(sync);
        }
        if (em) {
            Sync sync = new EliteMobsSync(player);
            syncs.add(sync);
        }
        if (cmi) {
            Sync sync = new CMISync(player);
            syncs.add(sync);
        }
        if (jobs) {
            Sync sync = new JobsSync(player);
            syncs.add(sync);
        }
        if (stats) {
            Sync sync = new StatSync(player);
            syncs.add(sync);
        }
        plugin.playerSyncs.put(player, syncs);
    }

    private void clearPlayerSync(Player player) {
        List<Sync> syncs = playerSyncs.get(player);
        for (Sync sync : syncs) {
            sync.uploadData();
        }
        playerSyncs.remove(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerSync(event.getPlayer());
    }


    @Override
    public void onDisable() {
        for (Player player : playerSyncs.keySet()) {
            for (Sync sync : playerSyncs.get(player)) {
                sync.uploadData();
            }
        }
        playerSyncs = new HashMap<>();

        if (em) EliteMobsSync.clearTask();
        if (aurelium) AureliumSync.clearTask();
        if (cmi) CMISync.clearTask();
        if (jobs) JobsSync.clearTask();
        if (stats) StatSync.clearTask();

        task.cancel();
    }
}
