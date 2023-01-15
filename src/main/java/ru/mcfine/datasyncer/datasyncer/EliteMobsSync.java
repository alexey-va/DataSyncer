package ru.mcfine.datasyncer.datasyncer;

import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class EliteMobsSync extends Sync {

    private final Player player;
    private boolean loaded = false;
    private int loadingCounter = 0;
    private Map<String, String> map;
    private boolean hasEntry = true;
    public static File eliteMobsFile;
    public static FileConfiguration eliteMobsConfig;
    public static File eliteMobsHistoryFile;
    public static FileConfiguration eliteMobsHistoryConfig;
    public static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    public static BukkitTask historySaveTask;
    private int delayCounter = 0;

    public EliteMobsSync(Player player) {
        if (eliteMobsFile == null) {
            eliteMobsFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "elite_mobs" + File.separator + "player_data.yml");
            eliteMobsHistoryFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "elite_mobs" + File.separator + "history.yml");
            try {
                eliteMobsFile.getParentFile().mkdir();
                eliteMobsFile.createNewFile();
                eliteMobsHistoryFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            eliteMobsConfig = YamlConfiguration.loadConfiguration(eliteMobsFile);
            eliteMobsHistoryConfig = YamlConfiguration.loadConfiguration(eliteMobsHistoryFile);
            historySaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (DataSyncer.plugin.writeHistory) saveHistory();
                    try {
                        eliteMobsConfig.save(eliteMobsFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }.runTaskTimerAsynchronously(DataSyncer.plugin, 100L, 100L);
        }

        this.player = player;
        map = getLatestData();
    }

    public void run() {
        if(delayCounter != -1){
            delayCounter++;
            if(delayCounter > 20){
                delayCounter = -1;
            }
            return;
        }
        if (!ifPluginLoaded()) return;
        if (!loaded) {
            loadingCounter++;
            if (loadingCounter > 100)
                System.out.println("Loading data for " + player.getName() + " takes more than " + loadingCounter + " attempts.");
            setData();
        } else {
            if (!hasEntry && !DataSyncer.plugin.mainServer) return;
            uploadData();
        }
    }

    public void uploadData() {
        map = getValueMap();
        if(map == null || map.size() == 0) return;
        DataSyncer.plugin.pool.hset("EMData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        eliteMobsConfig.set(player.getUniqueId().toString(), map);
        try {
            eliteMobsConfig.save(eliteMobsFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void setData() {
        this.loaded = true;
        Map<String, String> oldData = getValueMap();
        new BukkitRunnable() {
            @Override
            public void run() {
                PlayerData data = PlayerData.getPlayerData(player.getUniqueId());

                data.setCurrency(Double.parseDouble(map.get("currency")));
                data.setActiveGuildLevel(Integer.parseInt(map.get("guild-level")));
                data.setGuildPrestigeLevel(Integer.parseInt(map.get("prestige")));
                data.setHighestLevelKilled(Integer.parseInt(map.get("highest-level-killed")));
                data.setMaxGuildLevel(Integer.parseInt(map.get("max-guild-level")));
                data.setUseBookMenus(Boolean.parseBoolean(map.get("use-book")));
                data.setDismissEMStatusScreenMessage(Boolean.parseBoolean(map.get("dismiss-em-status")));
                data.setScore(Integer.parseInt(map.get("score")));
                data.setKills(Integer.parseInt(map.get("kills")));
                data.setDeaths(Integer.parseInt(map.get("deaths")));
            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        oldData.put("name", player.getName());
        historyQueue.add(new Pair<>(oldData, map));
    }

    private boolean ifPluginLoaded() {
        return PlayerData.isInMemory(player) && PlayerData.getPlayerData(player.getUniqueId()) != null;
    }

    private Map<String, String> getValueMap() {
        PlayerData data = PlayerData.getPlayerData(player.getUniqueId());
        if (data == null) return null;

        double currency = data.getCurrency();
        int gl = data.getActiveGuildLevel();
        int prestige = data.getGuildPrestigeLevel();
        int hlk = data.getHighestLevelKilled();
        int mgl = data.getMaxGuildLevel();
        boolean book = PlayerData.getUseBookMenus(player.getUniqueId());
        boolean dismiss = PlayerData.getDismissEMStatusScreenMessage(player.getUniqueId());
        int deaths = data.getDeaths();
        int kills = data.getKills();
        int score = data.getScore();
        long time = System.currentTimeMillis();

        Map<String, String> map = new HashMap<>();
        map.put("currency", currency + "");
        map.put("guild-level", gl + "");
        map.put("prestige", prestige + "");
        map.put("highest-level-killed", hlk + "");
        map.put("max-guild-level", mgl + "");
        map.put("use-book", book + "");
        map.put("dismiss-em-status", dismiss + "");
        map.put("timestamp", time + "");
        map.put("deaths", deaths + "");
        map.put("kills", kills + "");
        map.put("score", score + "");
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        long latestMs = eliteMobsConfig.getLong(player.getUniqueId().toString() + ".timestamp", -1);
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("EMData:" + serverName + ":" + player.getUniqueId().toString());

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

    private static void saveHistory() {
        try {
            while (!historyQueue.isEmpty()) {
                Pair<Map<String, String>, Map<String, String>> pair = historyQueue.poll();
                Map<String, String> replacementMap = new HashMap<>();
                replacementMap.put("name", pair.getKey().get("name"));
                for (String key : pair.getValue().keySet()) {
                    String k = "none";
                    if(pair.getKey() != null && pair.getKey().get(key) != null) k = pair.getKey().get(key);
                    if (k.equals(pair.getValue().get(key))) continue;
                    replacementMap.put(key, (k + " -> " + pair.getValue().get(key)));
                }
                if (replacementMap.size() > 2)
                    eliteMobsHistoryConfig.set(pair.getKey().get("name") + " - " + pair.getKey().get("timestamp"), replacementMap);
            }
            eliteMobsHistoryConfig.save(eliteMobsHistoryFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clearTask() {
        if(eliteMobsFile == null) return;
        saveHistory();
        if (historySaveTask != null && !historySaveTask.isCancelled()) historySaveTask.cancel();
        eliteMobsFile = null;
        eliteMobsConfig = null;
        eliteMobsHistoryFile = null;
        eliteMobsHistoryConfig = null;
    }

}
