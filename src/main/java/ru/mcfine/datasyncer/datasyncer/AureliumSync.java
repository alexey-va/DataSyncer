package ru.mcfine.datasyncer.datasyncer;

import com.archyx.aureliumskills.ability.AbstractAbility;
import com.archyx.aureliumskills.api.AureliumAPI;
import com.archyx.aureliumskills.data.AbilityData;
import com.archyx.aureliumskills.data.PlayerData;
import com.archyx.aureliumskills.skills.Skill;
import com.archyx.aureliumskills.skills.Skills;
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

public class AureliumSync extends Sync {

    private final Player player;
    private boolean loaded = false;
    private int loadingCounter = 0;
    private Map<String, String> map;
    private boolean hasEntry = true;
    public static File aureliumFile;
    public static FileConfiguration aureliumConfig;
    public static File aureliumHistoryFile;
    public static FileConfiguration aureliumHistoryConfig;
    public static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    public static BukkitTask historySaveTask;
    private int delayCounter = 0;

    public AureliumSync(Player player) {
        if (aureliumFile == null) {
            aureliumFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" +File.separator + "player_data.yml");
            aureliumHistoryFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" +File.separator + "history.yml");
            try {
                aureliumFile.getParentFile().mkdir();
                aureliumFile.createNewFile();
                aureliumHistoryFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            aureliumConfig = YamlConfiguration.loadConfiguration(aureliumFile);
            aureliumHistoryConfig = YamlConfiguration.loadConfiguration(aureliumHistoryFile);
            historySaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (DataSyncer.plugin.writeHistory) saveHistory();
                    try {
                        aureliumConfig.save(aureliumFile);
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
        DataSyncer.plugin.pool.hset("AUData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        aureliumConfig.set(player.getUniqueId().toString(), map);
    }

    private void setData() {
        this.loaded = true;
        Map<String, String> oldData = getValueMap();
        new BukkitRunnable() {
            @Override
            public void run() {
                PlayerData data = AureliumAPI.getPlugin().getPlayerManager().getPlayerData(player);
                if (data == null) return;

                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey().startsWith("LEVEL:")) {
                        Skill skill = Skills.valueOf(entry.getKey().replace("LEVEL:", ""));
                        data.setSkillLevel(skill, Integer.parseInt(entry.getValue()));
                    } else if (entry.getKey().startsWith("XP:")) {
                        Skill skill = Skills.valueOf(entry.getKey().replace("XP:", ""));
                        data.setSkillXp(skill, Double.parseDouble(entry.getValue()));
                    }
                }
                data.setMana(Double.parseDouble(map.get("mana")));
                data.setShouldSave(true);
            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        oldData.put("name", player.getName());
        historyQueue.add(new Pair<>(oldData, map));
    }

    private boolean ifPluginLoaded() {
        return AureliumAPI.getPlugin().getPlayerManager().hasPlayerData(player);
    }

    private Map<String, String> getValueMap() {
        PlayerData data = AureliumAPI.getPlugin().getPlayerManager().getPlayerData(player);
        if (data == null) return null;
        Map<String, String> map = new HashMap<>();

        double mana = data.getMana();
        map.put("mana", mana + "");
        for (Map.Entry<Skill, Integer> entry : data.getSkillLevelMap().entrySet()) {
            map.put("LEVEL:" + entry.getKey().name(), entry.getValue() + "");
        }
        for (Map.Entry<Skill, Double> entry : data.getSkillXpMap().entrySet()) {
            map.put("XP:" + entry.getKey().name(), entry.getValue() + "");
        }

        long time = System.currentTimeMillis();
        map.put("timestamp", time + "");
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        long latestMs = aureliumConfig.getLong(player.getUniqueId().toString() + ".timestamp", -1);
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("AUData:" + serverName + ":" + player.getUniqueId().toString());

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
                    if (pair.getKey().get(key).equals(pair.getValue().get(key))) continue;
                    replacementMap.put(key, (pair.getKey().get(key) + " -> " + pair.getValue().get(key)));
                }
                if (replacementMap.size() > 2)
                    aureliumHistoryConfig.set(pair.getKey().get("name") + " - " + pair.getKey().get("timestamp"), replacementMap);
            }
            aureliumHistoryConfig.save(aureliumHistoryFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clearTask() {
        if(aureliumFile == null) return;
        saveHistory();
        if (historySaveTask != null && !historySaveTask.isCancelled()) historySaveTask.cancel();
        aureliumFile = null;
        aureliumConfig = null;
        aureliumHistoryFile = null;
        aureliumHistoryConfig = null;
    }

}
