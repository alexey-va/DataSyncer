package ru.mcfine.datasyncer.datasyncer;

import com.archyx.aureliumskills.api.AureliumAPI;
import com.archyx.aureliumskills.data.PlayerData;
import com.archyx.aureliumskills.modifier.StatModifier;
import com.archyx.aureliumskills.skills.Skill;
import com.archyx.aureliumskills.skills.Skills;
import com.archyx.aureliumskills.stats.Stat;
import com.archyx.aureliumskills.stats.Stats;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AureliumSync extends Sync {

    protected static File historyFile;
    protected static FileConfiguration historyConfig;
    protected static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    protected static BukkitTask historySaveTask;
    //protected static File file = null;
    //protected static FileConfiguration config;

    public AureliumSync(Player player) {

        if (historyFile == null) {
            //file = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "player_data.yml");
            historyFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "history.yml");
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

                    if (DataSyncer.plugin.writeHistory) {

                        try {
                            saveHistory();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }.runTaskTimerAsynchronously(DataSyncer.plugin, 100L, 100L);
        }

        this.player = player;
        this.playerName = player.getName();
        map = getLatestData();
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
                    if (k == null || v == null) continue;
                    replacementMap.put(key, (k + " -> " + v));
                }
                if (replacementMap.size() > 1)
                    historyConfig.set(pair.getKey().get("name") + " - " + new Date(Long.parseLong(pair.getKey().get("timestamp"))), replacementMap);
            }
            if (anyChanges) historyConfig.save(historyFile);
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

    public void uploadData() {
        try {
            map = getValueMap();
            if (map == null || map.size() == 0) return;
            DataSyncer.plugin.pool.del("AUData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString());
            DataSyncer.plugin.pool.hset("AUData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        } catch (Exception ignored) {
        }
        //if(config != null)config.set(player.getUniqueId().toString(), map);
    }

    protected void setData() {
        applying = true;
        Map<String, String> oldData = getValueMap();
        final Map<String, String> finalMap = new HashMap<>(map);
        new BukkitRunnable() {
            @Override
            public void run() {
                PlayerData data = AureliumAPI.getPlugin().getPlayerManager().getPlayerData(player);
                if (data == null) return;

                for (Map.Entry<String, String> entry : finalMap.entrySet()) {
                    if (entry.getValue() == null) {
                        System.out.println("Value for key " + entry.getKey() + " is null. Skipping!");
                        continue;
                    }
                    if (entry.getKey().startsWith("LEVEL:")) {
                        Skill skill = Skills.valueOf(entry.getKey().replace("LEVEL:", ""));
                        data.setSkillLevel(skill, Integer.parseInt(entry.getValue()));
                    } else if (entry.getKey().startsWith("XP:")) {
                        Skill skill = Skills.valueOf(entry.getKey().replace("XP:", ""));
                        data.setSkillXp(skill, Double.parseDouble(entry.getValue()));
                    } else if (entry.getKey().startsWith("STAT:")) {
                        Stat stat = Stats.valueOf(entry.getKey().replace("STAT:", ""));
                        data.setStatLevel(stat, Double.parseDouble(entry.getValue()));
                    } else if (entry.getKey().equals("MODIFIERS")) {
                        data.getStatModifiers().clear();
                        if (entry.getValue().equals("null")) {
                            continue;
                        }
                        String[] strings = entry.getValue().split(";;;");
                        for (String s : strings) {
                            if (s.length() == 0) continue;
                            String[] modifiers = s.split("</>");
                            String key = modifiers[0];
                            String name = modifiers[1];
                            Stat stat = Stats.valueOf(modifiers[2]);
                            double value = Double.parseDouble(modifiers[3]);
                            StatModifier modifier = new StatModifier(name, stat, value);
                            data.getStatModifiers().put(key, modifier);
                        }
                    }
                }

                AureliumAPI.getPlugin().getHealth().reload(player);
                data.setMana(Double.parseDouble(finalMap.get("mana")));
                String abar = finalMap.get("abar");
                if (abar != null) {
                    if (Boolean.parseBoolean(abar))
                        AureliumAPI.getPlugin().getActionBar().getActionBarDisabled().add(player.getUniqueId());
                    else AureliumAPI.getPlugin().getActionBar().getActionBarDisabled().remove(player.getUniqueId());
                }
                data.setShouldSave(true);

                if (map.containsKey("health")) {
                    double health = Double.parseDouble(map.get("health"));
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.setHealth(Math.min(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue(), health));
                        }
                    }.runTaskLater(DataSyncer.plugin, 1L);
                }

                loaded = true;
                applying = false;
            }
        }.runTask(DataSyncer.plugin);
        if (oldData == null) oldData = new HashMap<>();
        historyQueue.add(new Pair<>(oldData, finalMap));
    }

    protected boolean ifPluginLoaded() {
        return AureliumAPI.getPlugin().getPlayerManager().hasPlayerData(player);
    }

    private Map<String, String> getValueMap() {
        PlayerData data = AureliumAPI.getPlugin().getPlayerManager().getPlayerData(player);
        if (data == null) return null;
        Map<String, String> map = new HashMap<>();

        double mana = data.getMana();
        map.put("mana", mana + "");

        var set2 = new HashSet<>(data.getSkillLevelMap().entrySet());
        for (Map.Entry<Skill, Integer> entry : set2) {
            map.put("LEVEL:" + entry.getKey().name(), entry.getValue() + "");
        }

        var set1 = new HashSet<>(data.getSkillXpMap().entrySet());
        for (Map.Entry<Skill, Double> entry : set1) {
            map.put("XP:" + entry.getKey().name(), entry.getValue() + "");
        }

        for (var entry : Stats.values()) {
            try {
                double level = data.getStatLevel(entry);
                map.put("STAT:" + entry.name(), level + "");
            } catch (Exception ex) {
                ex.printStackTrace();
                System.out.println("Error getting stat: " + entry.name());
            }
        }

        Map<String, StatModifier> statModifierMap = data.getStatModifiers();
        StringBuilder stringBuilder = new StringBuilder();

        if (statModifierMap != null && statModifierMap.size() > 0) {
            var set = new HashSet<>(statModifierMap.entrySet());
            for (var entry : set) {
                stringBuilder.append(entry.getKey());
                stringBuilder.append("</>");
                stringBuilder.append(entry.getValue().getName());
                stringBuilder.append("</>");
                stringBuilder.append(entry.getValue().getStat().name());
                stringBuilder.append("</>");
                stringBuilder.append(entry.getValue().getValue());
                stringBuilder.append(";;;");
            }
            map.put("MODIFIERS", stringBuilder.toString());
        } else map.put("MODIFIERS", "null");

        map.put("health", player.getHealth() + "");

        boolean isActionbarDisabled = AureliumAPI.getPlugin().getActionBar().getActionBarDisabled().contains(player.getUniqueId());
        map.put("abar", isActionbarDisabled + "");

        long time = System.currentTimeMillis();
        map.put("timestamp", time + "");
        map.put("name", playerName);
        map.entrySet().forEach(entry -> {
            if (entry.getValue() == null) entry.setValue("null");
        });
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        long latestMs = -1;
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

}
