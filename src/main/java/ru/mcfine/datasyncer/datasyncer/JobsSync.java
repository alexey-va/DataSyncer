package ru.mcfine.datasyncer.datasyncer;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Containers.PlayerMail;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.Zrips.CMI.Modules.Ranks.JobsManager;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.*;
import com.gamingmesh.jobs.economy.PaymentData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class JobsSync extends Sync {

    protected static File historyFile;
    protected static FileConfiguration historyConfig;
    protected static ConcurrentLinkedQueue<Pair<Map<String, String>, Map<String, String>>> historyQueue = new ConcurrentLinkedQueue<>();
    protected static BukkitTask historySaveTask;
    //protected static File file = null;
    //protected static FileConfiguration config;

    public JobsSync(Player player) {
        if (historyFile == null) {
            //file = new File(DataSyncer.plugin.getDataFolder() + File.separator + "aurelium_skills" + File.separator + "player_data.yml");
            historyFile = new File(DataSyncer.plugin.getDataFolder() + File.separator + "jobs" + File.separator + "history.yml");
            try {
                //file.getParentFile().mkdir();
                //file.createNewFile();
                historyFile.getParentFile().mkdir();
                historyFile.createNewFile();

                //(new ItemStack(Material.COBBLESTONE_STAIRS)).getItemMeta().getLocalizedName()
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

    public void run() {
        if (delayCounter != -1) {
            delayCounter++;
            if (delayCounter > 20) {
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
        if (map == null || map.size() == 0) return;
        DataSyncer.plugin.pool.del("JsData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString());

        DataSyncer.plugin.pool.hset("JsData:" + DataSyncer.plugin.name + ":" + player.getUniqueId().toString(), map);
        //if(config != null)config.set(player.getUniqueId().toString(), map);
    }



    protected void setData() {
        applying = true;
        Map<String, String> oldData = getValueMap();
        final Map<String, String> finalMap = new HashMap<>(map);
        new BukkitRunnable() {
            @Override
            public void run() {
                JobsPlayer data = Jobs.getPlayerManager().getJobsPlayer(player);
                if (data == null) return;
                int skipped = Integer.parseInt(finalMap.get("skipped"));
                data.setSkippedQuests(skipped);
                Set<JobProgression> progressionSet = new HashSet<>();

                Collection<JobProgression> jobProgressions = new ArrayList<>(data.getJobProgression());
                for(JobProgression jobProgression : jobProgressions){
                    if(!map.containsKey("job:"+jobProgression.getJob().getId())){
                        data.leaveJob(jobProgression.getJob());
                    }
                }

                for (Map.Entry<String, String> entry : finalMap.entrySet()) {
                    if (entry.getKey().startsWith("job:")) {
                        Job job = Jobs.getJob(Integer.parseInt(entry.getKey().replace("job:", "")));
                        if (job == null) {
                            continue;
                        }
                        //System.out.println("Joining job: "+job.getName());
                        String[] strings = entry.getValue().split(";");
                        if(!data.isInJob(job)) data.joinJob(job);

                        JobProgression progression = data.getJobProgression(job);
                        progression.setLevel(Integer.parseInt(strings[0]));
                        progression.setExperience(Double.parseDouble(strings[1]));
                        progression.setLastExperience(Double.parseDouble(strings[2]));
                        progression.setLeftOn(Long.valueOf(strings[3]));
                    } else if (entry.getKey().startsWith("archived_job:")) {
                        Job job = Jobs.getJob(Integer.parseInt(entry.getKey().replace("archived_job:", "")));
                        if (job == null) {
                            continue;
                        }
                        String[] strings = entry.getValue().split(";");

                        JobProgression progression = new JobProgression(job, data, Integer.parseInt(strings[0]), Double.parseDouble(strings[1]));
                        progression.setLastExperience(Double.parseDouble(strings[2]));
                        progression.setLeftOn(Long.valueOf(strings[3]));
                        progressionSet.add(progression);
                    }
                }

                jobProgressions = new ArrayList<>(data.getJobProgression());
                for(JobProgression jobProgression : jobProgressions){
                    if(!map.containsKey("job:"+jobProgression.getJob().getId())){
                        data.leaveJob(jobProgression.getJob());
                    }
                }

                ArchivedJobs archivedJobs = data.getArchivedJobs();
                archivedJobs.setArchivedJobs(progressionSet);
                data.setArchivedJobs(archivedJobs);
                if (!finalMap.get("quest").equals("null")) {
                    data.setQuestProgressionFromString(finalMap.get("quest"));
                }

                if (!finalMap.get("pd:payment").equals("null")) {
                    PaymentData p = new PaymentData(Long.parseLong(finalMap.get("pd:time")), Double.parseDouble(finalMap.get("pd:payment")),
                            Double.parseDouble(finalMap.get("pd:points")), Double.parseDouble(finalMap.get("pd:exp")),
                            Long.parseLong(finalMap.get("pd:lastAnnounced")), Boolean.parseBoolean(finalMap.get("pd:informed")));
                    data.setPaymentLimit(p);
                }
                //m.out.println("Set Jobs data for "+player.getUniqueId().toString());
                loaded = true;
                applying = false;
            }
        }.runTask(DataSyncer.plugin);
        if (oldData == null) oldData = new HashMap<>();
        historyQueue.add(new Pair<>(oldData, finalMap));
    }

    protected boolean ifPluginLoaded() {
        return Jobs.getPlayerManager().getJobsPlayer(player) != null;
    }

    private Map<String, String> getValueMap() {
        JobsPlayer data = Jobs.getPlayerManager().getJobsPlayer(player);
        if (data == null) return null;

        Map<String, String> map = new HashMap<>();

        Integer skipped = data.getSkippedQuests();
        Collection<JobProgression> progressions = new ArrayList<>(data.getJobProgression());
        Collection<JobProgression> progressions2 = new ArrayList<>(data.getArchivedJobs().getArchivedJobs());
        String quest = data.getQuestProgressionString();
        PaymentData paymentData = data.getPaymentLimit();

        if (paymentData == null) map.put("pd:payment", "null");
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
        map.put("skipped", skipped + "");
        for (JobProgression progression : progressions) {
            if (progression.getJob() == null) continue;
            map.put("job:" + progression.getJob().getId(), progression.getLevel() + ";" + progression.getExperience() + ";" + progression.getLastExperience() + ";" + progression.getLeftOn());
        }
        if (quest != null) map.put("quest", quest);
        else map.put("quest", "null");
        for (JobProgression progression : progressions2) {
            if (progression == null || progression.getJob() == null) continue;
            map.put("archived_job:" + progression.getJob().getId(), progression.getLevel() + ";" + progression.getExperience() + ";" + progression.getLastExperience() + ";" + progression.getLeftOn());
        }

        long time2 = System.currentTimeMillis();
        map.put("timestamp", time2 + "");
        map.put("name", playerName);
        map.entrySet().forEach(entry -> {
            if(entry.getValue() == null) entry.setValue("null");
        });
        return map;
    }


    private Map<String, String> getLatestData() {
        Map<String, String> latestMap = null;
        //m.out.println("get latest!");
        long latestMs = -1;
        for (String serverName : DataSyncer.plugin.serverNames) {
            Map<String, String> map = DataSyncer.plugin.pool.hgetAll("JsData:" + serverName + ":" + player.getUniqueId().toString());
            if (map.size() == 0) continue;
            long ms = Long.parseLong(map.get("timestamp"));
            if (ms > latestMs) {
                latestMap = map;
                latestMs = ms;
                //System.out.println(serverName);
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
