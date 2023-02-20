package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StatSync extends Sync {
    protected static File historyFile;
    protected static FileConfiguration historyConfig;
    protected static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    protected static BukkitTask historySaveTask;
    //protected static File file = null;
    //protected static FileConfiguration config;

    public StatSync(Player player) {
        if (historyFile == null) {
            //file = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "player_data.yml");
            historyFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "stats" + File.separator + "history.yml");
            try {
                //file.getParentFile().mkdir();
                //file.createNewFile();
                historyFile.getParentFile().mkdir();
                historyFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            //config = YamlConfiguration.loadConfiguration(file);
            historyConfig = YamlConfiguration.loadConfiguration(historyFile);
            historySaveTask = new BukkitRunnable() {
                @Override
                public void run() {

                    if (!DataSyncer.plugin.playerSyncs.containsKey(player)) {
                        this.cancel();
                        return;
                    }

                    if (DataSyncer.plugin.writeHistory){

                        try{
                            saveHistory();
                        } catch (Exception ignored){}
                    }
                }
            }.runTaskTimerAsynchronously(DataSyncer.plugin, 100L, 100L);
        }

        this.player = player;
        this.playerName = player.getName();
        map = getLatestData();
    }


    public void uploadData() {
        map = getValueMap();
        if (map.size() == 0) return;
        DataSyncer.plugin.pool.hset("StatData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        //if(config != null)config.set(player.getUniqueId().toString(), map);
    }


    protected void setData() {
        applying = true;
        Map<String, String> oldData = getValueMap();
        final Map<String, String> finalMap = new HashMap<>(map);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String statString : finalMap.keySet()) {
                    if (statString.equals("timestamp") || statString.equals("name")) continue;
                    try {
                        Statistic statistic = Statistic.valueOf(statString);
                        int val = Integer.parseInt(finalMap.get(statString));
                        player.setStatistic(statistic, val);
                    } catch (Exception ig) {
                        System.out.println(statString);
                        ig.printStackTrace();
                    }
                }
                loaded = true;
                applying = false;
                player.saveData();
            }
        }.runTask(DataSyncer.plugin);
        historyQueue.add(new Pair<>(oldData, finalMap));
    }

    protected boolean ifPluginLoaded() {
        return true;
    }

    private Map<String, String> getValueMap() {
        Map<String, String> map = new ConcurrentHashMap<>();
        for (Statistic statistic : Statistic.values()) {
            if (stats.contains(statistic)) continue;
            int stat;
            try {
                stat = player.getStatistic(statistic);
            } catch (Exception ignored) {
                continue;
            }
            map.put(statistic.name(), stat + "");
        }

        long time = System.currentTimeMillis();
        map.put("timestamp", time + "");
        map.put("name", playerName);
        map.entrySet().forEach(entry -> {
            if(entry.getValue() == null) entry.setValue("null");
        });
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        long latestMs = -1;
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("StatData:" + serverName + ":" + player.getUniqueId().toString());

            if (map.size() == 0) continue;
            long ms = Long.parseLong(map.get("timestamp"));
            if (ms > latestMs) {
                latestMap = map;
                latestMs = ms;
            }
        }
        if (latestMap == null) {
            this.loaded = true;
            this.hasEntry = false;
        }
        return latestMap;
    }

    protected static void saveHistory() {
        try {
            boolean anyChanges = false;
            while (!historyQueue.isEmpty()) {
                anyChanges = true;
                Pair<Map<String, String>, Map<String, String>> pair = historyQueue.poll();
                Map<String, String> replacementMap = new HashMap<>();
                String name = pair.getKey().get("name");
                if (name == null) name = "none";
                replacementMap.put("name", name);
                for (String key : pair.getValue().keySet()) {
                    if (key.equals("timestamp")) continue;
                    String k = "none";
                    String v = "none";
                    if (pair.getValue() != null && pair.getValue().get(key) != null) v = pair.getValue().get(key);
                    if (pair.getKey() != null && pair.getKey().get(key) != null) k = pair.getKey().get(key);
                    if (k.equals(v)) continue;
                    if(v == null || k == null){
                        System.out.println("NULL!!! "+k+" | "+v);
                    }
                    replacementMap.put(key, (k + " -> " + v));
                }
                if (replacementMap.size() > 1)
                    historyConfig.set(pair.getKey().get("name") + " - " + new Date(Long.parseLong(pair.getKey().get("timestamp"))), replacementMap);
            }
            if(anyChanges)historyConfig.save(historyFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final Set<Statistic> stats = new HashSet<>() {
        {
            add(Statistic.MINE_BLOCK);
            add(Statistic.BREAK_ITEM);
            add(Statistic.CRAFT_ITEM);
            add(Statistic.USE_ITEM);
            add(Statistic.PICKUP);
            add(Statistic.DROP_COUNT);
            add(Statistic.KILL_ENTITY);
            add(Statistic.ENTITY_KILLED_BY);
        }
    };


    public static void clearTask() {
        //if (file == null) return;
        saveHistory();
        if (historySaveTask != null && !historySaveTask.isCancelled()) historySaveTask.cancel();
        //file = null;
        //config = null;
        historyFile = null;
        historyConfig = null;
    }
}
