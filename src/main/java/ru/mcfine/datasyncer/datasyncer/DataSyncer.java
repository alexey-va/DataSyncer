package ru.mcfine.datasyncer.datasyncer;

import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import javafx.util.Pair;
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

    JedisPooled pool;
    public String ip;
    public int port;
    public List<String> serverNames = new ArrayList<>();
    long interval = 10L;
    public String name;
    public String mainName;
    public boolean load = true;
    public String username = "none";
    public String password = "none";
    public boolean writeHistory;

    File playerDataFile;
    FileConfiguration playerConfiguration;
    BukkitTask playerSaveTask = null;

    File historyFile;
    FileConfiguration historyConfiguration;
    BukkitTask generalSaveTask = null;
    public ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>>();

    public Map<Player, BukkitTask> saveTasks = new HashMap<>();
    public DataSyncer plugin;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        plugin = this;
        ip = getConfig().getString("ip", "localhost");
        port = getConfig().getInt("port", 25565);
        serverNames = getConfig().getStringList("server-names");
        name = getConfig().getString("server-name", "lobby");
        mainName = getConfig().getString("main-server-name", "lobby");
        interval = getConfig().getLong("interval", 10L);
        load = getConfig().getBoolean("load-data", true);
        username = getConfig().getString("username", "none");
        password = getConfig().getString("password", "none");
        writeHistory = getConfig().getBoolean("write-history", true);

        Bukkit.getPluginManager().registerEvents(this, this);
        generalSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                savePlayerData();
            }
        }.runTaskTimerAsynchronously(this, 20L, 100L);

        pool = new JedisPooled();
        if (!password.equals("none")) {
            pool = new JedisPooled(ip, port, username, password);
        } else {
            pool = new JedisPooled(ip, port);
        }
        getLogger().info("Redis DB connected.");

        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            getLogger().info("Creating task for " + player.getName() + ".");
            createSaveTask(player);
        }

        playerDataFile = new File(getDataFolder() + File.separator + "player-data.yml");
        historyFile = new File(getDataFolder() + File.separator + "history.yml");
        try {
            if (!playerDataFile.exists()) playerDataFile.createNewFile();
            if (!historyFile.exists()) historyFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        playerConfiguration = YamlConfiguration.loadConfiguration(playerDataFile);
        historyConfiguration = YamlConfiguration.loadConfiguration(historyFile);


        getCommand("emsync").setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                onDisable();
                onEnable();
                return true;
            }
            else {
                sender.sendMessage("Wrong command! Type /datasyncer reload to reload config.");
            }
            return false;
        });

        getCommand("datasyncer").setTabCompleter(new TabCompleter() {
            @Override
            public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
                List<String> res = new ArrayList<>();
                if (args.length == 1) {
                    res.add("reload");
                }
                return res;
            }
        });


    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Map<String, String> latestMap = null;
                long latestMs = playerConfiguration.getLong(event.getPlayer().getUniqueId().toString() + ".timestamp", -1);
                String latestName = null;
                for (String serverName : serverNames) {
                    Map<String, String> map = pool.hgetAll("EMData:" + serverName + ":" + event.getPlayer().getUniqueId().toString());
                    if (map.size() == 0) continue;
                    long ms = Long.parseLong(map.get("timestamp"));
                    if (ms > latestMs) {
                        latestMap = map;
                        latestMs = ms;
                        latestName = serverName;
                    }
                }

                final int[] counter = {0};

                if (latestMap == null) {
                    //getLogger().info("No entry was found for " + event.getPlayer().getName() + " but this is not a main server.");
                    if (name.equals(mainName)) {
                        //getLogger().info("Sending initial data of " + event.getPlayer().getName() + ".");
                        createSaveTask(event.getPlayer());
                    }
                    return;
                } else if (latestName.equals(name)) {
                    //getLogger().info("This server has the latest data for player " + event.getPlayer().getName() + ".");
                    return;
                }

                final Player player = event.getPlayer();
                final boolean[] uploaded = {false};
                Map<String, String> finalLatestMap = latestMap;
                BukkitTask task = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!uploaded[0]) {
                            if (!PlayerData.isInMemory(player)) {
                                return;
                            }

                            Map<String, String> map = getValueMap(player);
                            map.put("name", player.getName());
                            playerConfiguration.set(player.getUniqueId().toString(), map);

                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    PlayerData data = PlayerData.getPlayerData(player.getUniqueId());

                                    data.setCurrency(Double.parseDouble(finalLatestMap.get("currency")));
                                    data.setActiveGuildLevel(Integer.parseInt(finalLatestMap.get("guild-level")));
                                    data.setGuildPrestigeLevel(Integer.parseInt(finalLatestMap.get("prestige")));
                                    data.setHighestLevelKilled(Integer.parseInt(finalLatestMap.get("highest-level-killed")));
                                    data.setMaxGuildLevel(Integer.parseInt(finalLatestMap.get("max-guild-level")));
                                    data.setUseBookMenus(Boolean.parseBoolean(finalLatestMap.get("use-book")));
                                    data.setDismissEMStatusScreenMessage(Boolean.parseBoolean(finalLatestMap.get("dismiss-em-status")));
                                    data.setScore(Integer.parseInt(finalLatestMap.get("score")));
                                    data.setKills(Integer.parseInt(finalLatestMap.get("kills")));
                                    data.setDeaths(Integer.parseInt(finalLatestMap.get("deaths")));

                                    getLogger().info("Data for player " + event.getPlayer().getName() + " was uploaded.");
                                }
                            }.runTask(plugin);
                            if (writeHistory) historyQueue.add(new Pair<>(map, finalLatestMap));


                            uploaded[0] = true;

                        } else pool.hset("EMData:" + name + ":" + player.getUniqueId().toString(), getValueMap(player));
                    }
                }.runTaskTimerAsynchronously(plugin, 0L, interval);
                saveTasks.put(event.getPlayer(), task);
            }
        }.runTaskAsynchronously(this);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (PlayerData.isInMemory(event.getPlayer())) {
            Player player = event.getPlayer();
            Map<String, String> map = getValueMap(player);
            new BukkitRunnable() {
                @Override
                public void run() {
                    pool.hset("EMData:" + name + ":" + player.getUniqueId().toString(), map);
                    map.put("name", player.getName());
                    playerConfiguration.set(player.getUniqueId().toString(), map);
                }
            }.runTaskAsynchronously(this);
        }
        if (saveTasks.containsKey(event.getPlayer()) && !saveTasks.get(event.getPlayer()).isCancelled()) {
            saveTasks.get(event.getPlayer()).cancel();
            saveTasks.remove(event.getPlayer());
        }
    }

    public Map<String, String> getValueMap(Player player) {
        double currency = PlayerData.getCurrency(player.getUniqueId());
        int gl = PlayerData.getActiveGuildLevel(player.getUniqueId());
        int prestige = PlayerData.getGuildPrestigeLevel(player.getUniqueId());
        int hlk = PlayerData.getHighestLevelKilled(player.getUniqueId());
        int mgl = PlayerData.getMaxGuildLevel(player.getUniqueId());
        boolean book = PlayerData.getUseBookMenus(player.getUniqueId());
        boolean dismiss = PlayerData.getDismissEMStatusScreenMessage(player.getUniqueId());
        int deaths = PlayerData.getDeaths(player.getUniqueId());
        int kills = PlayerData.getKills(player.getUniqueId());
        int score = PlayerData.getScore(player.getUniqueId());
        long time = System.currentTimeMillis();
        Map<String, String> map = new HashMap<>();
        map.put("currency", currency + "");
        map.put("guild-level", gl + "");
        map.put("prestige", prestige + "");
        map.put("highest-level-killed", hlk + "");
        map.put("max-guild-level", mgl + "");
        map.put("use-book", book + "");
        map.put("dismiss-em-status", dismiss+"");
        map.put("timestamp", time + "");
        map.put("deaths", deaths+"");
        map.put("kills", kills+"");
        map.put("score", score+"");
        return map;
    }

    public void savePlayerData() {
        try {
            playerConfiguration.save(playerDataFile);
            while (!historyQueue.isEmpty()) {
                Pair<Map<String, String>, Map<String, String>> pair = historyQueue.poll();
                Map<String, String> replacementMap = new HashMap<>();
                replacementMap.put("name", pair.getKey().get("name"));
                for (String key : pair.getValue().keySet())
                    replacementMap.put(key, (pair.getKey().get(key) + " -> " + pair.getValue().get(key)));
                historyConfiguration.set(pair.getKey().get("timestamp"), replacementMap);
            }
            historyConfiguration.save(historyFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createSaveTask(Player player) {
        if (saveTasks.containsKey(player) && !saveTasks.get(player).isCancelled()) {
            saveTasks.get(player).cancel();
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (PlayerData.isInMemory(player)) {
                    double currency = PlayerData.getCurrency(player.getUniqueId());
                    int gl = PlayerData.getActiveGuildLevel(player.getUniqueId());
                    int prestige = PlayerData.getGuildPrestigeLevel(player.getUniqueId());
                    int hlk = PlayerData.getHighestLevelKilled(player.getUniqueId());
                    int mgl = PlayerData.getMaxGuildLevel(player.getUniqueId());
                    boolean book = PlayerData.getUseBookMenus(player.getUniqueId());
                    long time = System.currentTimeMillis();
                    Map<String, String> map = new HashMap<>();
                    map.put("currency", currency + "");
                    map.put("guild-level", gl + "");
                    map.put("prestige", prestige + "");
                    map.put("highest-level-killed", hlk + "");
                    map.put("max-guild-level", mgl + "");
                    map.put("use-book", book + "");
                    map.put("timestamp", time + "");
                    pool.hset("EMData:" + name + ":" + player.getUniqueId().toString(), map);

                }
            }
        }.runTaskTimerAsynchronously(this, 0L, interval);
        saveTasks.put(player, task);
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : saveTasks.values()) {
            if (task != null && !task.isCancelled()) task.cancel();
        }
        savePlayerData();
    }
}
