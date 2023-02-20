package ru.mcfine.datasyncer.datasyncer;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;

public abstract class Sync {


    protected Player player;
    protected boolean loaded = false;
    protected int loadingCounter = 0;
    protected Map<String, String> map;
    protected boolean hasEntry = true;
    protected boolean applying = false;
    protected int delayCounter = 10;
    protected String playerName;


    public abstract void uploadData();

    public void run() {
        if (delayCounter > -1) {
            delayCounter++;
            if (delayCounter >= DataSyncer.plugin.delay) {
                delayCounter = -1;
            }
            return;
        }
        if (!ifPluginLoaded()){
            return;
        }
        if (!loaded && !applying) {
            try {
                setData();
            } catch (Exception e){
                e.printStackTrace();
                loaded=true;
                applying=false;
            }
        } else if(!applying) {
            if (!hasEntry && !DataSyncer.plugin.mainServer) return;
            uploadData();
        }
    }

    protected abstract boolean ifPluginLoaded();

    protected abstract void setData();

}
