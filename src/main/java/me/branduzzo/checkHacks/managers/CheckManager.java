package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.*;
import me.branduzzo.checkHacks.utils.SignUtil;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

public class CheckManager {

    private static final String CTRL_KEYBIND  = "key.forward";
    private static final int    LINES_PER_SIGN = 3;
    private static final String BYPASS_PERMISSION = "checkhacks.bypass";

    private final CheckHacksPlugin plugin;
    private final Map<UUID, CheckPlayerData> activeChecks  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>            lastAutoCheck = new ConcurrentHashMap<>();

    public CheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) { return activeChecks.containsKey(uuid); }

    public boolean isAutoCheckQueued(UUID uuid) {
        return plugin.getAutoCheckQueueManager().isQueued(autoQueueKey(uuid));
    }

    public boolean canAutoCheck(UUID uuid) {
        long cooldownMs = plugin.getConfigManager().getFlagCooldownHours() * 3_600_000L;
        return System.currentTimeMillis() - lastAutoCheck.getOrDefault(uuid, 0L) >= cooldownMs;
    }

    public void startCheck(Player target, Player initiator,
                           List<HackDefinition> hacks, boolean autoCheck, String reason) {
        if (!Bukkit.isPrimaryThread()) {
            List<HackDefinition> hackCopy = List.copyOf(hacks);
            Bukkit.getScheduler().runTask(plugin, () ->
                    startCheck(target, initiator, hackCopy, autoCheck, reason));
            return;
        }

        if (autoCheck) {
            queueAutoCheck(target, initiator, hacks, reason);
            return;
        }

        plugin.getAutoCheckQueueManager().remove(autoQueueKey(target.getUniqueId()));
        startCheckNow(target, initiator, hacks, false, reason);
    }

    private boolean startCheckNow(Player target, Player initiator,
                                  List<HackDefinition> hacks, boolean autoCheck, String reason) {
        UUID uuid = target.getUniqueId();

        if (hasBypass(target, initiator, autoCheck)) return false;

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return false;
        }

        if (plugin.getConfigManager().isBedrockEnabled()) {
            for (String prefix : plugin.getConfigManager().getBedrockPrefixes()) {
                if (target.getName().startsWith(prefix)) {
                    Component msg = plugin.getMessageManager().get("bedrock-skip",
                            Map.of("player", target.getName()));
                    if (initiator != null) initiator.sendMessage(msg);
                    else plugin.getMessageManager().broadcastAlerts(msg);
                    return false;
                }
            }
        }

        if (autoCheck) lastAutoCheck.put(uuid, System.currentTimeMillis());

        List<List<HackDefinition>> batches = buildBatches(hacks);
        if (batches.isEmpty()) return false;

        CheckPlayerData data = new CheckPlayerData(uuid,
                initiator != null ? initiator.getUniqueId() : null,
                batches, autoCheck, reason);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.getMessageManager().get("check-started",
                    Map.of("player", target.getName())));

        processBatch(target, data);
        return true;
    }

    private void queueAutoCheck(Player target, Player initiator,
                                List<HackDefinition> hacks, String reason) {
        UUID uuid = target.getUniqueId();
        String queueKey = autoQueueKey(uuid);

        if (hasBypass(target, initiator, true)) return;
        if (activeChecks.containsKey(uuid) || plugin.getAutoCheckQueueManager().isQueued(queueKey)) return;
        if (hacks.isEmpty()) return;

        if (plugin.getConfigManager().isBedrockEnabled()) {
            for (String prefix : plugin.getConfigManager().getBedrockPrefixes()) {
                if (target.getName().startsWith(prefix)) {
                    Component msg = plugin.getMessageManager().get("bedrock-skip",
                            Map.of("player", target.getName()));
                    if (initiator != null) initiator.sendMessage(msg);
                    else plugin.getMessageManager().broadcastAlerts(msg);
                    return;
                }
            }
        }

        lastAutoCheck.put(uuid, System.currentTimeMillis());

        UUID initiatorUUID = initiator != null ? initiator.getUniqueId() : null;
        List<HackDefinition> hackCopy = List.copyOf(hacks);
        String targetName = target.getName();

        plugin.getAutoCheckQueueManager().enqueue(queueKey, uuid, "hack check for " + targetName, () -> {
            Player queuedTarget = Bukkit.getPlayer(uuid);
            if (queuedTarget == null || !queuedTarget.isOnline()) return false;
            Player queuedInitiator = initiatorUUID != null ? Bukkit.getPlayer(initiatorUUID) : null;
            return startCheckNow(queuedTarget, queuedInitiator, hackCopy, true, reason);
        });
    }

    private String autoQueueKey(UUID uuid) {
        return "hack:" + uuid;
    }

    private boolean hasBypass(Player target, Player initiator, boolean autoCheck) {
        if (!target.hasPermission(BYPASS_PERMISSION)) return false;
        if (!autoCheck && initiator != null) {
            initiator.sendMessage(plugin.getMessageManager().get("bypass-skip",
                    Map.of("player", target.getName())));
        }
        return true;
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += LINES_PER_SIGN)
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + LINES_PER_SIGN, hacks.size()))));
        return batches;
    }

    private void processBatch(Player target, CheckPlayerData data) {
        UUID uuid = target.getUniqueId();
        List<HackDefinition> batch = data.getCurrentBatchHacks();

        Location signLoc = SignUtil.findAirBlock(target);
        if (signLoc == null) {
            finishCheck(uuid);
            return;
        }

        Block block = signLoc.getBlock();
        BlockState originalState = block.getState();

        Location belowLoc    = signLoc.clone().subtract(0, 1, 0);
        Block    belowBlock  = belowLoc.getBlock();
        boolean  placedBarrier = belowBlock.getType().isAir();
        if (placedBarrier) belowBlock.setType(Material.BARRIER, false);

        block.setType(Material.OAK_SIGN, false);
        BlockState freshState = block.getState();
        if (!(freshState instanceof Sign sign)) {
            originalState.update(true, false);
            if (placedBarrier) belowBlock.setType(Material.AIR, false);
            finishCheck(uuid);
            return;
        }

        var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < LINES_PER_SIGN; i++)
            front.line(i, i < batch.size() ? buildComponent(batch.get(i)) : Component.empty());
        front.line(3, Component.keybind(CTRL_KEYBIND));
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
            CheckPlayerData d = activeChecks.get(uuid);
            if (d == null) return;
            restoreCurrentSign(d);
            for (HackDefinition h : batch)
                d.getResults().put(h.getId(), HackResult.PROTECTED);
            d.incrementBatch();
            scheduleNextOrFinish(uuid);
        }, plugin.getConfigManager().getTimeoutTicks());

        data.setSignTimeoutTask(timeout);
    }

    private Component buildComponent(HackDefinition hack) {
        return switch (hack.getMode()) {
            case METEOR, TRANSLATE -> Component.translatable(hack.getKey(), hack.getFallback());
            case KEYBIND           -> Component.keybind(hack.getKey());
        };
    }

    public void handleBatchResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;

        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        restoreCurrentSign(data);

        List<HackDefinition> batch = data.getCurrentBatchHacks();
        String ctrlResp = lines.length > 3 ? lines[3].strip() : "";

        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);

        if (plugin.getConfigManager().isDebugLoggingEnabled()) {
            plugin.getLogger().info("[CheckHacks] Batch " + data.getCurrentBatch()
                    + " from " + target.getName()
                    + " L0='" + (lines.length > 0 ? lines[0] : "")
                    + "' L1='" + (lines.length > 1 ? lines[1] : "")
                    + "' L2='" + (lines.length > 2 ? lines[2] : "")
                    + "' CTRL='" + ctrlResp + "'"
                    + (exploitPreventer ? " [ExploitPreventer DETECTED]" : ""));
        }

        if (exploitPreventer) {
            Component epMsg = plugin.getMessageManager().get("exploitpreventer-detected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(epMsg);
            notifyInitiator(data, epMsg);
        }

        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String resp = i < lines.length ? lines[i].strip() : "";
            HackResult result = evaluateResponse(hack, resp, exploitPreventer);
            data.getResults().put(hack.getId(), result);
            if (plugin.getConfigManager().isDebugLoggingEnabled()) {
                plugin.getLogger().info("[CheckHacks] " + hack.getDisplayName()
                        + " -> " + result + " (resp='" + resp + "')");
            }
        }

        data.incrementBatch();
        scheduleNextOrFinish(uuid);
    }

    private void scheduleNextOrFinish(UUID uuid) {
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;
        if (data.hasMoreBatches()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player t = Bukkit.getPlayer(uuid);
                if (t != null && t.isOnline()) processBatch(t, data);
                else finishCheck(uuid);
            }, plugin.getConfigManager().getBetweenSignTicks());
        } else {
            finishCheck(uuid);
        }
    }

    private HackResult evaluateResponse(HackDefinition hack, String resp, boolean exploitPreventer) {
        if (resp.isEmpty()) return HackResult.NOT_DETECTED;

        return switch (hack.getMode()) {
            case METEOR -> {
                if (resp.equalsIgnoreCase(hack.getKey()))                                    yield HackResult.DETECTED;
                if (resp.toLowerCase().startsWith(hack.getFallback().toLowerCase()))         yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case TRANSLATE -> {
                if (resp.toLowerCase().startsWith(hack.getFallback().toLowerCase()))         yield HackResult.NOT_DETECTED;
                if (resp.equalsIgnoreCase(hack.getKey()))                                    yield HackResult.PROTECTED;
                yield HackResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && resp.equalsIgnoreCase(hack.getKey()))                yield HackResult.PROTECTED;
                if (resp.equalsIgnoreCase(hack.getKey()))                                    yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
        };
    }

    private void finishCheck(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;

        Player targetPlayer = Bukkit.getPlayer(uuid);
        String targetName   = targetPlayer != null ? targetPlayer.getName() : uuid.toString();
        String targetUUID   = uuid.toString();
        String checkerName  = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : (data.isAutoCheck() ? "AutoCheck" : "Console");

        List<HackDefinition> allHacks = data.getBatches().stream().flatMap(List::stream).toList();
        List<String> detectedChecks = new ArrayList<>();
        List<String> protectedChecks = new ArrayList<>();
        boolean anyDetected  = false;
        boolean anyProtected = false;
        boolean allClean     = true;
        StringBuilder resultText = new StringBuilder();

        Component header = plugin.getMessageManager().get("check-complete", Map.of("player", targetName));
        plugin.getMessageManager().broadcastAlerts(header);
        notifyInitiator(data, header);

        for (HackDefinition hack : allHacks) {
            HackResult r = data.getResults().getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED)  {
                anyDetected = true;
                allClean = false;
                detectedChecks.add(hack.getDisplayName());
            }
            if (r == HackResult.PROTECTED) {
                anyProtected = true;
                allClean = false;
                protectedChecks.add(hack.getDisplayName());
            }
            if (r == HackResult.SKIPPED)     allClean = false;
            resultText.append(hack.getDisplayName()).append(": ").append(r.name()).append("\n");

            String color = switch (r) {
                case DETECTED     -> "<red>";
                case NOT_DETECTED -> "<green>";
                case PROTECTED    -> "<yellow>";
                case SKIPPED      -> "<gray>";
            };
            Component line = MiniMessage.miniMessage().deserialize(
                    plugin.getConfigManager().getPrefix()
                            + "  <white>" + hack.getDisplayName() + ": " + color + r.name());
            plugin.getMessageManager().broadcastAlerts(line);
            notifyInitiator(data, line);
        }

        long scanId = plugin.getDatabaseManager().saveScan(
                "hack", targetName, targetUUID, checkerName, data.getReason(), anyDetected);
        for (HackDefinition hack : allHacks) {
            HackResult r = data.getResults().getOrDefault(hack.getId(), HackResult.SKIPPED);
            plugin.getDatabaseManager().saveHackResult(scanId, hack.getId(), hack.getDisplayName(), r.name());
        }

        ConfigManager cfg = plugin.getConfigManager();

        if (cfg.isDiscordEnabled()) {
            String hacksChecked = allHacks.stream()
                    .map(HackDefinition::getDisplayName)
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            WebhookUtil.sendResult(plugin, cfg.getWebhookUrl(), cfg.getEmbedColor(),
                    cfg.getDiscordMessage(), targetName, checkerName,
                    data.getReason(), hacksChecked, resultText.toString().trim());
        }

        final String tn = targetName;
        if (anyDetected && cfg.isCommandIfPositiveEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getPositiveCommand(), tn, detectedChecks);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (anyProtected && !anyDetected && cfg.isCommandIfProtectedEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getProtectedCommand(), tn, protectedChecks);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (allClean && cfg.isCommandIfCleanEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getCleanCommand(), tn,
                    allHacks.stream().map(HackDefinition::getDisplayName).toList());
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (data.isAutoCheck()) plugin.getAutoCheckQueueManager().releaseSlot(data.getTargetUUID());
    }

    private String applyCommandPlaceholders(String command, String playerName, List<String> checks) {
        return command
                .replace("%player%", playerName)
                .replace("%check%", formatCheckList(checks));
    }

    private String formatCheckList(List<String> checks) {
        if (checks == null || checks.isEmpty()) return "none";
        if (checks.size() == 1) return checks.getFirst();
        if (checks.size() == 2) return checks.get(0) + ", and " + checks.get(1);

        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < checks.size() - 1; i++) {
            joiner.add(checks.get(i));
        }
        return joiner + ", and " + checks.getLast();
    }

    private void notifyInitiator(CheckPlayerData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean gets = ini.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!gets) ini.sendMessage(msg);
    }

    private void restoreCurrentSign(CheckPlayerData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Runnable restore = () -> {
            try { if (data.getOriginalState() != null) data.getOriginalState().update(true, false); }
            catch (Exception e) { plugin.getLogger().warning("[CheckHacks] Restore: " + e.getMessage()); }
            if (data.isBarrierPlaced() && data.getBarrierLocation() != null) {
                try { data.getBarrierLocation().getBlock().setType(Material.AIR, false); }
                catch (Exception e) { plugin.getLogger().warning("[CheckHacks] Barrier: " + e.getMessage()); }
            }
        };
        if (Bukkit.isPrimaryThread()) restore.run();
        else Bukkit.getScheduler().runTask(plugin, restore);
        data.setSignLocation(null);
    }

    public void cleanup() {
        for (CheckPlayerData d : activeChecks.values()) {
            if (d.getSignTimeoutTask() != null) d.getSignTimeoutTask().cancel();
            restoreCurrentSign(d);
        }
        activeChecks.clear();
    }
}
