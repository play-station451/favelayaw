package net.favela.yaw.impl.management.managers;

import com.google.gson.*;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.favela.yaw.EntryPoint;
import net.favela.yaw.impl.management.Manager;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.Setting;
import net.favela.yaw.impl.util.log.Log;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ConfigManager {

    public static final String DEFAULT_CONFIG = "default";
    private final Path configDir;

    private final Path activeConfigFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    @Setter
    @Getter
    private String currentConfig = DEFAULT_CONFIG;

    public ConfigManager() {
        Path base = Minecraft.getInstance().gameDirectory.toPath().resolve("R3tard");
        Path dir = base.resolve("configs");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Log.error("Failed to create config directory", e);
        }
        this.configDir = dir;
        this.activeConfigFile = base.resolve("active_config.txt");
    }

    public void registerLifecycle() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (currentConfig != null) {
                save();
                writeActiveConfig(currentConfig);
                Log.info("{} config '{}' saved on shutdown", EntryPoint.name(), currentConfig);
            }
        });
    }

    private Path fileFor(String name) {
        return configDir.resolve(name + ".json");
    }

    public boolean exists(String name) {
        return Files.exists(fileFor(name));
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(configDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String fileName = p.getFileName().toString();
                names.add(fileName.substring(0, fileName.length() - 5));
            });
        } catch (IOException e) {
            Log.error("Failed to list configs", e);
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public boolean create(String name) {
        if (exists(name)) return false;
        currentConfig = name;
        save(name);
        writeActiveConfig(name);
        return true;
    }

    public boolean delete(String name) {
        try {
            return Files.deleteIfExists(fileFor(name));
        } catch (IOException e) {
            Log.error("Failed to delete config {}", name, e);
            return false;
        }
    }

    public void save() {
        save(currentConfig);
    }

    public void save(String name) {
        JsonObject root = new JsonObject();
        for (Module module : Manager.MODULE.getModules()) {
            JsonObject moduleObj = new JsonObject();
            moduleObj.addProperty("enabled", module.isEnabled());
            JsonObject settingsObj = new JsonObject();
            for (Setting setting : module.getSettings()) {
                try {
                    settingsObj.add(setting.getName(), setting.toJson());
                } catch (Exception e) {
                    Log.error("Failed to save setting {} of module {}", setting.getName(), module.getName(), e);
                }
            }
            moduleObj.add("settings", settingsObj);
            root.add(module.getName(), moduleObj);
        }
        try (Writer writer = Files.newBufferedWriter(fileFor(name))) {
            gson.toJson(root, writer);
            Log.info("Config saved to {}", fileFor(name));
        } catch (IOException e) {
            Log.error("Failed to save config", e);
        }
    }

    public void load() {
        String active = readActiveConfig();
        if (active != null && exists(active)) {
            currentConfig = active;
        }
        if (!exists(DEFAULT_CONFIG)) {
            save(DEFAULT_CONFIG);
            Log.info("Created default config");
        }
        load(currentConfig);
    }

    public boolean load(String name) {
        Path configFile = fileFor(name);
        if (!Files.exists(configFile)) {
            Log.info("No config file found for {}, using defaults", name);
            return false;
        }
        try (Reader reader = Files.newBufferedReader(configFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Module module : Manager.MODULE.getModules()) {
                if (!root.has(module.getName())) continue;
                JsonObject moduleObj = root.getAsJsonObject(module.getName());
                if (moduleObj.has("enabled")) {
                    boolean enabled = moduleObj.get("enabled").getAsBoolean();
                    if (enabled != module.isEnabled()) module.toggle();
                }
                if (moduleObj.has("settings")) {
                    JsonObject settingsObj = moduleObj.getAsJsonObject("settings");
                    for (Setting setting : module.getSettings()) {
                        if (settingsObj.has(setting.getName())) {
                            try {
                                setting.fromJson(settingsObj.get(setting.getName()));
                            } catch (Exception e) {
                                Log.error("Failed to load setting {} of module {}", setting.getName(), module.getName(), e);
                            }
                        }
                    }
                }
            }
            currentConfig = name;
            writeActiveConfig(name);
            Log.info("Config loaded from {}", configFile);
            return true;
        } catch (Exception e) {
            Log.error("Failed to load config", e);
            return false;
        }
    }

    private String readActiveConfig() {
        try {
            if (Files.exists(activeConfigFile)) {
                String name = Files.readString(activeConfigFile).trim();
                return name.isEmpty() ? null : name;
            }
        } catch (IOException e) {
            Log.error("Failed to read active config marker", e);
        }
        return null;
    }

    private void writeActiveConfig(String name) {
        try {
            Files.writeString(activeConfigFile, name);
        } catch (IOException e) {
            Log.error("Failed to write active config marker", e);
        }
    }
}