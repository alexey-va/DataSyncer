package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Containers.PlayerMail;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.Zrips.CMI.Modules.Ranks.JobsManager;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.*;
import com.gamingmesh.jobs.economy.PaymentData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class JobsSync extends Sync{

    private final Player player;
    private boolean loaded = false;
    private int loadingCounter = 0;
    private Map<String, String> map;
    private boolean hasEntry = true;
    public static File jobsFile;
    public static FileConfiguration jobsConfig;
    public static File jobsHistoryFile;
    public static FileConfiguration JobsHistoryConfig;
    public static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    public static BukkitTask historySaveTask;


    private int delayCounter = 0;

    public JobsSync(Player player) {
        if (jobsFile == null) {
            jobsFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "jobs" + File.separator + "player_data.yml");
            jobsHistoryFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "jobs" + File.separator + "history.yml");
            try {
                jobsFile.getParentFile().mkdir();
                jobsFile.createNewFile();
                jobsHistoryFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            jobsConfig = YamlConfiguration.loadConfiguration(jobsFile);
            JobsHistoryConfig = YamlConfiguration.loadConfiguration(jobsHistoryFile);
            historySaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (DataSyncer.plugin.writeHistory) saveHistory();
                    try {
                        jobsConfig.save(jobsFile);
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
        DataSyncer.plugin.pool.del("JsData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString());
        DataSyncer.plugin.pool.hset("JsData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        jobsConfig.set(player.getUniqueId().toString(), map);
    }


    private void setData() {
        this.loaded = true;
        Map<String, String> oldData = getValueMap();
        new BukkitRunnable() {
            @Override
            public void run() {
                JobsPlayer data = Jobs.getPlayerManager().getJobsPlayer(player);
                if(data == null) return;
                int skipped = Integer.parseInt(map.get("skipped"));
                data.setSkippedQuests(skipped);
                Set<JobProgression> progressionSet = new HashSet<>();

                data.leaveAllJobs();
                for(Map.Entry<String, String> entry : map.entrySet()){
                    if(entry.getKey().startsWith("job:")){
                        Job job = Jobs.getJob(Integer.parseInt(entry.getKey().replace("job:", "")));
                        if(job == null){
                            continue;
                        }
                        //System.out.println("Joining job: "+job.getName());
                        String[] strings = entry.getValue().split(";");
                        data.joinJob(job);

                        JobProgression progression = data.getJobProgression(job);
                        progression.setLevel(Integer.parseInt(strings[0]));
                        progression.setExperience(Double.parseDouble(strings[1]));
                        progression.setLastExperience(Double.parseDouble(strings[2]));
                        progression.setLeftOn(Long.valueOf(strings[3]));
                    }
                    else if(entry.getKey().startsWith("archived_job:")){
                        Job job = Jobs.getJob(Integer.parseInt(entry.getKey().replace("archived_job:", "")));
                        if(job == null){
                            continue;
                        }
                        String[] strings = entry.getValue().split(";");

                        JobProgression progression = new JobProgression(job, data, Integer.parseInt(strings[0]), Double.parseDouble(strings[1]));
                        progression.setLastExperience(Double.parseDouble(strings[2]));
                        progression.setLeftOn(Long.valueOf(strings[3]));
                        progressionSet.add(progression);
                    }
                }
                ArchivedJobs archivedJobs = data.getArchivedJobs();
                archivedJobs.setArchivedJobs(progressionSet);
                data.setArchivedJobs(archivedJobs);
                if(!map.get("quest").equals("null")){
                    System.out.println(map.get("quest"));
                    data.setQuestProgressionFromString(map.get("quest"));
                }

                if(!map.get("pd:payment").equals("null")){
                    PaymentData p = new PaymentData(Long.parseLong(map.get("pd:time")), Double.parseDouble(map.get("pd:payment")),
                            Double.parseDouble(map.get("pd:points")), Double.parseDouble(map.get("pd:exp")),
                            Long.parseLong(map.get("pd:lastAnnounced")), Boolean.parseBoolean(map.get("pd:informed")));
                    data.setPaymentLimit(p);
                }
                //m.out.println("Set Jobs data for "+player.getName());

            }
        }.runTask(DataSyncer.plugin);
        if(oldData == null) oldData = new HashMap<>();
        oldData.put("name", player.getName());
        historyQueue.add(new Pair<>(oldData, map));
    }

    private boolean ifPluginLoaded() {
        return Jobs.getPlayerManager().getJobsPlayer(player) !=null;
    }

    private Map<String, String> getValueMap() {
        JobsPlayer data = Jobs.getPlayerManager().getJobsPlayer(player);
        if (data == null) return null;


        Map<String, String> map = new HashMap<>();

        Integer skipped = data.getSkippedQuests();
        map.put("skipped", skipped+"");
        for(JobProgression progression : data.getJobProgression()){
            if (progression == null || progression.getJob() == null) continue;
            map.put("job:"+progression.getJob().getId(),progression.getLevel()+";"+progression.getExperience()+";"+progression.getLastExperience()+";"+progression.getLeftOn());
        }
        String quest = data.getQuestProgressionString();
        if(quest != null) map.put("quest", quest);
        else map.put("quest", "null");
        for(JobProgression progression : data.getArchivedJobs().getArchivedJobs()){
            if (progression == null || progression.getJob() == null) continue;
            map.put("archived_job:"+progression.getJob().getId(),progression.getLevel()+";"+progression.getExperience()+";"+progression.getLastExperience()+";"+progression.getLeftOn());
        }

        PaymentData paymentData = data.getPaymentLimit();
        if(paymentData == null) map.put("pd:payment", "null");
        else {
            Double payment = paymentData.getAmount(CurrencyType.MONEY);
            Double exp = paymentData.getAmount(CurrencyType.EXP);
            Double points = paymentData.getAmount(CurrencyType.POINTS);
            Long time = paymentData.getTime(CurrencyType.MONEY);
            Long lastAnnounced = paymentData.getLastAnnounced();
            boolean informed = paymentData.isInformed();

            //PaymentData p = new PaymentData(time, payment, points, exp, lastAnnounced, informed);
            map.put("pd:payment", payment + "");
            map.put("pd:exp", exp + "");
            map.put("pd:points", points + "");
            map.put("pd:time", time + "");
            map.put("pd:lastAnnounced", lastAnnounced + "");
            map.put("pd:informed", informed + "");
        }

        if(data.getDisplayHonorific() != null) map.put("honorific", data.getDisplayHonorific());

        long time2 = System.currentTimeMillis();
        map.put("timestamp", time2 + "");
        return map;
    }

    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        //m.out.println("get latest!");
        long latestMs = jobsConfig.getLong(player.getUniqueId().toString() + ".timestamp", -1);
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("JsData:" + serverName + ":" + player.getUniqueId().toString());
            //System.out.println(serverName+" | "+map);
            if (map.size() == 0) continue;
            long ms = Long.parseLong(map.get("timestamp"));
            if (ms > latestMs) {
                latestMap = map;
                latestMs = ms;
                //System.out.println(serverName);
            }
        }

        //System.out.println(latestMs);

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
                    JobsHistoryConfig.set(pair.getKey().get("name") + " - " + pair.getKey().get("timestamp"), replacementMap);
            }
            JobsHistoryConfig.save(jobsHistoryFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clearTask() {
        if(jobsFile == null) return;
        saveHistory();
        if (historySaveTask != null && !historySaveTask.isCancelled()) historySaveTask.cancel();
        jobsFile = null;
        jobsConfig = null;
        jobsHistoryFile = null;
        JobsHistoryConfig = null;
    }

}
