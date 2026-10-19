package ru.manus.tradeorders;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.util.*;

/**
 * Приваты кланов (/clan private).
 * Лидер получает «Ядро привата» (маяк). Где ядро поставлено — тот чанк приватится первым.
 * ПКМ по ядру открывает карту соседних чанков, где можно докупить приват.
 * Цена n-го чанка: base-cost + cost-step * (n - 1) → 5000, 10000, 15000 ...
 */
public final class ClanClaims implements Listener {
    private final TradeOrdersPlugin plugin;
    private final NamespacedKey coreKey;
    /** "world;x;z" -> id клана */
    private final Map<String, Integer> claims = new HashMap<>();
    /** id клана -> расположение ядра */
    private final Map<Integer, Location> cores = new HashMap<>();
    /** последний чанк, в котором был игрок (для уведомлений при переходе границы) */
    private final Map<UUID, Integer> lastOwner = new HashMap<>();
    /** Явные настройки показа границ: true — показывать, false — скрыть. Нет записи — значение по умолчанию из конфига. */
    private final Map<UUID, Boolean> borderPrefs = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask borderTask;

    private static final int MAP_COLS = 9, MAP_ROWS = 5;

    ClanClaims(TradeOrdersPlugin plugin) {
        this.plugin = plugin;
        this.coreKey = new NamespacedKey(plugin, "clan_core");
    }

    // ------------------------------------------------------------------ config

    private long baseCost() { return Math.max(0L, plugin.getConfig().getLong("clan.private.base-cost", 5000L)); }
    private long costStep() { return Math.max(0L, plugin.getConfig().getLong("clan.private.cost-step", 5000L)); }
    private int maxChunks() { return Math.max(1, plugin.getConfig().getInt("clan.private.max-chunks", 16)); }
    private boolean payFromTreasury() { return plugin.getConfig().getBoolean("clan.private.pay-from-treasury", true); }
    private boolean officersManage() { return plugin.getConfig().getBoolean("clan.private.officers-can-manage", false); }
    private boolean membersClaim() { return plugin.getConfig().getBoolean("clan.private.members-can-claim", true); }
    private boolean protectExplosions() { return plugin.getConfig().getBoolean("clan.private.protect-explosions", true); }
    private boolean bordersDefault() { return plugin.getConfig().getBoolean("clan.private.borders.default-visible", true); }
    private int bordersInterval() { return Math.max(5, plugin.getConfig().getInt("clan.private.borders.interval-ticks", 20)); }
    private int bordersDistance() { return Math.max(8, Math.min(128, plugin.getConfig().getInt("clan.private.borders.view-distance", 48))); }
    private boolean notifyBorders() { return plugin.getConfig().getBoolean("clan.private.border-messages", true); }

    /** Цена следующего чанка, если у клана уже owned чанков. */
    long nextCost(int owned) {
        return plugin.su_safeAdd(baseCost(), plugin.su_safeMultiply(costStep(), owned));
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String world, int x, int z) { return world + ";" + x + ";" + z; }
    private static String key(Chunk c) { return key(c.getWorld().getName(), c.getX(), c.getZ()); }
    private static String key(Location l) { return key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4); }

    Integer ownerAt(Location l) { return l == null || l.getWorld() == null ? null : claims.get(key(l)); }

    int countOf(int clanId) {
        int n = 0;
        for (Integer v : claims.values()) if (v == clanId) n++;
        return n;
    }

    private boolean isCore(Block b) {
        for (Location l : cores.values()) {
            if (l.getWorld() != null && l.getWorld().equals(b.getWorld())
                    && l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) return true;
        }
        return false;
    }

    private Integer coreClanAt(Block b) {
        for (Map.Entry<Integer, Location> e : cores.entrySet()) {
            Location l = e.getValue();
            if (l.getWorld() != null && l.getWorld().equals(b.getWorld())
                    && l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) return e.getKey();
        }
        return null;
    }

    /** Может ли игрок строить/взаимодействовать в этой точке. */
    private boolean canBuild(Player p, Location l) {
        Integer owner = ownerAt(l);
        if (owner == null) return true;
        if (p.hasPermission("tradeorders.admin")) return true;
        TradeOrdersPlugin.Clan c = plugin.su_clanOf(p.getUniqueId());
        return c != null && c.id == owner;
    }

    private boolean canManage(Player p, TradeOrdersPlugin.Clan c) {
        if (c == null) return false;
        if (c.leader.equals(p.getUniqueId())) return true;
        return officersManage() && c.isOfficer(p.getUniqueId());
    }

    /** Докупать чанки: лидер, (офицеры) и — если включено — любой участник клана. */
    private boolean canClaim(Player p, TradeOrdersPlugin.Clan c) {
        if (canManage(p, c)) return true;
        return c != null && membersClaim() && c.members.containsKey(p.getUniqueId());
    }

    private String clanLabel(int clanId) {
        TradeOrdersPlugin.Clan c = plugin.su_clanById(clanId);
        return c == null ? "?" : "[" + c.tag + "] " + c.name;
    }

    private void deny(Player p) {
        Integer owner = ownerAt(p.getLocation());
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(ChatColor.RED + "Это территория клана" + (owner == null ? "" : " " + clanLabel(owner))));
    }

    /** Оплата привата: из казны или личных монет. Возвращает null при успехе, иначе текст ошибки. */
    private String pay(Player p, TradeOrdersPlugin.Clan c, long cost) {
        if (cost <= 0) return null;
        if (payFromTreasury()) {
            if (c.balance < cost) return "В казне нужно " + cost + " монет (есть " + c.balance + "). Пополни: /clan deposit <сумма>";
            c.balance -= cost;
            plugin.su_saveClans();
        } else {
            long bal = plugin.su_getBalance(p.getUniqueId());
            if (bal < cost) return "Нужно " + cost + " личных монет (есть " + bal + ").";
            plugin.su_setBalance(p.getUniqueId(), bal - cost);
            plugin.su_saveBalances();
        }
        return null;
    }

    // ------------------------------------------------------------------ команда /clan private

    void handleCommand(Player p, TradeOrdersPlugin.Clan c, String[] args) {
        if (args.length >= 2 && (args[1].equalsIgnoreCase("borders") || args[1].equalsIgnoreCase("border"))) {
            toggleBorders(p);
            return;
        }
        if (c == null) {
            p.sendMessage(ChatColor.RED + "Ты не состоишь в клане.");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("info")) {
            sendInfo(p, c);
            return;
        }
        if (sub.equals("borders") || sub.equals("border")) {
            toggleBorders(p);
            return;
        }
        if (!c.leader.equals(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Приват может оформить только лидер клана.");
            sendInfo(p, c);
            return;
        }
        Location core = cores.get(c.id);
        if (core != null) {
            p.sendMessage(ChatColor.YELLOW + "Ядро привата уже стоит: " + fmt(core) + ". ПКМ по нему — меню чанков.");
            sendInfo(p, c);
            return;
        }
        for (ItemStack it : p.getInventory().getContents()) {
            if (isCoreItem(it)) {
                p.sendMessage(ChatColor.YELLOW + "Ядро привата уже у тебя в инвентаре — поставь его на землю.");
                return;
            }
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(coreItem(c));
        if (!left.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Нет места в инвентаре для ядра привата.");
            return;
        }
        p.sendMessage(ChatColor.GREEN + "Получено ядро привата (маяк). Поставь его в чанке, который хочешь заприватить.");
        p.sendMessage(ChatColor.GRAY + "Первый чанк стоит " + nextCost(0) + " монет" + (payFromTreasury() ? " из казны клана" : "")
                + ", каждый следующий дороже на " + costStep() + ". ПКМ по ядру — выбор чанков.");
    }

    private void sendInfo(Player p, TradeOrdersPlugin.Clan c) {
        int n = countOf(c.id);
        Location core = cores.get(c.id);
        p.sendMessage(ChatColor.GOLD + "Приват клана [" + c.tag + "]: " + n + "/" + maxChunks() + " чанков"
                + (core == null ? " • ядро не установлено" : " • ядро: " + fmt(core)));
        if (n < maxChunks()) p.sendMessage(ChatColor.GRAY + "Следующий чанк: " + nextCost(n) + " монет" + (payFromTreasury() ? " (из казны, сейчас " + c.balance + ")" : ""));
    }

    private static String fmt(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private ItemStack coreItem(TradeOrdersPlugin.Clan c) {
        ItemStack it = new ItemStack(Material.BEACON);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Ядро привата [" + c.tag + "]");
        m.setLore(List.of(
                ChatColor.GRAY + "Поставь в чанке, который хочешь заприватить.",
                ChatColor.GRAY + "Первый чанк: " + ChatColor.GOLD + nextCost(0) + " монет",
                ChatColor.GRAY + "ПКМ по ядру — меню выбора чанков.",
                ChatColor.GRAY + "Снять приват: лидер ломает ядро с Shift."));
        m.getPersistentDataContainer().set(coreKey, PersistentDataType.INTEGER, c.id);
        it.setItemMeta(m);
        return it;
    }

    private boolean isCoreItem(ItemStack it) {
        return it != null && it.getType() == Material.BEACON && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(coreKey, PersistentDataType.INTEGER);
    }

    /** Роспуск клана: снимаем все приваты и убираем ядро. */
    void onClanDisband(int clanId) {
        claims.values().removeIf(v -> v == clanId);
        Location core = cores.remove(clanId);
        if (core != null && core.getWorld() != null) core.getBlock().setType(Material.AIR);
        save();
    }

    // ------------------------------------------------------------------ ядро: установка, ПКМ, снятие

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItemInHand();
        if (isCoreItem(it)) {
            e.setCancelled(true); // по умолчанию запрещаем, разрешаем только при успешной оплате
            int clanId = it.getItemMeta().getPersistentDataContainer().get(coreKey, PersistentDataType.INTEGER);
            TradeOrdersPlugin.Clan c = plugin.su_clanOf(p.getUniqueId());
            if (c == null || c.id != clanId) { p.sendMessage(ChatColor.RED + "Это ядро другого клана."); return; }
            if (!c.leader.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Поставить ядро может только лидер клана."); return; }
            if (cores.containsKey(c.id)) { p.sendMessage(ChatColor.RED + "У клана уже есть ядро: " + fmt(cores.get(c.id))); return; }
            Location loc = e.getBlockPlaced().getLocation();
            Integer owner = ownerAt(loc);
            if (owner != null && owner != c.id) { p.sendMessage(ChatColor.RED + "Этот чанк уже занят кланом " + clanLabel(owner) + "."); return; }
            boolean alreadyOwn = owner != null;
            if (!alreadyOwn) {
                if (countOf(c.id) >= maxChunks()) { p.sendMessage(ChatColor.RED + "Достигнут лимит чанков: " + maxChunks()); return; }
                long cost = nextCost(countOf(c.id));
                String err = pay(p, c, cost);
                if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
                claims.put(key(loc), c.id);
                p.sendMessage(ChatColor.GREEN + "Чанк заприватен за " + cost + " монет!");
            }
            e.setCancelled(false);
            cores.put(c.id, loc);
            save();
            p.sendMessage(ChatColor.GREEN + "Ядро привата установлено. ПКМ по нему — меню выбора чанков.");
            plugin.su_notifyClan(c, ChatColor.GOLD + p.getName() + " установил ядро привата: " + fmt(loc), p.getUniqueId());
            return;
        }
        if (!canBuild(p, e.getBlockPlaced().getLocation())) { e.setCancelled(true); deny(p); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Integer coreClan = coreClanAt(b);
        if (coreClan != null) {
            TradeOrdersPlugin.Clan c = plugin.su_clanById(coreClan);
            boolean leader = c != null && c.leader.equals(p.getUniqueId());
            if (!leader && !p.hasPermission("tradeorders.admin")) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Ядро привата может снять только лидер клана.");
                return;
            }
            if (!p.isSneaking()) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.YELLOW + "Сломай ядро, зажав Shift, — это снимет ВЕСЬ приват клана (деньги не возвращаются).");
                return;
            }
            e.setDropItems(false);
            int removed = countOf(coreClan);
            claims.values().removeIf(v -> v.equals(coreClan));
            cores.remove(coreClan);
            save();
            if (c != null) {
                p.getInventory().addItem(coreItem(c)).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
                plugin.su_notifyClan(c, ChatColor.RED + p.getName() + " снял приват клана (" + removed + " чанков).", null);
            }
            return;
        }
        if (!canBuild(p, b.getLocation())) { e.setCancelled(true); deny(p); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && b.getType() == Material.BEACON) {
            Integer coreClan = coreClanAt(b);
            if (coreClan != null) {
                if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) { e.setCancelled(true); return; }
                e.setCancelled(true);
                TradeOrdersPlugin.Clan mine = plugin.su_clanOf(p.getUniqueId());
                if ((mine == null || mine.id != coreClan) && !p.hasPermission("tradeorders.admin")) {
                    p.sendMessage(ChatColor.RED + "Это ядро привата клана " + clanLabel(coreClan) + ".");
                    return;
                }
                openMenu(p, coreClan);
                return;
            }
        }
        if ((e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getAction() == org.bukkit.event.block.Action.PHYSICAL)
                && !canBuild(p, b.getLocation())) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) e.setCancelled(true);
            else if (e.getItem() != null && isRiskyItem(e.getItem().getType())) e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (e.getAction() != org.bukkit.event.block.Action.PHYSICAL) deny(p);
        }
    }

    private static boolean isRiskyItem(Material m) {
        String n = m.name();
        return n.endsWith("_BUCKET") || n.equals("BUCKET") || n.equals("FLINT_AND_STEEL") || n.equals("FIRE_CHARGE")
                || n.equals("BONE_MEAL") || n.endsWith("_SPAWN_EGG") || n.equals("ARMOR_STAND") || n.endsWith("_BOAT")
                || n.endsWith("_MINECART") || n.equals("MINECART") || n.equals("END_CRYSTAL") || n.endsWith("_RAFT");
    }

    // ------------------------------------------------------------------ меню чанков

    static final class ClaimMenu implements InventoryHolder {
        Inventory inventory;
        int clanId;
        String world;
        int cx, cz;
        @Override public Inventory getInventory() { return inventory; }
    }

    private void openMenu(Player p, int clanId) {
        Location core = cores.get(clanId);
        if (core == null || core.getWorld() == null) return;
        TradeOrdersPlugin.Clan c = plugin.su_clanById(clanId);
        if (c == null) return;
        ClaimMenu h = new ClaimMenu();
        h.clanId = clanId;
        h.world = core.getWorld().getName();
        h.cx = core.getBlockX() >> 4;
        h.cz = core.getBlockZ() >> 4;
        h.inventory = Bukkit.createInventory(h, 54, ChatColor.DARK_AQUA + "Приват [" + c.tag + "] — карта чанков");
        int owned = countOf(clanId);
        long cost = nextCost(owned);
        boolean manage = canManage(p, c);
        boolean claim = canClaim(p, c);
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        for (int row = 0; row < MAP_ROWS; row++) {
            for (int col = 0; col < MAP_COLS; col++) {
                int x = h.cx + col - MAP_COLS / 2, z = h.cz + row - MAP_ROWS / 2;
                Integer owner = claims.get(key(h.world, x, z));
                String coords = ChatColor.DARK_GRAY + "Чанк " + x + ", " + z + " (блоки " + (x << 4) + ".." + ((x << 4) + 15) + ", " + (z << 4) + ".." + ((z << 4) + 15) + ")";
                List<String> lore = new ArrayList<>();
                lore.add(coords);
                if (x == pcx && z == pcz && p.getWorld().getName().equals(h.world)) lore.add(ChatColor.AQUA + "Ты здесь");
                Material mat;
                String name;
                if (x == h.cx && z == h.cz) {
                    mat = Material.BEACON;
                    name = ChatColor.AQUA + "Ядро привата";
                    lore.add(ChatColor.GRAY + "Этот чанк нельзя снять отдельно —");
                    lore.add(ChatColor.GRAY + "лидер ломает ядро с Shift.");
                } else if (owner != null && owner == clanId) {
                    mat = Material.LIME_STAINED_GLASS_PANE;
                    name = ChatColor.GREEN + "Наш чанк";
                    if (manage) lore.add(ChatColor.YELLOW + "Shift+клик — снять приват (без возврата денег)");
                } else if (owner != null) {
                    mat = Material.RED_STAINED_GLASS_PANE;
                    name = ChatColor.RED + "Клан " + clanLabel(owner);
                } else if (adjacentToClan(h.world, x, z, clanId)) {
                    mat = Material.YELLOW_STAINED_GLASS_PANE;
                    name = ChatColor.YELLOW + "Свободный чанк";
                    if (owned >= maxChunks()) lore.add(ChatColor.RED + "Достигнут лимит " + maxChunks() + " чанков");
                    else {
                        lore.add(ChatColor.GRAY + "Цена: " + ChatColor.GOLD + cost + " монет" + (payFromTreasury() ? " (из казны)" : ""));
                        if (claim) lore.add(ChatColor.YELLOW + "Клик — заприватить");
                    }
                } else {
                    mat = Material.GRAY_STAINED_GLASS_PANE;
                    name = ChatColor.GRAY + "Свободный чанк";
                    lore.add(ChatColor.DARK_GRAY + "Можно приватить только рядом с вашей территорией");
                }
                h.inventory.setItem(row * 9 + col, named(mat, name, lore));
            }
        }
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) h.inventory.setItem(i, filler);
        List<String> info = new ArrayList<>();
        info.add(ChatColor.GRAY + "Чанков: " + ChatColor.WHITE + owned + "/" + maxChunks());
        if (owned < maxChunks()) info.add(ChatColor.GRAY + "Следующий: " + ChatColor.GOLD + cost + " монет");
        info.add(ChatColor.GRAY + "Казна клана: " + ChatColor.GOLD + c.balance);
        info.add(ChatColor.GRAY + "Верх карты — север, ядро в центре.");
        info.add(ChatColor.GRAY + "Участники клана свободно строят на территории.");
        if (!claim) info.add(ChatColor.RED + "Докупать чанки может только лидер.");
        else if (!manage) info.add(ChatColor.GRAY + "Снимать чанки может только лидер.");
        h.inventory.setItem(49, named(Material.MAP, ChatColor.GOLD + "Приват клана [" + c.tag + "]", info));
        boolean vis = bordersVisible(p);
        h.inventory.setItem(47, named(vis ? Material.ENDER_EYE : Material.ENDER_PEARL,
                (vis ? ChatColor.GREEN + "Границы привата: показаны" : ChatColor.GRAY + "Границы привата: скрыты"),
                List.of(ChatColor.GRAY + "Границы рисуются частицами:",
                        ChatColor.GREEN + "зелёные" + ChatColor.GRAY + " — ваш клан, " + ChatColor.RED + "красные" + ChatColor.GRAY + " — чужие.",
                        ChatColor.GRAY + "Видны только тебе.",
                        ChatColor.YELLOW + "Клик — " + (vis ? "скрыть" : "показать"),
                        ChatColor.DARK_GRAY + "Или командой: /clan private borders")));
        h.inventory.setItem(53, named(Material.BARRIER, ChatColor.RED + "Закрыть", List.of()));
        p.openInventory(h.inventory);
    }

    private boolean adjacentToClan(String world, int x, int z, int clanId) {
        Integer v;
        return ((v = claims.get(key(world, x + 1, z))) != null && v == clanId)
                || ((v = claims.get(key(world, x - 1, z))) != null && v == clanId)
                || ((v = claims.get(key(world, x, z + 1))) != null && v == clanId)
                || ((v = claims.get(key(world, x, z - 1))) != null && v == clanId);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ClaimMenu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot == 53) { p.closeInventory(); return; }
        if (slot == 47) { toggleBorders(p); openMenu(p, h.clanId); return; }
        if (slot >= MAP_ROWS * 9) return;
        TradeOrdersPlugin.Clan c = plugin.su_clanById(h.clanId);
        if (c == null) { p.closeInventory(); return; }
        int row = slot / 9, col = slot % 9;
        int x = h.cx + col - MAP_COLS / 2, z = h.cz + row - MAP_ROWS / 2;
        String k = key(h.world, x, z);
        Integer owner = claims.get(k);
        if (x == h.cx && z == h.cz) return;
        if (owner != null && owner == h.clanId) {
            if (!canManage(p, c)) { p.sendMessage(ChatColor.RED + "Снимать приват с чанков может только лидер клана."); return; }
            if (!e.isShiftClick()) { p.sendMessage(ChatColor.GRAY + "Чтобы снять приват с чанка, кликни с Shift."); return; }
            claims.remove(k);
            save();
            p.sendMessage(ChatColor.YELLOW + "Приват с чанка " + x + ", " + z + " снят.");
        } else if (owner != null) {
            p.sendMessage(ChatColor.RED + "Чанк занят кланом " + clanLabel(owner) + ".");
            return;
        } else {
            if (!canClaim(p, c)) { p.sendMessage(ChatColor.RED + "Докупать чанки может только лидер клана."); return; }
            if (!adjacentToClan(h.world, x, z, h.clanId)) { p.sendMessage(ChatColor.RED + "Приватить можно только чанки, граничащие с вашей территорией."); return; }
            int owned = countOf(h.clanId);
            if (owned >= maxChunks()) { p.sendMessage(ChatColor.RED + "Достигнут лимит чанков: " + maxChunks()); return; }
            long cost = nextCost(owned);
            String err = pay(p, c, cost);
            if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
            claims.put(k, h.clanId);
            save();
            p.sendMessage(ChatColor.GREEN + "Чанк " + x + ", " + z + " заприватен за " + cost + " монет. Следующий: " + nextCost(owned + 1) + ".");
            plugin.su_notifyClan(c, ChatColor.GOLD + p.getName() + " заприватил чанк " + x + ", " + z + " (" + (owned + 1) + "/" + maxChunks() + ").", p.getUniqueId());
        }
        openMenu(p, h.clanId);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ClaimMenu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ защита территории

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()).getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlockClicked().getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof Player) return;
        if (!canBuild(e.getPlayer(), t.getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!canBuild(e.getPlayer(), e.getRightClicked().getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && !canBuild(e.getPlayer(), e.getEntity().getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = playerOf(e.getRemover());
        if (p != null) {
            if (!canBuild(p, e.getEntity().getLocation())) { e.setCancelled(true); deny(p); }
        } else if (ownerAt(e.getEntity().getLocation()) != null && protectExplosions()) {
            e.setCancelled(true);
        }
    }

    /** Мобы-питомцы, животные, жители, стойки и рамки на территории защищены от чужих игроков. PvP не трогаем. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (v instanceof Player || v instanceof Monster) return;
        if (!(v instanceof Animals || v instanceof AbstractVillager || v instanceof ArmorStand || v instanceof Hanging
                || v instanceof Vehicle || v instanceof Golem || v instanceof Allay)) return;
        Player p = playerOf(e.getDamager());
        if (p != null && !canBuild(p, v.getLocation())) { e.setCancelled(true); deny(p); }
    }

    private static Player playerOf(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        if (e instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> isCore(b) || (protectExplosions() && ownerAt(b.getLocation()) != null));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> isCore(b) || (protectExplosions() && ownerAt(b.getLocation()) != null));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        // эндермены, иссушители, разрушители и т.п. не трогают блоки на территории
        if (e.getEntity() instanceof Player) return;
        if (e.getEntity() instanceof FallingBlock) return;
        if (isCore(e.getBlock()) || (protectExplosions() && ownerAt(e.getBlock().getLocation()) != null)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    private boolean pistonCrosses(Block piston, List<Block> moved, org.bukkit.block.BlockFace dir) {
        Integer src = ownerAt(piston.getLocation());
        for (Block b : moved) {
            if (isCore(b)) return true;
            Integer a = ownerAt(b.getLocation());
            Integer t = ownerAt(b.getRelative(dir).getLocation());
            if (!Objects.equals(a, src) && a != null) return true;
            if (!Objects.equals(t, src) && t != null) return true;
        }
        if (moved.isEmpty()) {
            Integer t = ownerAt(piston.getRelative(dir).getLocation());
            return t != null && !Objects.equals(t, src);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Integer to = ownerAt(e.getToBlock().getLocation());
        if (to != null && !Objects.equals(to, ownerAt(e.getBlock().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (ownerAt(e.getBlock().getLocation()) == null) return;
        if (e.getPlayer() != null) {
            if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); deny(e.getPlayer()); }
        } else if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA
                || e.getCause() == BlockIgniteEvent.IgniteCause.FIREBALL) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ сообщения о границах

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!notifyBorders() || e.getTo() == null) return;
        if ((e.getFrom().getBlockX() >> 4) == (e.getTo().getBlockX() >> 4) && (e.getFrom().getBlockZ() >> 4) == (e.getTo().getBlockZ() >> 4)
                && e.getFrom().getWorld() == e.getTo().getWorld()) return;
        Integer now = ownerAt(e.getTo());
        UUID id = e.getPlayer().getUniqueId();
        Integer before = lastOwner.containsKey(id) ? lastOwner.get(id) : null;
        if (Objects.equals(now, before)) return;
        if (now == null) lastOwner.remove(id); else lastOwner.put(id, now);
        String msg = now == null ? ChatColor.GRAY + "Ничейная территория" : ChatColor.GOLD + "Территория клана " + clanLabel(now);
        e.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastOwner.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ визуальные границы (частицы)

    boolean bordersVisible(Player p) {
        Boolean v = borderPrefs.get(p.getUniqueId());
        return v == null ? bordersDefault() : v;
    }

    private void toggleBorders(Player p) {
        boolean now = !bordersVisible(p);
        borderPrefs.put(p.getUniqueId(), now);
        save();
        p.sendMessage(now ? ChatColor.GREEN + "Границы приватов показаны (видны только тебе)."
                : ChatColor.GRAY + "Границы приватов скрыты. Включить: меню ядра или /clan private borders");
    }

    void startBorderTask() {
        if (borderTask != null) borderTask.cancel();
        borderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drawBorders, 40L, bordersInterval());
    }

    private static final Particle.DustOptions OWN = new Particle.DustOptions(Color.fromRGB(60, 255, 90), 1.1f);
    private static final Particle.DustOptions FOREIGN = new Particle.DustOptions(Color.fromRGB(255, 60, 60), 1.1f);

    private void drawBorders() {
        if (claims.isEmpty()) return;
        int dist = bordersDistance();
        int chunkR = (dist >> 4) + 1;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bordersVisible(p)) continue;
            World w = p.getWorld();
            String wn = w.getName();
            Location pl = p.getLocation();
            int pcx = pl.getBlockX() >> 4, pcz = pl.getBlockZ() >> 4;
            TradeOrdersPlugin.Clan mine = plugin.su_clanOf(p.getUniqueId());
            Integer myId = mine == null ? null : mine.id;
            double distSq = (double) dist * dist;
            for (int cx = pcx - chunkR; cx <= pcx + chunkR; cx++) {
                for (int cz = pcz - chunkR; cz <= pcz + chunkR; cz++) {
                    Integer owner = claims.get(key(wn, cx, cz));
                    if (owner == null) continue;
                    Particle.DustOptions col = owner.equals(myId) ? OWN : FOREIGN;
                    int bx = cx << 4, bz = cz << 4;
                    // рисуем только внешние стороны территории (где сосед — другой владелец)
                    if (!owner.equals(claims.get(key(wn, cx, cz - 1)))) edge(p, pl, distSq, col, bx, bz, 1, 0);
                    if (!owner.equals(claims.get(key(wn, cx, cz + 1)))) edge(p, pl, distSq, col, bx, bz + 16, 1, 0);
                    if (!owner.equals(claims.get(key(wn, cx - 1, cz)))) edge(p, pl, distSq, col, bx, bz, 0, 1);
                    if (!owner.equals(claims.get(key(wn, cx + 1, cz)))) edge(p, pl, distSq, col, bx + 16, bz, 0, 1);
                }
            }
        }
    }

    /** Линия длиной 16 блоков от (x,z) в направлении (dx,dz), на двух высотах относительно игрока. */
    private void edge(Player p, Location pl, double distSq, Particle.DustOptions col, int x, int z, int dx, int dz) {
        double baseY = Math.floor(pl.getY());
        for (int i = 0; i <= 16; i++) {
            double px = x + dx * i, pz = z + dz * i;
            double ddx = px - pl.getX(), ddz = pz - pl.getZ();
            if (ddx * ddx + ddz * ddz > distSq) continue;
            p.spawnParticle(Particle.DUST, px, baseY + 0.3, pz, 1, 0, 0, 0, 0, col);
            p.spawnParticle(Particle.DUST, px, baseY + 1.6, pz, 1, 0, 0, 0, 0, col);
        }
    }

    // ------------------------------------------------------------------ хранение

    private File file() { return new File(plugin.getDataFolder(), "claims.yml"); }

    void load() {
        claims.clear();
        cores.clear();
        borderPrefs.clear();
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection cl = y.getConfigurationSection("claims");
        if (cl != null) {
            for (String id : cl.getKeys(false)) {
                try {
                    int clanId = Integer.parseInt(id);
                    if (plugin.su_clanById(clanId) == null) continue; // клан удалён
                    for (String k : cl.getStringList(id)) claims.put(k, clanId);
                } catch (NumberFormatException ignored) { }
            }
        }
        ConfigurationSection bp = y.getConfigurationSection("borders");
        if (bp != null) {
            for (String id : bp.getKeys(false)) {
                try { borderPrefs.put(UUID.fromString(id), bp.getBoolean(id)); } catch (IllegalArgumentException ignored) { }
            }
        }
        ConfigurationSection co = y.getConfigurationSection("cores");
        if (co != null) {
            for (String id : co.getKeys(false)) {
                try {
                    int clanId = Integer.parseInt(id);
                    if (plugin.su_clanById(clanId) == null) continue;
                    World w = Bukkit.getWorld(co.getString(id + ".world", "world"));
                    cores.put(clanId, new Location(w, co.getInt(id + ".x"), co.getInt(id + ".y"), co.getInt(id + ".z")));
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            Map<Integer, List<String>> byClan = new TreeMap<>();
            for (Map.Entry<String, Integer> e : claims.entrySet()) byClan.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            for (Map.Entry<Integer, List<String>> e : byClan.entrySet()) {
                Collections.sort(e.getValue());
                y.set("claims." + e.getKey(), e.getValue());
            }
            for (Map.Entry<Integer, Location> e : cores.entrySet()) {
                Location l = e.getValue();
                String p = "cores." + e.getKey() + ".";
                y.set(p + "world", l.getWorld() == null ? "world" : l.getWorld().getName());
                y.set(p + "x", l.getBlockX());
                y.set(p + "y", l.getBlockY());
                y.set(p + "z", l.getBlockZ());
            }
            for (Map.Entry<UUID, Boolean> e : borderPrefs.entrySet()) y.set("borders." + e.getKey(), e.getValue());
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file());
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not save claims: " + ex.getMessage());
        }
    }
}
