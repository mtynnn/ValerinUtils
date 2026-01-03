package me.Mtynnn.valerinUtils.modules.ecoskillsrecount;

import me.Mtynnn.valerinUtils.ValerinUtils;
import me.Mtynnn.valerinUtils.core.Module;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class EcoSkillsRecountModule implements Module, Listener {

    private final ValerinUtils plugin;
    private final File dataFile;
    private FileConfiguration dataConfig;
    private final Set<UUID> recountedPlayers = new HashSet<>();
    private Object cachedLeveler; // AuroraLevels Leveler instance via reflection
    private boolean levelerUnavailable;

    public EcoSkillsRecountModule(ValerinUtils plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "ecoskills_recount_data.yml");
        loadData();
    }

    @Override
    public String getId() {
        return "ecoskillsrecount";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[EcoSkillsRecount] Module enabled");
    }

    @Override
    public void disable() {
        saveData();
    }

    // ========== DATA MANAGEMENT ==========

    private void loadData() {
        recountedPlayers.clear();
        
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("[EcoSkillsRecount] Could not create ecoskills_recount_data.yml: " + e.getMessage());
            }
        }
        
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        for (String uuidStr : dataConfig.getStringList("recounted-players")) {
            try {
                recountedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }
        
        plugin.getLogger().info("[EcoSkillsRecount] Loaded " + recountedPlayers.size() + " recounted players");
    }

    private void saveData() {
        // Recargar antes de escribir para no pisar datos externos
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        var list = recountedPlayers.stream()
                .map(UUID::toString)
                .toList();
        
        dataConfig.set("recounted-players", list);
        
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[EcoSkillsRecount] Could not save ecoskills_recount_data.yml: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (recountedPlayers.contains(uuid)) {
            plugin.getLogger().info("[EcoSkillsRecount] " + player.getName() + " already recounted, skipping");
            return;
        }

        long delayTicks = plugin.getConfig().getLong("modules.ecoskillsrecount.delay-ticks", 20L);
        plugin.getLogger().info("[EcoSkillsRecount] Checking skills for " + player.getName() + " in " + delayTicks + " ticks");
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            
            plugin.getLogger().info("[EcoSkillsRecount] Verifying skills for " + player.getName());
            
            // Calcular XP de Aurora en base a los niveles de cada skill usando tramos configurados
            double totalAuroraXp = getAuroraXpFromSkillLevels(player);
            if (totalAuroraXp > 0D) {
                int auroraLevel = levelFromXp(totalAuroraXp);
                double remainder = totalAuroraXp - xpForLevel(auroraLevel);
                plugin.getLogger().info("[EcoSkillsRecount] " + player.getName() + " SkillLevels->AuroraXP=" + totalAuroraXp + " -> Aurora level " + auroraLevel + " + remainder " + remainder);
                executeRecount(player);
                syncAuroraLevel(player, auroraLevel, totalAuroraXp);
            } else {
                plugin.getLogger().info("[EcoSkillsRecount] " + player.getName() + " has no skills, skipping");
            }
        }, delayTicks);
    }

    // ========== SKILL LEVEL AGGREGATION ==========

    /**
     * Suma los niveles de todas las skills y los convierte a XP de Aurora según tramos:
     *  - L1-10: 5 XP por nivel
     *  - L11-25: 10 XP por nivel
     *  - L26+: 20 XP por nivel
     */
    private double getAuroraXpFromSkillLevels(Player player) {
        double totalAuroraXp = 0D;
        try {
            Class<?> skillsClass = Class.forName("com.willfp.ecoskills.skills.Skills");
            Object skillsInstance = skillsClass.getField("INSTANCE").get(null);
            Object skillsCollection = null;
            for (String methodName : new String[]{"getSkills", "getAllSkills", "values", "getRegisteredSkills"}) {
                try {
                    skillsCollection = skillsInstance.getClass().getMethod(methodName).invoke(skillsInstance);
                    break;
                } catch (Exception ignored) {}
            }
            if (!(skillsCollection instanceof Iterable<?>)) {
                return 0D;
            }
            for (Object skill : (Iterable<?>) skillsCollection) {
                try {
                    var getLevel = findSkillLevelMethod(skill.getClass());
                    if (getLevel == null) {
                        plugin.getLogger().info("[EcoSkillsRecount] No level method found for skill class " + skill.getClass().getName());
                        continue;
                    }
                    int level = invokeSkillLevel(getLevel, skill, player);
                    plugin.getLogger().info("[EcoSkillsRecount] Skill " + skill.getClass().getSimpleName() + " level=" + level + " via method " + getLevel.getName());
                    totalAuroraXp += auroraXpFromSkillLevel(level);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EcoSkillsRecount] Error reading EcoSkills levels: " + e.getClass().getSimpleName());
        }
        return totalAuroraXp;
    }

    private double auroraXpFromSkillLevel(int level) {
        if (level <= 0) {
            return 0D;
        }
        int l1 = Math.min(level, 10);
        int l2 = Math.min(Math.max(level - 10, 0), 15); // niveles 11-25 (15 niveles)
        int l3 = Math.max(level - 25, 0);
        return l1 * 5D + l2 * 10D + l3 * 20D;
    }

    /**
     * Busca un método que devuelva el nivel de la skill para un jugador.
     */
    private java.lang.reflect.Method findSkillLevelMethod(Class<?> skillClass) {
        for (String name : new String[]{"getLevel", "getLevelInt", "getPlayerLevel", "getLevel$core_plugin"}) {
            try {
                return skillClass.getMethod(name, org.bukkit.OfflinePlayer.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                return skillClass.getMethod(name, org.bukkit.entity.Player.class);
            } catch (NoSuchMethodException ignored) {}
        }
        // Fallback: cualquier método que contenga "level" con 1 parámetro Player/OfflinePlayer
        for (var m : skillClass.getMethods()) {
            if (m.getParameterCount() == 1 && m.getName().toLowerCase().contains("level")) {
                Class<?> p = m.getParameterTypes()[0];
                if (p.isAssignableFrom(org.bukkit.OfflinePlayer.class) || p.isAssignableFrom(org.bukkit.entity.Player.class)) {
                    return m;
                }
            }
        }
        return null;
    }

    private int invokeSkillLevel(java.lang.reflect.Method method, Object skill, Player player) throws Exception {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 1 && params[0].isAssignableFrom(org.bukkit.OfflinePlayer.class)) {
            return ((Number) method.invoke(skill, player)).intValue();
        }
        if (params.length == 1 && params[0].isAssignableFrom(Player.class)) {
            return ((Number) method.invoke(skill, player)).intValue();
        }
        return 0;
    }

    // Conversión XP -> nivel para fórmula triangular: xp = 10 * n * (n + 1) / 2
    private int levelFromXp(double xp) {
        if (xp <= 0D) return 0;
        double n = (Math.sqrt(1D + (4D * xp) / 5D) - 1D) / 2D;
        return (int) Math.floor(n);
    }

    // ========== COMMAND EXECUTION (fallback) ==========

    /**
     * Fallback por comandos usando la XP total de EcoSkills.
     */
    private void executeXpCommand(Player player, double totalAuroraXp) {
        if (Bukkit.getPluginManager().getPlugin("AuroraLevels") == null) {
            plugin.getLogger().info("[EcoSkillsRecount] AuroraLevels no está instalado, omitiendo fallback por comandos");
            return;
        }
        long roundedXp = Math.max(0L, Math.round(totalAuroraXp));
        // Orden correcto: /level set <level> <player> [silent]
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "level set 0 " + player.getName() + " true");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "level addxp " + roundedXp + " " + player.getName() + " true");
        plugin.getLogger().info("[EcoSkillsRecount] Fallback commands: addxp totalAuroraXp=" + totalAuroraXp + " (rounded=" + roundedXp + ")");
    }

    // ========== AURORA LEVELS API (reflection) ==========

    /**
     * Sincroniza el nivel del jugador usando AuroraLevels API a partir de la XP total de EcoSkills.
     * Si la API no está disponible, usa el fallback de comandos.
     */
    private void syncAuroraLevel(Player player, int auroraLevel, double totalAuroraXp) {
        Object leveler = getLeveler();
        if (leveler == null) {
            // Fallback a comandos si no hay API
            executeXpCommand(player, totalAuroraXp);
            return;
        }

        try {
            Class<?> levelerClass = leveler.getClass();
            var setLevel = levelerClass.getMethod("setPlayerLevel", Player.class, long.class);
            var addXp = levelerClass.getMethod("addXpToPlayer", Player.class, double.class);

            // Reset y suma de XP total proveniente de EcoSkills
            setLevel.invoke(leveler, player, 0L);
            addXp.invoke(leveler, player, totalAuroraXp);
            plugin.getLogger().info("[EcoSkillsRecount] Synced Aurora XP to level ~" + auroraLevel + " using totalAuroraXp=" + totalAuroraXp + " for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("[EcoSkillsRecount] AuroraLevels sync failed, using command fallback: " + e.getMessage());
            executeXpCommand(player, totalAuroraXp);
        }
    }

    /**
     * XP acumulada para alcanzar un nivel dado con fórmula 10 * level (acumulativa):
     * sum_{i=1..n} 10*i = 10 * n * (n + 1) / 2
     */
    private double xpForLevel(int level) {
        if (level <= 0) {
            return 0D;
        }
        return 10D * level * (level + 1) / 2D;
    }

    /**
     * Obtiene la instancia de Leveler vía reflection y la cachea.
     */
    private Object getLeveler() {
        if (levelerUnavailable) {
            return null;
        }
        if (cachedLeveler != null) {
            return cachedLeveler;
        }
        try {
            Class<?> providerClass = Class.forName("gg.auroramc.auroralevels.api.AuroraLevelsProvider");
            var getLeveler = providerClass.getMethod("getLeveler");
            cachedLeveler = getLeveler.invoke(null);
            if (cachedLeveler == null) {
                levelerUnavailable = true;
                plugin.getLogger().warning("[EcoSkillsRecount] AuroraLevels Leveler is null");
            } else {
                plugin.getLogger().info("[EcoSkillsRecount] AuroraLevels Leveler hooked successfully");
            }
        } catch (ClassNotFoundException e) {
            levelerUnavailable = true;
            plugin.getLogger().warning("[EcoSkillsRecount] AuroraLevels API not found (optional)");
        } catch (Exception e) {
            levelerUnavailable = true;
            plugin.getLogger().warning("[EcoSkillsRecount] Could not hook AuroraLevels: " + e.getMessage());
        }
        return cachedLeveler;
    }

    /**
     * Ejecuta /ecoskills recount para el jugador
     */
    private void executeRecount(Player player) {
        String command = "ecoskills recount " + player.getName();
        plugin.getLogger().info("[EcoSkillsRecount] Executing recount for " + player.getName());
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                recountedPlayers.add(player.getUniqueId());
                saveData();
                plugin.getLogger().info("[EcoSkillsRecount] Successfully recounted " + player.getName());
            } catch (Exception e) {
                plugin.getLogger().warning("[EcoSkillsRecount] Error executing recount: " + e.getMessage());
            }
        });
    }

    // ========== UTILITY ==========

    public void resetPlayer(UUID uuid) {
        if (recountedPlayers.remove(uuid)) {
            saveData();
        }
    }

    public Set<UUID> getRecountedPlayers() {
        return new HashSet<>(recountedPlayers);
    }
}
