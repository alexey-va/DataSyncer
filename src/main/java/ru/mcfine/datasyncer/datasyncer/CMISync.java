package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Containers.PlayerMail;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CMISync extends Sync{

    private final Player player;
    private boolean loaded = false;
    private int loadingCounter = 0;
    private Map<String, String> map;
    private boolean hasEntry = true;
    public static File cmiFile;
    public static FileConfiguration cmiConfig;
    public static File cmiHistoryFile;
    public static FileConfiguration cmiHistoryConfig;
    public static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    public static BukkitTask historySaveTask;
    private int delayCounter = 0;

    public CMISync(Player player) {
        if (cmiFile == null) {
            cmiFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "cmi" + File.separator + "player_data.yml");
            cmiHistoryFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "cmi" + File.separator + "history.yml");
            try {
                cmiFile.getParentFile().mkdir();
                cmiFile.createNewFile();
                cmiHistoryFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            cmiConfig = YamlConfiguration.loadConfiguration(cmiFile);
            cmiHistoryConfig = YamlConfiguration.loadConfiguration(cmiHistoryFile);
            historySaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (DataSyncer.plugin.writeHistory) saveHistory();
                    try {
                        cmiConfig.save(cmiFile);
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
        DataSyncer.plugin.pool.hset("CMIData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        cmiConfig.set(player.getUniqueId().toString(), map);

    }


    private void setData() {
        this.loaded = true;
        Map<String, String> oldData = getValueMap();
        new BukkitRunnable() {
            @Override
            public void run() {
                CMIUser data = CMI.getInstance().getPlayerManager().getUser(player);
                if(data == null) return;

                data.setVotifierVotes(Integer.parseInt(map.get("votes")));
                data.setAcceptingPM(Boolean.parseBoolean(map.get("pm")));
                if(!map.get("rank").equals("null")) data.setRank(new CMIRank(map.get("rank")));
                data.setNoPayToggled(Boolean.parseBoolean(map.get("no-pay")));
                data.setShiftEditEnabled(Boolean.parseBoolean(map.get("shift")));
                data.setShowTotemBar(Boolean.parseBoolean(map.get("totem")));
                if(!map.get("nick").equals("null")) data.setNickName(map.get("nick"), true);
                data.setTotalPlayTime(Long.parseLong(map.get("time")));
                if(!map.get("skin").equals("null")) data.setSkin(map.get("skin"));
                List<PlayerMail> playerMailList = new ArrayList<>();
                String[] mails = map.get("mail").split("<<<qsq>>>");
                for(String s : mails){
                    if(s == null || s.length() <3) continue;
                    String[] m = s.split("<<<s>>>");
                    if(m.length != 3) continue;
                    PlayerMail mail = new PlayerMail(m[0], Long.parseLong(m[1]), m[2]);
                    playerMailList.add(mail);
                }
                data.setMail(playerMailList);
            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        oldData.put("name", player.getName());
        historyQueue.add(new Pair<>(oldData, map));
    }

    private boolean ifPluginLoaded() {
        return CMI.getInstance().getPlayerManager().getUser(player) != null;
    }

    private Map<String, String> getValueMap() {
        CMIUser data = CMI.getInstance().getPlayerManager().getUser(player);
        if (data == null) return null;

        Map<String, String> map = new HashMap<>();

        // Mail
        StringBuilder builder = new StringBuilder();
        for(PlayerMail playerMail : data.getMails()){
            builder.append(playerMail.getSender());
            builder.append("<<<s>>>");
            builder.append(playerMail.getTime());
            builder.append("<<<s>>>");
            builder.append(playerMail.getMessage());
            builder.append("<<<qsq>>>");
        }
        map.put("mail", builder.toString());

        // Votes
        Integer votes = data.getVotifierVotes();
        map.put("votes", votes + "");

        // Accepting PM
        boolean acceptingPm = data.isAcceptingPM();
        map.put("pm", acceptingPm+"");

        // Rank
        String rank = data.getRank().getName();
        if(rank != null) map.put("rank", rank);
        else map.put("rank", "null");

        // No pay
        boolean noPay = data.isNoPayToggled();
        map.put("no-pay", noPay+"");

        boolean shift = data.isShiftEditEnabled();
        map.put("shift", shift+"");

        // totem
        boolean totem = data.isShowTotemBar();
        map.put("totem", totem+"");

        // nickName
        String nickName = data.getNickName();
        if(nickName != null) map.put("nick", nickName);
        else map.put("nick", "null");

        // time
        long totalTime = data.getTotalPlayTime();
        map.put("time", totalTime+"");

        // skin
        String skin = data.getSkin();
        if(skin != null) map.put("skin", skin);
        else map.put("skin", "null");

        long time = System.currentTimeMillis();
        map.put("timestamp", time + "");
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        long latestMs = cmiConfig.getLong(player.getUniqueId().toString() + ".timestamp", -1);
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("CMIData:" + serverName + ":" + player.getUniqueId().toString());

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
                    cmiHistoryConfig.set(pair.getKey().get("name") + " - " + pair.getKey().get("timestamp"), replacementMap);
            }
            cmiHistoryConfig.save(cmiHistoryFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clearTask() {
        if(cmiFile == null) return;
        saveHistory();
        if (historySaveTask != null && !historySaveTask.isCancelled()) historySaveTask.cancel();
        cmiFile = null;
        cmiConfig = null;
        cmiHistoryFile = null;
        cmiHistoryConfig = null;
    }

}
