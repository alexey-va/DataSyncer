package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.xml.crypto.Data;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Double.parseDouble;
import static java.util.regex.Pattern.compile;

public class BalanceSync{

    public static Map<String, Set<TransactionInfo>> transMap = new ConcurrentHashMap<>();
    BukkitTask readTask;
    BukkitTask syncTask;
    boolean readingData = false;
    boolean readingData2 = false;

    public BalanceSync() {
        readTask = new BukkitRunnable() {
            @Override
            public void run() {
                if(readingData2) return;
                readingData = true;
                readChangeMessage();
                for(var set : transMap.entrySet()){
                    set.getValue().forEach(t -> t.counter--);
                    set.getValue().removeIf(t -> t.counter<0);
                }
                transMap.values().removeIf(s -> s.size() == 0);

                if(DataSyncer.plugin.mainServer) {
                    publishPlayerList();
                }
                readingData = false;
            }
        }.runTaskTimer(DataSyncer.plugin, DataSyncer.plugin.interval, DataSyncer.plugin.interval);

        if(!DataSyncer.plugin.mainServer) {
            syncTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (readingData) return;
                    readingData2 = true;
                    readPlayerList();
                    readingData2 = false;
                }
            }.runTaskTimerAsynchronously(DataSyncer.plugin, 113L, 113L);
        }
    }

    public void publishPlayerList(){
        DataSyncer.plugin.pool.del("DataSync:playersBalance");
        String[] strings = Bukkit.getOnlinePlayers().stream().map(p -> {
            try {
                String s1 = p.getUniqueId().toString();
                String s2 = CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().getBalance(p) + "";
                return s1 + ";;;" + s2;
            } catch (Exception ig){
                ig.printStackTrace();
            }
            return "";
        }).distinct().toArray(String[]::new);
        if(strings.length > 0)DataSyncer.plugin.pool.sadd("DataSync:playersBalance", strings);
    }

    public void readPlayerList(){
            Set<String> players = DataSyncer.plugin.pool.smembers("DataSync:playersBalance");
            Set<String> uuids = new HashSet<>();
            for(String s : players){
                try {
                    String[] parts = s.split(";;;");
                    String uuid = parts[0];
                    double money = Double.parseDouble(parts[1]);

                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                    double currentBalance = CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().getBalance(offlinePlayer);
                    double diff = money - currentBalance;

                    if(uuids.contains(uuid)){
                        System.out.println(offlinePlayer.getName() + " already has entry!!");
                        continue;
                    }
                    uuids.add(uuid);

                    if (diff > 0.000001) {
                        System.out.println("Setting money for " + offlinePlayer.getName() + " with difference of " + diff);
                        TransactionInfo transactionInfo = new TransactionInfo(currentBalance, money, diff, System.currentTimeMillis(), uuid);
                        if(!transMap.containsKey(uuid)) transMap.put(uuid, new HashSet<>());
                        transMap.get(uuid).add(transactionInfo);
                        CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().depositPlayer(offlinePlayer, diff);
                    } else if (diff < -0.000001) {
                        System.out.println("Setting money for " + offlinePlayer.getName() + " with difference of " + diff);
                        TransactionInfo transactionInfo = new TransactionInfo(currentBalance, money, diff, System.currentTimeMillis(), uuid);
                        if(!transMap.containsKey(uuid)) transMap.put(uuid, new HashSet<>());
                        transMap.get(uuid).add(transactionInfo);
                        CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().withdrawPlayer(offlinePlayer, -diff);
                    }
                } catch (Exception e){
                    System.out.println("error parsing string: "+s);
                    e.printStackTrace();
                }
            }
    }

    public void readChangeMessage(){
        Set<String> indexes = DataSyncer.plugin.pool.smembers("BalanceChange:index");
        for(String index : indexes){
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll(index);
            String status = map.get("server:"+DataSyncer.plugin.name);
            if(status == null || status.equals("true")){
                trimIfDone(index, map);
                continue;
            }
            String uuid = map.get("uuid");
            double from = parseDouble(map.get("from"));
            double to = parseDouble(map.get("to"));
            String type = map.get("type");
            String source = map.get("source");
            double difference = to-from;

            CMIUser cmiUser = CMI.getInstance().getPlayerManager().getUser(UUID.fromString(uuid));
            TransactionInfo transactionInfo = new TransactionInfo(cmiUser.getBalance(), cmiUser.getBalance()+difference, difference, System.currentTimeMillis(), uuid);
            if(!transMap.containsKey(uuid)) transMap.put(uuid, new HashSet<>());
            transMap.get(uuid).add(transactionInfo);
            if(difference > 0){
                CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().depositPlayer(cmiUser.getOfflinePlayer(), difference);
            } else {
                CMI.getInstance().getEconomyManager().getVaultManager().getVaultEconomy().withdrawPlayer(cmiUser.getOfflinePlayer(), -difference);
            }

            if(cmiUser.getPlayer() != null && cmiUser.getPlayer().isOnline()){
                if(source.equals("null")){
                    if(difference > 0) {
                        cmiUser.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                                " \n &7» &eВы получили &a"+formatDbl(difference)+"&f\uD83D\uDCB0 &eв другом мире. \n &7» &7Ваш текущий баланс: &a"+cmiUser.getFormatedBalance()+" \n &e"));
                    } else {
                        cmiUser.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                                " \n &7» &eВы отдали &c"+formatDbl(-difference)+"&f\uD83D\uDCB0 &eв другом мире. \n &7» &7Ваш текущий баланс: &a"+cmiUser.getFormatedBalance()+" \n &e"));
                    }
                } else {
                    if(difference > 0) {
                        cmiUser.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                                " \n &7» &eВы получили &a"+formatDbl(difference)+"&f\uD83D\uDCB0 &eв другом мире от &a"+source+". \n &7» &7Ваш текущий баланс: &a"+cmiUser.getFormatedBalance()+" \n &e"));
                    } else {
                        cmiUser.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                                " \n &7» &eВы отдали &c"+formatDbl(-difference)+"&f\uD83D\uDCB0 &eв другом мире игроку &a"+source+". \n &7» &7Ваш текущий баланс: &a"+cmiUser.getFormatedBalance()+" \n &e"));
                    }
                }

            }

            map.put("server:"+DataSyncer.plugin.name, "true");
            if(!trimIfDone(index, map)) DataSyncer.plugin.pool.hset(index, "server:"+DataSyncer.plugin.name, "true");
        }
    }

    private static final Pattern REGEX = compile("(\\d+(?:\\.\\d+)?)([KMG]?)");
    private static final String[] KMG = new String[] {"", "K", "M", "G"};

    static String formatDbl(double d) {
        int i = 0;
        while (d >= 1000) { i++; d /= 1000; }
        String val = String.format("%,.1f", d);
        if(val.endsWith(".0")) val = val.substring(0, val.length()-2);
        return val + KMG[i];
    }

    private boolean trimIfDone(String index, Map<String, String> map){
        boolean notDone = false;
        for(String s : map.keySet()){
            if(s.startsWith("server:")){
                String value = map.get(s);
                if(value == null || (!value.equals("true") && !value.equals("false"))) continue;
                if(value.equals("false")){
                    notDone = true;
                    break;
                }
            }
        }

        if(!notDone){
            DataSyncer.plugin.pool.del(index);
            DataSyncer.plugin.pool.srem("BalanceChange:index", index);
        }
        return !notDone;
    }

    public void sendChangeMessage(String uuid, double from, double to, String type, String sourceName){

        Map<String, String> map = new HashMap<>();
        map.put("uuid", uuid);
        map.put("from", from+"");
        map.put("to", to+"");
        if(sourceName == null) sourceName = "null";
        map.put("source", sourceName);
        for(String serverName : DataSyncer.plugin.serverNames){
            map.put("server:"+serverName, "false");
        }
        map.put("server:"+DataSyncer.plugin.name, "true");
        if(type == null) type = "null";
        map.put("type", type);
        String key = "BalanceChange:"+uuid+":"+UUID.randomUUID().toString();
        DataSyncer.plugin.pool.hset(key, map);
        DataSyncer.plugin.pool.sadd("BalanceChange:index", key);

    }


    public void clearTask() {
        this.readTask.cancel();
    }

    static class TransactionInfo{
        double from;
        double to;
        double difference;
        long timeStamp;
        String uuid;
        int counter = 100;

        public TransactionInfo(double from, double to, double difference, long timeStamp, String uuid) {
            this.from = from;
            this.to = to;
            this.difference = difference;
            this.timeStamp = timeStamp;
            this.uuid = uuid;
        }
    }

}
