package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Containers.PlayerMail;
import com.Zrips.CMI.Modules.PlayTime.CMIPlayTime;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.Zrips.CMI.Modules.Statistics.StatsManager;
import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CMISync extends Sync{

    protected static File historyFile;
    protected static FileConfiguration historyConfig;
    protected static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    protected static BukkitTask historySaveTask;
    //protected static File file = null;
    //protected static FileConfiguration config;

    public CMISync(Player player) {
        if (historyFile == null) {
            //file = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "player_data.yml");
            historyFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "cmi" + File.separator + "history.yml");
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
        DataSyncer.plugin.pool.hset("CMIData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        //if(config != null)config.set(player.getUniqueId().toString(), map);
    }


    protected void setData() {
        applying = true;
        Map<String, String> oldData = getValueMap();
        final Map<String, String> finalMap = new HashMap<>(map);
        new BukkitRunnable() {
            @Override
            public void run() {
                CMIUser data = CMI.getInstance().getPlayerManager().getUser(player);
                if(data == null) return;

                data.setVotifierVotes(Integer.parseInt(finalMap.get("votes")));
                data.setAcceptingPM(Boolean.parseBoolean(finalMap.get("pm")));
                if(!finalMap.get("rank").equals("null")){
                    CMIRank cmiRank = CMI.getInstance().getRankManager().getRank(finalMap.get("rank"));
                    if(cmiRank == null){
                        System.out.println("No such rank: "+finalMap.get("rank"));
                        for(CMIRank cmiRank1 : CMI.getInstance().getRankManager().getRanks().values()){
                            System.out.println("Rank: "+cmiRank1.getName());
                        }
                    } else data.setRank(cmiRank);
                }


                if(finalMap.containsKey("prefix") && !finalMap.get("prefix").equals("null")) data.setNamePlatePrefix(finalMap.get("prefix"));
                if(finalMap.containsKey("suffix") && !finalMap.get("suffix").equals("null")) data.setNamePlateSuffix(finalMap.get("suffix"));
                if(finalMap.containsKey("god")) data.setGod(Boolean.parseBoolean(finalMap.get("god")));
                if(finalMap.containsKey("glow") && !finalMap.get("glow").equals("null")){
                    char c = finalMap.get("glow").charAt(0);
                    ChatColor chatColor = ChatColor.getByChar(c);
                    if(chatColor != null) data.setGlow(chatColor, false);
                }
                data.setNoPayToggled(!Boolean.parseBoolean(finalMap.get("no-pay")));
                data.setShiftEditEnabled(Boolean.parseBoolean(finalMap.get("shift")));
                data.setShowTotemBar(Boolean.parseBoolean(finalMap.get("totem")));
                if(!finalMap.get("nick").equals("null")) data.setNickName(finalMap.get("nick"), true);
                else data.setNickName(null, true);
                if(!finalMap.get("skin").equals("null")) data.setSkin(finalMap.get("skin"));
                else data.setSkin(null);
                List<PlayerMail> playerMailList = new ArrayList<>();
                String[] mails = finalMap.get("mail").split("<<<qsq>>>");
                for(String s : mails){
                    if(s == null || s.length() <3) continue;
                    String[] m = s.split("<<<s>>>");
                    if(m.length != 3) continue;
                    PlayerMail mail = new PlayerMail(m[0], Long.parseLong(m[1]), m[2]);
                    playerMailList.add(mail);
                }
                data.setMail(playerMailList);
                loaded = true;
                applying = false;
            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        historyQueue.add(new Pair<>(oldData, finalMap));
    }

    protected boolean ifPluginLoaded() {
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

        map.put("god", data.isGod()+"");

        if(data.getGlow() != null) map.put("glow", data.getGlow().getChar()+"");
        else map.put("glow", "null");
        if(data.getNamePlatePrefix() != null) map.put("prefix", data.getNamePlatePrefix());
        else map.put("prefix", "null");
        if(data.getNamePlateSuffix() != null) map.put("suffix", data.getNamePlateSuffix());
        else map.put("suffix", "null");

        // No pay
        boolean noPay = data.isNoPayToggled();
        map.put("no-pay", noPay+"");

        boolean shift = data.isShiftEditEnabled();
        map.put("shift", shift+"");

        // totem
        boolean totem = data.isShowTotemBar();
        map.put("totem", totem+"");

        // balance
        double balance = data.getBalance();
        map.put("balance", balance+"");
        // nickName
        String nickName = data.getNickName();
        if(nickName != null) map.put("nick", nickName);
        else map.put("nick", "null");
        // skin
        String skin = data.getSkin();
        if(skin != null) map.put("skin", skin);
        else map.put("skin", "null");

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
