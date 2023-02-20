package ru.mcfine.datasyncer.datasyncer;

import io.github.thebusybiscuit.slimefun4.api.researches.Research;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SlimefunSync {

    public static Map<String, Set<ResearchInfo>> researchMap = new ConcurrentHashMap<>();
    BukkitTask readTask;

    public SlimefunSync() {
        readTask = new BukkitRunnable() {
            @Override
            public void run() {
                readChangeMessage();
                for (var set : researchMap.entrySet()) {
                    set.getValue().forEach(t -> t.counter--);
                    set.getValue().removeIf(t -> t.counter < 0);
                }
                researchMap.values().removeIf(s -> s.size() == 0);
            }
        }.runTaskTimerAsynchronously(DataSyncer.plugin, DataSyncer.plugin.interval, DataSyncer.plugin.interval);

    }


    public void readChangeMessage() {
        Set<String> indexes = DataSyncer.plugin.pool.smembers("ResearchChange:index");
        for (String index : indexes) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll(index);
            String status = map.get("server:" + DataSyncer.plugin.name);
            if (status == null || status.equals("true")) {
                trimIfDone(index, map);
                continue;
            }
            String uuid = map.get("uuid");
            var profile = Slimefun.getRegistry().getPlayerProfiles().get(UUID.fromString(uuid));
            if(profile == null) continue;

            String namespace = map.get("namespace");
            String key = map.get("key");
            Research research = null;
            for (Research res : Slimefun.getRegistry().getResearches()) {
                if (res.getKey().getKey().equals(key) && res.getKey().getNamespace().equals(namespace)) {
                    research = res;
                    break;
                }
            }
            if (research == null) {
                System.out.println("Research: " + namespace + ":" + key + ": does not exists!!! WTF");
                return;
            }


            Research finalResearch = research;
            new BukkitRunnable() {
                @Override
                public void run() {
                    profile.setResearched(finalResearch, true);
                }
            }.runTask(DataSyncer.plugin);

            ResearchInfo researchInfo = new ResearchInfo(uuid, namespace, key);
            if (!researchMap.containsKey(uuid)) researchMap.put(uuid, new HashSet<>());
            researchMap.get(uuid).add(researchInfo);

            map.put("server:" + DataSyncer.plugin.name, "true");
            if (!trimIfDone(index, map)) DataSyncer.plugin.pool.hset(index, "server:" + DataSyncer.plugin.name, "true");
        }
    }


    private boolean trimIfDone(String index, Map<String, String> map) {
        boolean notDone = false;
        for (String s : map.keySet()) {
            if (s.startsWith("server:")) {
                String value = map.get(s);
                if (value == null || (!value.equals("true") && !value.equals("false"))) continue;
                if (value.equals("false")) {
                    notDone = true;
                    break;
                }
            }
        }

        if (!notDone) {
            DataSyncer.plugin.pool.del(index);
            DataSyncer.plugin.pool.srem("ResearchChange:index", index);
        }
        return !notDone;
    }

    public void sendChangeMessage(String uuid, Research research) {

        Map<String, String> map = new HashMap<>();

        map.put("key", research.getKey().getKey());
        map.put("namespace", research.getKey().getNamespace());
        map.put("uuid", uuid);
        for (String serverName : DataSyncer.plugin.serverNames) {
            map.put("server:" + serverName, "false");
        }
        map.put("server:" + DataSyncer.plugin.name, "true");

        String key = "ResearchChange:" + uuid + ":" + UUID.randomUUID().toString();
        DataSyncer.plugin.pool.hset(key, map);
        DataSyncer.plugin.pool.sadd("ResearchChange:index", key);

    }


    public void clearTask() {
        this.readTask.cancel();
    }

    static class ResearchInfo {
        String namespace;
        String key;
        String uuid;
        int counter = 50;

        public ResearchInfo(String uuid, String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
            this.uuid = uuid;
        }
    }

}
