package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.LangCheckData;
import me.branduzzo.checkHacks.utils.SignUtil;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LangCheckManager {

    private static final String LANG_KEY      = "key.forward";
    private static final String LANG_FALLBACK = "\u27e6LANG_UNKNOWN\u27e7";
    private static final String BYPASS_PERMISSION = "checkhacks.bypass";

    private final CheckHacksPlugin plugin;
    private final Map<UUID, LangCheckData> activeChecks     = new ConcurrentHashMap<>();
    private final Set<UUID>                firstJoinChecked = ConcurrentHashMap.newKeySet();

    public LangCheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid)   { return activeChecks.containsKey(uuid); }
    public Set<UUID> getFirstJoinChecked() { return firstJoinChecked; }

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        startCheck(target, initiator, languages, false);
    }

    public void startCheck(Player target, Player initiator,
                           Map<String, String> languages, boolean autoCheck) {
        if (!Bukkit.isPrimaryThread()) {
            Map<String, String> languageCopy = new LinkedHashMap<>(languages);
            Bukkit.getScheduler().runTask(plugin, () ->
                    startCheck(target, initiator, languageCopy, autoCheck));
            return;
        }

        if (autoCheck) {
            queueAutoCheck(target, initiator, languages);
            return;
        }

        plugin.getAutoCheckQueueManager().remove(autoQueueKey(target.getUniqueId()));
        startCheckNow(target, initiator, languages, false);
    }

    private boolean startCheckNow(Player target, Player initiator,
                                  Map<String, String> languages, boolean autoCheck) {
        UUID uuid = target.getUniqueId();

        if (hasBypass(target, initiator, autoCheck)) return false;

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return false;
        }

        LangCheckData data = new LangCheckData(uuid,
                initiator != null ? initiator.getUniqueId() : null, languages, autoCheck);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.getMessageManager().get("lang-check-started",
                    Map.of("player", target.getName())));

        Location signLoc = SignUtil.findAirBlock(target);
        if (signLoc == null) {
            activeChecks.remove(uuid);
            return false;
        }

        Block block = signLoc.getBlock();
        BlockState originalState = block.getState();

        Location belowLoc   = signLoc.clone().subtract(0, 1, 0);
        Block    belowBlock = belowLoc.getBlock();
        boolean  placedBarrier = belowBlock.getType().isAir();
        if (placedBarrier) belowBlock.setType(Material.BARRIER, false);

        block.setType(Material.OAK_SIGN, false);
        BlockState freshState = block.getState();
        if (!(freshState instanceof Sign sign)) {
            originalState.update(true, false);
            if (placedBarrier) belowBlock.setType(Material.AIR, false);
            activeChecks.remove(uuid);
            return false;
        }

        sign.getSide(Side.FRONT).line(0, Component.translatable(LANG_KEY, LANG_FALLBACK));
        sign.update(true, false);

        data.setSignLocation(signLoc);
        data.setOriginalState(originalState);
        data.setBarrierPlaced(placedBarrier);
        data.setBarrierLocation(belowLoc);

        SignUtil.setAllowedEditor(signLoc, uuid, plugin);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!activeChecks.containsKey(uuid)) return;
            SignUtil.sendBlockEntityPacket(target, signLoc, plugin);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!activeChecks.containsKey(uuid)) return;
                SignUtil.sendOpenSignPacket(target, signLoc, plugin);
                target.sendBlockChange(signLoc, Material.AIR.createBlockData());
            }, 1L);
        });

        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!activeChecks.containsKey(uuid)) return;
            restoreSign(data);
            activeChecks.remove(uuid);
            Component msg = plugin.getMessageManager().get("lang-check-timeout",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(data, msg);
            if (data.isAutoCheck()) plugin.getAutoCheckQueueManager().releaseSlot(data.getTargetUUID());
        }, plugin.getConfigManager().getLangTimeoutTicks());

        data.setTimeoutTask(timeout);
        return true;
    }

    private void queueAutoCheck(Player target, Player initiator, Map<String, String> languages) {
        UUID uuid = target.getUniqueId();
        String queueKey = autoQueueKey(uuid);

        if (hasBypass(target, initiator, true)) return;
        if (activeChecks.containsKey(uuid) || plugin.getAutoCheckQueueManager().isQueued(queueKey)) return;
        if (languages.isEmpty()) return;

        UUID initiatorUUID = initiator != null ? initiator.getUniqueId() : null;
        Map<String, String> languageCopy = new LinkedHashMap<>(languages);
        String targetName = target.getName();

        plugin.getAutoCheckQueueManager().enqueue(queueKey, uuid, "language check for " + targetName, () -> {
            Player queuedTarget = Bukkit.getPlayer(uuid);
            if (queuedTarget == null || !queuedTarget.isOnline()) return false;
            Player queuedInitiator = initiatorUUID != null ? Bukkit.getPlayer(initiatorUUID) : null;
            return startCheckNow(queuedTarget, queuedInitiator, languageCopy, true);
        });
    }

    private String autoQueueKey(UUID uuid) {
        return "lang:" + uuid;
    }

    private boolean hasBypass(Player target, Player initiator, boolean autoCheck) {
        if (!target.hasPermission(BYPASS_PERMISSION)) return false;
        if (!autoCheck && initiator != null) {
            initiator.sendMessage(plugin.getMessageManager().get("bypass-skip",
                    Map.of("player", target.getName())));
        }
        return true;
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;

        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data);

        String response = lines.length > 0 ? lines[0].strip() : "";
        if (plugin.getConfigManager().isDebugLoggingEnabled()) {
            plugin.getLogger().info("[CheckHacks] LangCheck response from "
                    + target.getName() + ": '" + response + "'");
        }

        if (response.isEmpty() || response.equals(LANG_FALLBACK)) {
            Component msg = plugin.getMessageManager().get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(data, msg);
            plugin.getDatabaseManager().saveScan("lang", target.getName(),
                    target.getUniqueId().toString(),
                    data.getInitiatorUUID() != null
                            ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                            .map(Player::getName).orElse("AutoCheck")
                            : "AutoCheck",
                    "Lang check", false);
            if (data.isAutoCheck()) plugin.getAutoCheckQueueManager().releaseSlot(data.getTargetUUID());
            return;
        }

        String detected = null;
        for (Map.Entry<String, String> entry : data.getLanguages().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(response)) { detected = entry.getKey(); break; }
        }

        String display = detected != null ? detected : "Unknown";
        String msgKey  = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Component msg  = plugin.getMessageManager().get(msgKey,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.getMessageManager().broadcastAlerts(msg);
        notifyInitiator(data, msg);

        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : "AutoCheck";

        long scanId = plugin.getDatabaseManager().saveScan("lang", target.getName(),
                target.getUniqueId().toString(), checkerName, "Lang check", false);
        plugin.getDatabaseManager().saveLangResult(scanId, display, response);

        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isLangDiscordEnabled()) {
            String description = cfg.getLangDiscordMessage()
                    .replace("&name&",    target.getName())
                    .replace("&checker&", checkerName)
                    .replace("&lang&",    detected != null ? detected : "Unknown (" + response + ")");
            WebhookUtil.sendRaw(plugin, cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(), description);
        }
        if (data.isAutoCheck()) plugin.getAutoCheckQueueManager().releaseSlot(data.getTargetUUID());
    }

    private void notifyInitiator(LangCheckData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean gets = ini.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!gets) ini.sendMessage(msg);
    }

    private void restoreSign(LangCheckData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Runnable restore = () -> {
            try { if (data.getOriginalState() != null) data.getOriginalState().update(true, false); }
            catch (Exception e) { plugin.getLogger().warning("[CheckHacks] LangRestore: " + e.getMessage()); }
            if (data.isBarrierPlaced() && data.getBarrierLocation() != null) {
                try { data.getBarrierLocation().getBlock().setType(Material.AIR, false); }
                catch (Exception e) { plugin.getLogger().warning("[CheckHacks] LangBarrier: " + e.getMessage()); }
            }
        };
        if (Bukkit.isPrimaryThread()) restore.run();
        else Bukkit.getScheduler().runTask(plugin, restore);
        data.setSignLocation(null);
    }

    public void cleanup() {
        for (LangCheckData d : activeChecks.values()) {
            if (d.getTimeoutTask() != null) d.getTimeoutTask().cancel();
            restoreSign(d);
        }
        activeChecks.clear();
    }
}
