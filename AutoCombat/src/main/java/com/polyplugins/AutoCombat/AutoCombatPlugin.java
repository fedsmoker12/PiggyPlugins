package com.polyplugins.AutoCombat;


import com.example.EthanApiPlugin.Collections.Inventory;
import com.example.EthanApiPlugin.Collections.NPCs;
import com.example.EthanApiPlugin.Collections.TileItems;
import com.example.EthanApiPlugin.EthanApiPlugin;
import com.example.InteractionApi.InventoryInteraction;
import com.example.InteractionApi.TileObjectInteraction;
import com.example.PacketUtils.WidgetInfoExtended;
import com.example.Packets.*;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.piggyplugins.PiggyUtils.API.InventoryUtil;
import com.piggyplugins.PiggyUtils.API.ObjectUtil;
import com.piggyplugins.PiggyUtils.API.PlayerUtil;
import com.polyplugins.AutoCombat.helper.LootHelper;
import com.polyplugins.AutoCombat.helper.SlayerHelper;
import com.polyplugins.AutoCombat.util.SuppliesUtil;
import com.polyplugins.AutoCombat.util.Util;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

import net.runelite.client.plugins.opponentinfo.OpponentInfoPlugin;

@PluginDescriptor(
        name = "<html><font color=\"#7ecbf2\">[PJ]</font>AutoCombat</html>",
        description = "Kills shit",
        enabledByDefault = false,
        tags = {"poly", "plugin"}
)
@Slf4j
public class AutoCombatPlugin extends Plugin {
    @Inject
    private Client client;
    @Inject
    public AutoCombatConfig config;
    @Inject
    private AutoCombatOverlay overlay;
    @Inject
    private KeyManager keyManager;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    public ItemManager itemManager;
    @Inject
    private ClientThread clientThread;
    public boolean started = false;
    public int timeout = 0;

    @Provides
    private AutoCombatConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(AutoCombatConfig.class);
    }

    @Inject
    public SuppliesUtil supplies;
    @Inject
    public Util util;
    @Inject
    public LootHelper lootHelper;
    @Inject
    public PlayerUtil playerUtil;
    @Inject
    public SlayerHelper slayerHelper;
    public Queue<ItemStack> lootQueue = new LinkedList<>();

    private boolean hasFood = false;
    private boolean hasPrayerPot = false;
    private boolean hasCombatPot = false;
    private boolean hasBones = false;
    public boolean isSlayerNpc = false;
    public SlayerNpc slayerInfo = null;
    public int idleTicks = 0;
    public NPC targetNpc = null;
    public Player player = null;
    private boolean looting = false;

    @Override
    protected void startUp() throws Exception {
        keyManager.registerKeyListener(toggle);
        overlayManager.add(overlay);
        timeout = 0;
    }

    @Override
    protected void shutDown() throws Exception {
        keyManager.unregisterKeyListener(toggle);
        overlayManager.remove(overlay);
        resetEverything();
    }

    public void resetEverything() {
        timeout = 0;
        started = false;
        hasBones = false;
        hasCombatPot = false;
        hasPrayerPot = false;
        hasFood = false;
        idleTicks = 0;
        lootQueue.clear();
        targetNpc = null;
        player = null;
        slayerInfo = null;
        isSlayerNpc = false;
    }

    @Subscribe
    private void onGameTick(GameTick event) {
        player = client.getLocalPlayer();
        isSlayerNpc = slayerHelper.isSlayerNPC(config.targetName());

        if (isSlayerNpc) {
            slayerInfo = slayerHelper.getSlayerInfo(config.targetName());
            playerUtil.getBeingInteracted(config.targetName()).first().ifPresent(n -> {
                if (n.getHealthRatio() == -1) return;
                if (n.getHealthRatio() <= slayerInfo.getUseHp()) {
                    slayerHelper.useSlayerItem(slayerInfo.getItemName());
                    timeout = 3;
                }
            });
        }

        if (!playerUtil.isInteracting() || player.getAnimation() == -1) idleTicks++;
        else idleTicks = 0;
        if (timeout > 0) {
            timeout--;
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN || EthanApiPlugin.isMoving() || !started) {
            return;
        }


        Inventory.search().onlyUnnoted().withAction("Bury").filter(b -> config.buryBones()).first().ifPresent(bone -> {
            MousePackets.queueClickPacket();
            WidgetPackets.queueWidgetAction(bone, "Bury");
            timeout = 1;
        });

        if (lootQueue.isEmpty()) looting = false;
        checkRunEnergy();
        hasFood = supplies.findFood() != null;
        hasPrayerPot = supplies.findPrayerPotion() != null;
        hasCombatPot = supplies.findCombatPotion() != null;
        hasBones = supplies.findBone() != null;


        if (!lootQueue.isEmpty()) {
            looting = true;
            ItemStack itemStack = lootQueue.peek();
            TileItems.search().withId(itemStack.getId()).first().ifPresent(item -> {
//                log.info("Looting: " + item.getTileItem().getId());
                ItemComposition comp = itemManager.getItemComposition(item.getTileItem().getId());
                if (comp.isStackable() || comp.getNote() != -1) {
//                    log.info("stackable loot " + comp.getName());
                    if (lootHelper.hasStackableLoot(comp)) {
//                        log.info("Has stackable loot");
                        item.interact(false);
                    }
                }
                if (!Inventory.full()) {
                    item.interact(false);
                } else {
                    EthanApiPlugin.sendClientMessage("Inventory full, stopping. Will handle in future update");
                    EthanApiPlugin.stopPlugin(this);
                }
            });
            timeout = 3;
            lootQueue.remove();
            return;
        }
        if (playerUtil.isInteracting() || looting) {
            timeout = 6;
            return;
        }
        targetNpc = util.findNpc(config.targetName());
        if (targetNpc == null && isSlayerNpc && !slayerInfo.getDisturbAction().isEmpty()) {
            Optional<NPC> disturbNpc = NPCs.search().withName(slayerInfo.getUndisturbedName()).first();
//            log.info("Disturbing " + slayerInfo.getUndisturbedName());
            disturbNpc.ifPresent(npc -> {
                MousePackets.queueClickPacket();
                NPCPackets.queueNPCAction(disturbNpc.get(), slayerInfo.getDisturbAction());
                timeout = 6;
                idleTicks = 0;
            });
        } else {
            if (targetNpc != null) {
//                log.info("Should fight, found npc");
                MousePackets.queueClickPacket();
                NPCPackets.queueNPCAction(targetNpc, "Attack");
                timeout = 6;
                idleTicks = 0;
            }
        }
    }

    private void handleFullInventory() {

    }

    private void handleCombatPot() {
        if (hasCombatPot) {
            InventoryInteraction.useItem(supplies.findCombatPotion(), "Drink");
            timeout = 1;
        }
    }

    private void handlePrayerPot() {
        if (hasPrayerPot) {
            InventoryInteraction.useItem(supplies.findPrayerPotion(), "Drink");
            timeout = 1;
        }
    }

    private void handleEating() {
        if (hasFood) {
            InventoryInteraction.useItem(supplies.findFood(), "Eat");
            timeout = 2;
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        if (!started || !config.lootEnabled()) return;
        Collection<ItemStack> items = event.getItems();
        items.stream().filter(item -> {
            ItemComposition comp = itemManager.getItemComposition(item.getId());
            return lootHelper.getLootNames().contains(comp.getName());
        }).forEach(it -> {
//            log.info("Adding to lootQueue: " + it.getId());
            lootQueue.add(it);
        });
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (!started) return;
        if (client.getBoostedSkillLevel(Skill.HITPOINTS) <= config.eatAt()) {
//            log.info("should eat");
            handleEating();
        }
        if (config.usePrayerPotion()) {
            if (client.getBoostedSkillLevel(Skill.PRAYER) <= config.usePrayerPotAt()) {
                handlePrayerPot();
            }
        }
        if (config.useCombatPotion()) {
            if (client.getBoostedSkillLevel(Skill.STRENGTH) <= config.useCombatPotAt()) {
                handleCombatPot();
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!started) return;
    }


    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (!started) return;
        int bid = event.getVarbitId();
        int pid = event.getVarpId();
        if (pid == VarPlayer.SLAYER_TASK_SIZE) {
            if (event.getValue() <= 0 && config.shutdownOnTaskDone()) {
                InventoryInteraction.useItem(supplies.findTeleport(), "Break");
                EthanApiPlugin.sendClientMessage("Task done, stopping");
                resetEverything();
            }
        } else if (pid == VarPlayer.CANNON_AMMO) {
            if (event.getValue() <= ThreadLocalRandom.current().nextInt(4, 12)) {
                reloadCannon();
                timeout = 1;
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("AutoCombatConfig"))
            return;
        if (event.getKey().equals("lootNames")) {
            lootHelper.setLootNames(null);
            lootHelper.getLootNames();
        }
    }


    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.HOPPING || state == GameState.LOGGED_IN) return;
        EthanApiPlugin.stopPlugin(this);
    }

    private void checkRunEnergy() {
        if (runIsOff() && playerUtil.runEnergy() >= 30) {
            MousePackets.queueClickPacket();
            WidgetPackets.queueWidgetActionPacket(1, 10485787, -1, -1);
        }
    }

    private boolean runIsOff() {
        return EthanApiPlugin.getClient().getVarpValue(173) == 0;
    }

    private void reloadCannon() {
        Optional<Widget> cannonball = InventoryUtil.nameContainsNoCase("cannonball").first();

        if (cannonball.isPresent()) {
            Optional<TileObject> to = ObjectUtil.nameContainsNoCase("dwarf multicannon").nearestToPlayer();
            if (to.isPresent()) {
                MousePackets.queueClickPacket();
                MousePackets.queueClickPacket();
                ObjectPackets.queueWidgetOnTileObject(cannonball.get(), to.get());
            }
        }
    }

    private final HotkeyListener toggle = new HotkeyListener(() -> config.toggle()) {
        @Override
        public void hotkeyPressed() {
            toggle();
        }
    };

    public void toggle() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        started = !started;
    }
}