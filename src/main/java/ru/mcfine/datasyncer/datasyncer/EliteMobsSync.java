package ru.mcfine.datasyncer.datasyncer;

import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class EliteMobsSync extends Sync {

    protected static File historyFile;
    protected static FileConfiguration historyConfig;
    protected static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    protected static BukkitTask historySaveTask;
    //protected static File file = null;
    //protected static FileConfiguration config;

    public EliteMobsSync(Player player) {
        if (historyFile == null) {
            //file = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "player_data.yml");
            historyFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "elite_mobs" + File.separator + "history.yml");
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
        if(map == null || map.size() == 0) return;
        DataSyncer.plugin.pool.hset("EMData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        //if(config != null)config.set(player.getUniqueId().toString(), map);
    }


    protected void setData() {
        applying = true;
        Map<String, String> oldData = getValueMap();
        final Map<String, String> finalMap = new HashMap<>(map);
        new BukkitRunnable() {
            @Override
            public void run() {
                PlayerData data = PlayerData.getPlayerData(player.getUniqueId());

                data.setCurrency(Double.parseDouble(finalMap.get("currency")));
                data.setActiveGuildLevel(Integer.parseInt(finalMap.get("guild-level")));
                data.setGuildPrestigeLevel(Integer.parseInt(finalMap.get("prestige")));
                data.setHighestLevelKilled(Integer.parseInt(finalMap.get("highest-level-killed")));
                data.setMaxGuildLevel(Integer.parseInt(finalMap.get("max-guild-level")));
                data.setUseBookMenus(true);
                data.setDismissEMStatusScreenMessage(Boolean.parseBoolean(finalMap.get("dismiss-em-status")));
                data.setScore(Integer.parseInt(finalMap.get("score")));
                data.setKills(Integer.parseInt(finalMap.get("kills")));
                data.setDeaths(Integer.parseInt(finalMap.get("deaths")));
                loaded = true;
                applying = false;
            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        oldData.put("name", player.getName());
        historyQueue.add(new Pair<>(oldData, finalMap));
    }

    protected boolean ifPluginLoaded() {
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
