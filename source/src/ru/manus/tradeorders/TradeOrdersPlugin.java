package ru.manus.tradeorders;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

public final class TradeOrdersPlugin extends JavaPlugin implements Listener {
   private final Map<Integer, Order> orders = new LinkedHashMap<>();
   private final Map<UUID, Long> balances = new LinkedHashMap<>();
   private final Map<Material, MarketStats> market = new LinkedHashMap<>();
   private final Map<Material, Double> shopPrices = new LinkedHashMap<>();
   private final Map<Material, Map<UUID, Integer>> shopPurchased = new LinkedHashMap<>();
   private final Map<Integer, AuctionLot> auctions = new LinkedHashMap<>();
   private final List<UUID> auctionViewers = new ArrayList<>();
   private final Map<UUID, List<ItemStack>> pendingReturns = new LinkedHashMap<>();
   /** Личное хранилище игрока: предметы, которые ему доставлены (заказы, аукцион, возвраты), пока он был не в сети или инвентарь был полон. */
   private final Map<UUID, List<ItemStack>> storage = new LinkedHashMap<>();
   private final Map<UUID, Map<Material, Integer>> soldByPlayer = new LinkedHashMap<>();
   private final Map<String, Account> accounts = new LinkedHashMap<>();
   private final Set<UUID> authenticated = new HashSet<>();
   private final Map<UUID, BukkitTask> autoSellTasks = new LinkedHashMap<>();
   private final Map<UUID, Scoreboard> scoreboards = new LinkedHashMap<>();
   private final Map<UUID, TradeSession> pendingTrades = new LinkedHashMap<>();
   private final Map<UUID, TradeSession> tradeOffers = new LinkedHashMap<>();
   private final Map<Integer, Clan> clansById = new LinkedHashMap<>();
   private final Map<UUID, Integer> playerClan = new LinkedHashMap<>();
   private int nextClanId = 1;
   private ClanClaims claims;
   private int nextId = 1;
   private int nextAuctionId = 1;
   private File ordersFile;
   private File auctionFile;
   private File accountsFile;
   private File salesFile;
   private final Set<String> pendingRegistrations = new HashSet<>();
   private final Map<UUID, GameMode> lastGameModes = new LinkedHashMap<>();
   private final Map<String, Integer> loginAttempts = new LinkedHashMap<>();
   private static final SecureRandom RANDOM = new SecureRandom();
   private final Map<UUID, String> offlineNames = new LinkedHashMap<>();
   private final Map<UUID, AutoSellHolder> autoSellStates = new LinkedHashMap<>();
   private final Map<UUID, long[]> pendingClanInvites = new LinkedHashMap<>();

   private long clanUpgradeCost(int var1) {
      long var2 = Math.max(100L, this.getConfig().getLong("clan.upgrade.base-cost", 300L));
      double var4 = Math.max(1.0, this.getConfig().getDouble("clan.upgrade.cost-multiplier", 1.8));
      long var6 = (long)(var2 * Math.pow(var4, Math.max(0, var1 - 1)));
      return Math.min(var6, 1000000000L);
   }

   private int clanMaxLevel() {
      return Math.max(1, this.getConfig().getInt("clan.upgrade.max-level", 10));
   }

   @Override
   public void onEnable() {
      ConfigUpdater.run(this);
      this.saveDefaultConfig();
      this.ordersFile = new File(this.getDataFolder(), "orders.yml");
      this.auctionFile = new File(this.getDataFolder(), "auctions.yml");
      this.accountsFile = new File(this.getDataFolder(), "accounts.yml");
      this.salesFile = new File(this.getDataFolder(), "sales.yml");
      this.loadOrders();
      this.loadBalances();
      this.loadMarket();
      this.loadShopState();
      this.loadAuctions();
      this.loadStorage();
      this.flushPendingReturns();
      this.loadAccounts();
      this.loadSales();
      this.loadClans();
      this.claims = new ClanClaims(this);
      this.claims.load();
      this.claims.startBorderTask();
      Bukkit.getPluginManager().registerEvents(this, this);
      Bukkit.getPluginManager().registerEvents(this.claims, this);

      for (String var4 : new String[]{
         "orders", "order", "money", "buyer", "shop", "auction", "reg", "register", "login", "auth", "pay", "top", "autosell", "trade", "clan", "ogui", "storage"
      }) {
         Objects.requireNonNull(this.getCommand(var4)).setExecutor(this::onCommand);
      }

      Bukkit.getScheduler().runTaskTimer(this, this::tickAuctions, 300L, 300L);
      Bukkit.getScheduler().runTaskTimer(this, this::tickBoards, 20L, 20L);
      this.getLogger().info("TradeOrders enabled; loaded " + this.orders.size() + " orders, " + this.auctions.size() + " auction lots.");
   }

   @Override
   public void onDisable() {
      for (BukkitTask var2 : this.autoSellTasks.values()) {
         var2.cancel();
      }

      this.autoSellTasks.clear();
      this.saveOrders();
      this.saveBalances();
      this.saveMarket();
      this.saveShopState();
      this.saveAuctions();
      this.saveStorage();
      if (this.claims != null) {
         this.claims.save();
      }
      this.saveAccounts();
      this.saveSales();
      this.saveClans();
   }

   @Override
   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      var3 = var2.getName();
      if (!var3.equalsIgnoreCase("reg") && !var3.equalsIgnoreCase("register")) {
         if (!var3.equalsIgnoreCase("login") && !var3.equalsIgnoreCase("auth")) {
            if (!this.isAuthOk(var1)) {
               return true;
            } else if (var3.equalsIgnoreCase("money")) {
               if (var1 instanceof Player var22) {
                  var22.sendMessage(ChatColor.GOLD + "Твой баланс: " + ChatColor.YELLOW + this.getBalance(var22.getUniqueId()) + " монет");
                  return true;
               } else {
                  var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                  return true;
               }
            } else if (var3.equalsIgnoreCase("buyer")) {
               if (var1 instanceof Player var21) {
                  this.openBuyer(var21);
                  return true;
               } else {
                  var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                  return true;
               }
            } else if (!var3.equalsIgnoreCase("shop") && !var3.equalsIgnoreCase("store")) {
               if (!var3.equalsIgnoreCase("auction") && !var3.equalsIgnoreCase("ah")) {
                  if (var3.equalsIgnoreCase("orders")) {
                     if (var1 instanceof Player var20) {
                        this.openBoard(var20);
                        return true;
                     } else {
                        var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                        return true;
                     }
                  } else if (var3.equalsIgnoreCase("order")) {
                     if (var1 instanceof Player var19) {
                        if (var4.length == 0) {
                           this.sendOrderHelp(var19);
                           return true;
                        } else if (var4[0].equalsIgnoreCase("create")) {
                           if (var4.length >= 3 && var4[2].equalsIgnoreCase("money")) {
                              if (var4.length < 4) {
                                 var19.sendMessage(ChatColor.RED + "Использование: /order create <кол-во> money <монеты>");
                                 return true;
                              }
                              this.createMoneyOrder(var19, var4[1], var4[3]);
                              return true;
                           } else if (var4.length >= 3) {
                              this.createOrder(var19, var4[1], var4[2]);
                              return true;
                           } else {
                              this.sendOrderHelp(var19);
                              return true;
                           }
                        } else if (var4[0].equalsIgnoreCase("cancel")) {
                           if (var4.length >= 2) {
                              this.cancelOrder(var19, var4[1]);
                           } else {
                              var19.sendMessage(ChatColor.RED + "Отмена: /order cancel <id>");
                           }

                           return true;
                        } else {
                           this.sendOrderHelp(var19);
                           return true;
                        }
                     } else {
                        var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                        return true;
                     }
                  } else if (var3.equalsIgnoreCase("pay")) {
                     if (var1 instanceof Player var18) {
                        this.handlePay(var18, var4);
                        return true;
                     } else {
                        var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                        return true;
                     }
                  } else if (var3.equalsIgnoreCase("top")) {
                     if (var1 instanceof Player var17) {
                        this.openTop(var17);
                        return true;
                     } else {
                        var1.sendMessage(ChatColor.RED + "Команда доступна только игроку.");
                        return true;
                     }
                  } else if (var3.equalsIgnoreCase("autosell")) {
                     if (var1 instanceof Player var16) {
                        if (var4.length >= 1 && var4[0].equalsIgnoreCase("pick")) {
                           this.openAutoSellMenu(var16);
                           return true;
                        } else {
                           this.toggleAutoSell(var16);
                           return true;
                        }
                     } else {
                        var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                        return true;
                     }
                  } else if (var3.equalsIgnoreCase("trade")) {
                     if (var1 instanceof Player var15) {
                        this.handleTradeCommand(var15, var4);
                        return true;
                     } else {
                        var1.sendMessage(ChatColor.RED + "Обмен доступен только игроку.");
                        return true;
                     }
                  } else if (!var3.equalsIgnoreCase("ogui") && !var3.equalsIgnoreCase("menu") && !var3.equalsIgnoreCase("gui")) {
                     if (var3.equalsIgnoreCase("storage")) {
                        if (var1 instanceof Player varSt) {
                           this.openStorage(varSt, 0);
                        } else {
                           var1.sendMessage(ChatColor.RED + "Хранилище доступно только игроку.");
                        }
                        return true;
                     }
                     if (!var3.equalsIgnoreCase("clan")) {
                        return true;
                     } else if (var1 instanceof Player var14) {
                        this.handleClanCommand(var14, var4);
                        return true;
                     } else {
                        var1.sendMessage(ChatColor.RED + "Команда доступна только игроку.");
                        return true;
                     }
                  } else if (var1 instanceof Player var13) {
                     this.openOGui(var13);
                     return true;
                  } else {
                     var1.sendMessage(ChatColor.RED + "Меню доступно только игроку.");
                     return true;
                  }
               } else if (var1 instanceof Player var12) {
                  if (var4.length == 0) {
                     this.openAuction(var12);
                     return true;
                  } else if (var4[0].equalsIgnoreCase("sell")) {
                     if (var4.length >= 2) {
                        try {
                           long var6 = Long.parseLong(var4[1]);
                           long varBuy = var4.length >= 3 ? Long.parseLong(var4[2]) : 0L;
                           this.auctionSell(var12, var6, varBuy);
                        } catch (NumberFormatException var8) {
                           var12.sendMessage(ChatColor.RED + "Цена должна быть числом. Пример: /auction sell 50 200");
                        }
                     } else {
                        var12.sendMessage(ChatColor.RED + "Выставление: /auction sell <мин. цена> [цена выкупа] (предмет в основной руке)");
                     }

                     return true;
                  } else if (var4[0].equalsIgnoreCase("cancel")) {
                     if (var4.length >= 2) {
                        this.auctionCancel(var12, var4[1]);
                     } else {
                        var12.sendMessage(ChatColor.RED + "Отмена: /auction cancel <id лота>");
                     }

                     return true;
                  } else {
                     var12.sendMessage(ChatColor.GRAY + "Аукцион: /auction — доска лотов");
                     var12.sendMessage(ChatColor.GRAY + "Продажа: /auction sell <мин. цена> [цена выкупа] — предмет в основной руке");
                     var12.sendMessage(ChatColor.GRAY + "Shift+клик по лоту — купить сразу по цене выкупа");
                     var12.sendMessage(ChatColor.GRAY + "Отмена: /auction cancel <id>");
                     return true;
                  }
               } else {
                  var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                  return true;
               }
            } else if (var1 instanceof Player var11) {
               this.openShop(var11, 0);
               return true;
            } else {
               var1.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
               return true;
            }
         } else if (var1 instanceof Player var10) {
            this.handleLogin(var10, var4);
            return true;
         } else {
            var1.sendMessage(ChatColor.RED + "Вход доступен только игроку.");
            return true;
         }
      } else if (var1 instanceof Player var5) {
         this.handleRegister(var5, var4);
         return true;
      } else {
         var1.sendMessage(ChatColor.RED + "Регистрация доступна только игроку.");
         return true;
      }
   }

   private void sendOrderHelp(Player var1) {
      var1.sendMessage(ChatColor.GRAY + "Предметы: /order create <нужно> <предлагаю> (предметы в основной и второй руке)");
      var1.sendMessage(ChatColor.GRAY + "Монеты: /order create <нужно> money <монеты>");
      var1.sendMessage(ChatColor.GRAY + "Отмена: /order cancel <id>");
   }

   private void createOrder(Player var1, String var2, String var3) {
      int var4;
      int var5;
      try {
         var4 = Integer.parseInt(var2);
         var5 = Integer.parseInt(var3);
      } catch (NumberFormatException var11) {
         var1.sendMessage(ChatColor.RED + "Количество должно быть числом.");
         return;
      }

      if (var4 >= 1 && var4 <= 64 && var5 >= 1 && var5 <= 64) {
         ItemStack var6 = var1.getInventory().getItemInMainHand();
         ItemStack var7 = var1.getInventory().getItemInOffHand();
         if (!var6.getType().isAir() && !var7.getType().isAir()) {
            ItemStack var8 = var6.clone();
            var8.setAmount(var4);
            ItemStack var9 = var7.clone();
            var9.setAmount(var5);
            if (!this.hasItems(var1, var9)) {
               var1.sendMessage(ChatColor.RED + "У тебя нет " + this.amountName(var9) + " для оплаты заказа.");
               return;
            }
            // Оплата забирается на хранение — заказ можно выполнить, даже если владелец не в сети.
            this.removeItems(var1, var9);
            var1.updateInventory();
            Order var10 = new Order(
               this.nextId++, var1.getUniqueId(), var1.getName(), var8, var9, 0L
            );
            var10.escrow = true;
            this.orders.put(var10.id, var10);
            this.saveOrders();
            var1.sendMessage(ChatColor.GREEN + "Заказ #" + var10.id + " опубликован. " + this.amountName(var9) + " забрано на хранение.");
            var1.sendMessage(ChatColor.GRAY + "Заказ можно выполнить, даже когда ты не в сети — предметы придут в /storage. Отмена: /order cancel " + var10.id);
         } else {
            var1.sendMessage(ChatColor.RED + "Возьми нужный предмет в основную руку, а оплату — во вторую.");
         }
      } else {
         var1.sendMessage(ChatColor.RED + "Количество должно быть от 1 до 64.");
      }
   }

   private void createMoneyOrder(Player var1, String var2, String var3) {
      int var4;
      long var5;
      try {
         var4 = Integer.parseInt(var2);
         var5 = Long.parseLong(var3);
      } catch (NumberFormatException var10) {
         var1.sendMessage(ChatColor.RED + "Количество предметов и монет должно быть числом.");
         return;
      }

      if (var4 >= 1 && var4 <= 64 && var5 >= 1L && var5 <= 1000000000L) {
         ItemStack var7 = var1.getInventory().getItemInMainHand();
         if (var7.getType().isAir()) {
            var1.sendMessage(ChatColor.RED + "Возьми нужный предмет в основную руку.");
         } else if (this.getBalance(var1.getUniqueId()) < var5) {
            var1.sendMessage(ChatColor.RED + "Недостаточно монет для такого предложения. Баланс: " + this.getBalance(var1.getUniqueId()));
         } else {
            ItemStack var8 = var7.clone();
            var8.setAmount(var4);
            // Монеты списываются на хранение сразу, при отмене заказа возвращаются.
            this.setBalance(var1.getUniqueId(), this.getBalance(var1.getUniqueId()) - var5);
            this.saveBalances();
            Order var9 = new Order(
               this.nextId++, var1.getUniqueId(), var1.getName(), var8, null, var5
            );
            var9.escrow = true;
            this.orders.put(var9.id, var9);
            this.saveOrders();
            var1.sendMessage(ChatColor.GREEN + "Денежный заказ #" + var9.id + " опубликован. " + var5 + " монет забрано на хранение.");
            var1.sendMessage(ChatColor.GRAY + "Заказ можно выполнить, даже когда ты не в сети — предметы придут в /storage. Отмена: /order cancel " + var9.id);
         }
      } else {
         var1.sendMessage(ChatColor.RED + "Предметы: 1–64; монеты: 1–1 000 000 000.");
      }
   }

   private void cancelOrder(Player var1, String var2) {
      int var3;
      try {
         var3 = Integer.parseInt(var2);
      } catch (NumberFormatException var5) {
         var1.sendMessage(ChatColor.RED + "ID должен быть числом.");
         return;
      }

      Order var4 = this.orders.get(var3);
      if (var4 == null) {
         var1.sendMessage(ChatColor.RED + "Заказ не найден.");
      } else if (!var4.owner.equals(var1.getUniqueId()) && !var1.hasPermission("serverutilities.admin")) {
         var1.sendMessage(ChatColor.RED + "Можно отменить только свой заказ.");
      } else {
         this.orders.remove(var3);
         this.saveOrders();
         if (var4.escrow) {
            if (var4.moneyAmount > 0L) {
               this.setBalance(var4.owner, this.saturatingAdd(this.getBalance(var4.owner), var4.moneyAmount));
               this.saveBalances();
            } else if (var4.offered != null) {
               this.deliver(var4.owner, var4.offered.clone(), "возврат по отменённому заказу #" + var3);
            }
            var1.sendMessage(ChatColor.GREEN + "Заказ #" + var3 + " отменён, оплата возвращена владельцу.");
         } else {
            var1.sendMessage(ChatColor.GREEN + "Заказ #" + var3 + " отменён.");
         }
      }
   }

   private void openOGui(Player var1) {
      OGuiHolder var2 = new OGuiHolder();
      var2.inventory = Bukkit.createInventory(var2, 27, ChatColor.DARK_AQUA + "Меню ServerUtilities");
      ItemStack var3 = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
      ItemMeta var4 = var3.getItemMeta();
      if (var4 != null) {
         var4.setDisplayName(ChatColor.RESET + " ");
         var3.setItemMeta(var4);
      }

      for (int var5 = 0; var5 < 27; var5++) {
         var2.inventory.setItem(var5, var3);
      }

      long var12 = this.getBalance(var1.getUniqueId());

      for (OGuiEntry var9 : List.of(
         new OGuiEntry(
            10,
            Material.CHEST_MINECART,
            "" + ChatColor.YELLOW + ChatColor.BOLD + "Заказы игроков",
            List.of(ChatColor.GRAY + "Доска ордеров между игроками.", ChatColor.LIGHT_PURPLE + "/orders")
         ),
         new OGuiEntry(
            11,
            Material.GOLD_NUGGET,
            "" + ChatColor.GOLD + ChatColor.BOLD + "Скупщик",
            List.of(ChatColor.GRAY + "Продажа любых предметов серверу,", ChatColor.GRAY + "цена зависит от объёма продаж.", ChatColor.LIGHT_PURPLE + "/buyer")
         ),
         new OGuiEntry(
            12,
            Material.EMERALD,
            "" + ChatColor.GREEN + ChatColor.BOLD + "Магазин сервера",
            List.of(ChatColor.GRAY + "Покупка товаров у сервера.", ChatColor.LIGHT_PURPLE + "/shop")
         ),
         new OGuiEntry(
            13,
            Material.DIAMOND,
            "" + ChatColor.AQUA + ChatColor.BOLD + "Аукцион",
            List.of(ChatColor.GRAY + "Лоты игроков и ставки.", ChatColor.LIGHT_PURPLE + "/auction")
         ),
         new OGuiEntry(
            14,
            Material.HOPPER,
            "" + ChatColor.RED + ChatColor.BOLD + "Автоскупка",
            List.of(ChatColor.GRAY + "Автопродажа предметов из инвентаря.", ChatColor.LIGHT_PURPLE + "/autosell")
         ),
         new OGuiEntry(
            15,
            Material.NETHER_STAR,
            "" + ChatColor.LIGHT_PURPLE + ChatColor.BOLD + "Топ игроков",
            List.of(ChatColor.GRAY + "Таблица лидеров по монетам.", ChatColor.LIGHT_PURPLE + "/top")
         ),
         new OGuiEntry(
            16,
            Material.ENDER_CHEST,
            "" + ChatColor.DARK_PURPLE + ChatColor.BOLD + "Обмен",
            List.of(ChatColor.GRAY + "Безопасный обмен с игроком.", ChatColor.LIGHT_PURPLE + "/trade <игрок>")
         ),
         new OGuiEntry(
            19,
            Material.IRON_BARS,
            "" + ChatColor.DARK_RED + ChatColor.BOLD + "Клан",
            List.of(ChatColor.GRAY + "Информация о твоём клане,", ChatColor.GRAY + "казне и рангах.", ChatColor.LIGHT_PURPLE + "/clan")
         ),
         new OGuiEntry(
            25,
            Material.BARREL,
            "" + ChatColor.GREEN + ChatColor.BOLD + "Хранилище",
            List.of(ChatColor.GRAY + "Предметы по заказам и с аукциона.", ChatColor.GRAY + "Сейчас: " + this.storageCount(var1.getUniqueId()) + " стак(ов)", ChatColor.LIGHT_PURPLE + "/storage")
         )
      )) {
         ItemStack var10 = new ItemStack(var9.material);
         ItemMeta var11 = var10.getItemMeta();
         if (var11 != null) {
            var11.setDisplayName(var9.title);
            var11.setLore(var9.lore);
            var10.setItemMeta(var11);
         }

         var2.inventory.setItem(var9.slot, var10);
      }

      ItemStack var13 = new ItemStack(Material.SUNFLOWER);
      ItemMeta var14 = var13.getItemMeta();
      if (var14 != null) {
         var14.setDisplayName("" + ChatColor.GOLD + ChatColor.BOLD + "Баланс: " + var12 + " монет");
         var14.setLore(List.of(ChatColor.GRAY + "Перевод другому игроку:", ChatColor.LIGHT_PURPLE + "/pay <игрок> <сумма>"));
         var13.setItemMeta(var14);
      }

      var2.inventory.setItem(22, var13);
      var1.openInventory(var2.inventory);
   }

   @EventHandler
   public void onOGuiClick(InventoryClickEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof OGuiHolder) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
            if (var1.getWhoClicked() instanceof Player var2) {
               int var4 = var1.getSlot();
               switch (var4) {
                  case 10:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "orders", new String[0]);
                     break;
                  case 11:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "buyer", new String[0]);
                     break;
                  case 12:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "shop", new String[0]);
                     break;
                  case 13:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "auction", new String[0]);
                     break;
                  case 14:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "autosell", new String[0]);
                     break;
                  case 15:
                     var2.closeInventory();
                     this.dispatchAsCommand(var2, "top", new String[0]);
                     break;
                  case 16:
                     var2.sendMessage(ChatColor.GRAY + "Использование: /trade <игрок>");
                  case 17:
                  case 18:
                  default:
                     break;
                  case 25:
                     this.openStorage(var2, 0);
                     break;
                  case 19:
                     var2.closeInventory();
                     this.handleClanInfoOrHelp(var2);
               }
            }
         }
      }
   }

   private void handleClanInfoOrHelp(Player var1) {
      Integer var2 = this.playerClan.get(var1.getUniqueId());
      Clan var3 = var2 == null ? null : this.clansById.get(var2);
      if (var3 == null) {
         this.sendClanHelp(var1);
      } else {
         this.showClanInfo(var1, var3);
      }
   }

   private void dispatchAsCommand(Player var1, String var2, String[] var3) {
      try {
         Bukkit.dispatchCommand(var1, var3.length == 0 ? var2 : var2 + " " + String.join(" ", var3));
      } catch (Exception var5) {
      }
   }

   private void openBoard(Player var1) {
      BoardHolder var2 = new BoardHolder();
      var2.inventory = Bukkit.createInventory(var2, 54, ChatColor.DARK_GREEN + "Заказы игроков");
      int var3 = 0;

      for (Order var5 : this.orders.values()) {
         if (var3 >= 45) {
            break;
         }

         ItemStack var6 = var5.wanted.clone();
         ItemMeta var7 = var6.getItemMeta();
         if (var7 != null) {
            ArrayList var8 = new ArrayList();
            var8.add(ChatColor.GRAY + "Заказчик: " + ChatColor.WHITE + var5.ownerName);
            var8.add(ChatColor.GRAY + "Нужно: " + ChatColor.WHITE + this.amountName(var5.wanted));
            if (var5.moneyAmount > 0L) {
               var8.add(ChatColor.GRAY + "Дадут: " + ChatColor.GOLD + var5.moneyAmount + " монет");
            } else if (var5.offered != null) {
               var8.add(ChatColor.GRAY + "Дадут: " + ChatColor.WHITE + this.amountName(var5.offered));
            }

            if (var5.escrow) {
               var8.add(ChatColor.GREEN + "Оплата на хранении — можно выполнить без владельца");
            } else {
               var8.add(ChatColor.RED + "Старый заказ: нужен владелец в сети");
            }
            var8.add(ChatColor.YELLOW + "Нажми, чтобы выполнить");
            var7.setLore(var8);
            var7.setDisplayName(ChatColor.AQUA + "Заказ #" + var5.id);
            var7.getPersistentDataContainer().set(new NamespacedKey(this, "order_id"), PersistentDataType.INTEGER, var5.id);
            var6.setItemMeta(var7);
         }

         var2.inventory.setItem(var3++, var6);
      }

      var1.openInventory(var2.inventory);
   }

   private void openBuyer(Player var1) {
      BuyerHolder var2 = new BuyerHolder();
      var2.inventory = Bukkit.createInventory(var2, 54, ChatColor.DARK_GREEN + "Скупщик ресурсов");
      ItemStack var3 = this.named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + " ", List.of());

      for (int var4 = 45; var4 < 54; var4++) {
         var2.inventory.setItem(var4, var3);
      }

      var2.inventory
         .setItem(
            49,
            this.named(
               Material.GOLD_INGOT,
               ChatColor.GOLD + "Твой баланс: " + ChatColor.YELLOW + this.getBalance(var1.getUniqueId()) + " монет",
               List.of(
                  ChatColor.GRAY + "Монеты за продажу предметов из инвентаря.",
                  ChatColor.GRAY + "Чем больше сдают ресурс, тем ниже его курс.",
                  ChatColor.GRAY + "Курс постепенно восстанавливается со временем.",
                  ChatColor.YELLOW + "Нажми на лот, чтобы продать весь такой стак/тип."
               )
            )
         );
      Map var20 = var2.lots;
      ItemStack[] var5 = var1.getInventory().getStorageContents();
      int var6 = var5 == null ? 0 : var5.length;
      int var7 = Math.min(var6, 36);

      for (int var8 = 0; var8 < var7; var8++) {
         this.addLot(var20, var5[var8]);
      }

      ItemStack[] var21 = var1.getInventory().getArmorContents();
      if (var21 != null) {
         for (ItemStack var12 : var21) {
            this.addLot(var20, var12);
         }
      }

      ItemStack var22 = var1.getInventory().getItemInOffHand();
      this.addLot(var20, var22);
      int var23 = 0;

      for (Entry var25 : (Iterable<Entry>)(Iterable)(var20.entrySet())) {
         if (var23 >= 45) {
            break;
         }

         Listing var13 = (Listing)var25.getValue();
         ItemStack var14 = var13.template.clone();
         var14.setAmount(Math.min(var13.amount, Math.min(64, var14.getMaxStackSize())));
         ItemMeta var15 = var14.getItemMeta();
         if (var15 != null) {
            long var16 = this.unitPrice(var14.getType());
            long var18 = this.safeMultiply(var16, var13.amount);
            var15.setDisplayName(ChatColor.GREEN + this.pretty(var14) + " × " + var13.amount);
            var15.setLore(
               List.of(
                  ChatColor.GRAY + "У тебя: " + var13.amount + " шт.",
                  ChatColor.GRAY + "Курс: " + var16 + " монет за штуку",
                  ChatColor.GOLD + "Итого за всё: " + var18 + " монет",
                  ChatColor.YELLOW + "Нажми, чтобы продать весь стак"
               )
            );
            var14.setItemMeta(var15);
         }

         var2.inventory.setItem(var23++, var14);
      }

      var1.openInventory(var2.inventory);
   }

   private void addLot(Map<Integer, Listing> var1, ItemStack var2) {
      if (var2 != null && !var2.getType().isAir()) {
         if (this.unitPrice(var2.getType()) >= 0L) {
            for (Entry var4 : var1.entrySet()) {
               if (((Listing)var4.getValue()).template.isSimilar(var2)) {
                  Listing var10000 = (Listing)var4.getValue();
                  var10000.amount = var10000.amount + var2.getAmount();
                  return;
               }
            }

            if (var1.size() < 45) {
               Listing var5 = new Listing(var2.clone(), var2.getAmount());
               var5.template.setAmount(1);
               var1.put(var1.size(), var5);
            }
         }
      }
   }

   private void sellBuyerLot(Player var1, BuyerHolder var2, int var3) {
      Listing var4 = var2.lots.get(var3);
      if (var4 == null) {
         var1.sendMessage(ChatColor.RED + "Лот больше недоступен.");
         this.openBuyer(var1);
      } else {
         int var5 = this.countSimilar(var1, var4.template);
         if (var5 <= 0) {
            var1.sendMessage(ChatColor.RED + "Предметы больше не найдены в инвентаре.");
            this.openBuyer(var1);
         } else {
            long var6 = this.unitPrice(var4.template.getType());
            if (var6 < 0L) {
               var1.sendMessage(ChatColor.RED + "Скупщик больше не принимает этот предмет.");
               this.openBuyer(var1);
            } else {
               long var8 = this.safeMultiply(var6, var5);
               this.removeItems(var1, this.amountOf(var4.template, var5));
               this.setBalance(var1.getUniqueId(), this.saturatingAdd(this.getBalance(var1.getUniqueId()), var8));
               MarketStats var10 = this.market
                  .computeIfAbsent(var4.template.getType(), var0 -> new MarketStats());
               this.decay(var10);
               var10.pressure = var10.pressure + var5 * this.getConfig().getDouble("buyer.sell-pressure-weight", 1.0);
               var10.totalSold = this.saturatingAdd(var10.totalSold, var5);
               var10.lastUpdated = System.currentTimeMillis();
               this.recordSale(var1.getUniqueId(), var4.template.getType(), var5);
               this.saveBalances();
               this.saveMarket();
               this.saveSales();
               var1.sendMessage(ChatColor.GREEN + "Скупщик купил " + var5 + "× " + this.pretty(var4.template) + " за " + var8 + " монет");
               this.openBuyer(var1);
            }
         }
      }
   }

   private int countSimilar(Player var1, ItemStack var2) {
      int var3 = 0;
      var3 += this.countInArray(var1.getInventory().getStorageContents(), var2);
      var3 += this.countInArray(var1.getInventory().getArmorContents(), var2);
      return var3 + this.countInArray(new ItemStack[]{var1.getInventory().getItemInOffHand()}, var2);
   }

   private int countInArray(ItemStack[] var1, ItemStack var2) {
      int var3 = 0;
      if (var1 == null) {
         return 0;
      } else {
         for (ItemStack var7 : var1) {
            if (var7 != null && !var7.getType().isAir() && var7.isSimilar(var2)) {
               var3 += var7.getAmount();
            }
         }

         return var3;
      }
   }

   private ItemStack amountOf(ItemStack var1, int var2) {
      ItemStack var3 = var1.clone();
      var3.setAmount(Math.max(1, var2));
      return var3;
   }

   private void openShop(Player var1, int var2) {
      List var3 = this.sellableShopMaterials();
      int var4 = Math.max(1, (var3.size() + 44) / 45);
      if (var2 < 0) {
         var2 = 0;
      }

      if (var2 >= var4) {
         var2 = var4 - 1;
      }

      ShopHolder var5 = new ShopHolder();
      var5.inventory = Bukkit.createInventory(var5, 54, ChatColor.DARK_GREEN + "Магазин сервера (стр. " + (var2 + 1) + "/" + var4 + ")");
      int var6 = var2 * 45;
      int var7 = Math.min(var3.size(), var6 + 45);
      int var8 = 0;

      for (int var9 = var6; var9 < var7; var9++) {
         Material var10 = (Material)var3.get(var9);
         int var11 = this.shopStackSizeFor(var1, var10);
         long var12 = this.shopUnitPrice(var10, var1);
         ItemStack var14 = new ItemStack(var10, Math.min(var11, var10.getMaxStackSize()));
         ItemMeta var15 = var14.getItemMeta();
         if (var15 != null) {
            var15.setDisplayName(ChatColor.GREEN + this.prettyName(var10) + " × " + var11);
            var15.setLore(
               List.of(
                  ChatColor.GRAY + "Цена: " + this.shopPriceLine(var12, var11),
                  ChatColor.GOLD + "Твой баланс: " + this.getBalance(var1.getUniqueId()) + " монет",
                  ChatColor.YELLOW + "ЛКМ — купить стак, ПКМ — купить 1"
               )
            );
            var14.setItemMeta(var15);
         }

         var5.lots.put(var8, var10);
         var5.stackSizes.put(var8, var11);
         var5.inventory.setItem(var8++, var14);
      }

      if (var4 > 1) {
         var5.inventory.setItem(48, this.named(Material.ARROW, ChatColor.YELLOW + (var2 > 0 ? "« Назад" : "Первая страница"), List.of()));
         var5.inventory.setItem(50, this.named(Material.ARROW, ChatColor.YELLOW + (var2 + 1 < var4 ? "Вперёд »" : "Последняя страница"), List.of()));
      }

      var1.openInventory(var5.inventory);
   }

   private int shopStackSizeFor(Player var1, Material var2) {
      int var3 = var2.getMaxStackSize();
      long var4 = this.getBalance(var1.getUniqueId());
      long var6 = this.shopBaseUnitPrice(var2);
      if (var6 <= 0L) {
         return var3;
      } else {
         long var8 = Math.max(1L, var4 / var6);
         return (int)Math.min((long)var3, Math.min(64L, var8));
      }
   }

   private List<Material> sellableShopMaterials() {
      ArrayList var1 = new ArrayList();
      ConfigurationSection var2 = this.getConfig().getConfigurationSection("shop.sell-prices");
      if (var2 == null) {
         return var1;
      } else {
         for (String var4 : var2.getKeys(false)) {
            Material var5 = Material.matchMaterial(var4);
            if (var5 != null && var5.isItem() && !var5.isAir()) {
               var1.add(var5);
            }
         }

         var1.sort((java.util.Comparator<Material>)(var0, var1x) -> var0.name().compareToIgnoreCase(var1x.name()));
         return var1;
      }
   }

   private long shopBaseUnitPrice(Material var1) {
      return Math.max(1L, this.getConfig().getLong("shop.sell-prices." + var1.name(), 1L));
   }

   private long shopUnitPrice(Material var1, Player var2) {
      long var3 = this.shopBaseUnitPrice(var1);
      double var5 = this.shopPrices.getOrDefault(var1, 1.0);
      double var7 = Math.max(0.05, Math.min(0.95, this.getConfig().getDouble("shop.minimum-multiplier", 0.5)));
      double var9 = Math.max(var7, Math.min(1.0, var5));
      return Math.max(1L, (long)Math.floor(var3 * var9));
   }

   private String shopPriceLine(long var1, int var3) {
      long var4 = this.safeMultiply(var1, var3);
      return var1 + " монет/шт (весь стак: " + var4 + ")";
   }

   private void buyShopLot(Player var1, ShopHolder var2, int var3, boolean var4) {
      Material var5 = var2.lots.get(var3);
      if (var5 == null) {
         var1.sendMessage(ChatColor.RED + "Товар больше недоступен.");
         this.openShop(var1, 0);
      } else {
         int var6 = var4 ? 1 : var2.stackSizes.getOrDefault(var3, 1);
         long var7 = this.shopUnitPrice(var5, var1);
         long var9 = this.safeMultiply(var7, var6);
         long var11 = this.getBalance(var1.getUniqueId());
         if (var11 < var9) {
            var1.sendMessage(ChatColor.RED + "Недостаточно монет. Нужно: " + var9 + ", баланс: " + var11);
         } else {
            this.setBalance(var1.getUniqueId(), var11 - var9);
            this.giveOrDrop(var1, this.amountOf(new ItemStack(var5), var6));
            Map var13 = this.shopPurchased.computeIfAbsent(var5, var0 -> new LinkedHashMap<>());
            int var14 = (Integer)var13.getOrDefault(var1.getUniqueId(), 0);
            var13.put(var1.getUniqueId(), var14 + var6);
            double var15 = Math.max(1.0, this.getConfig().getDouble("shop.saturation-units", 128.0));
            double var17 = Math.max(0.05, Math.min(0.95, this.getConfig().getDouble("shop.minimum-multiplier", 0.5)));
            double var19 = Math.min(1.0, (var14 + var6) / var15);
            double var21 = 1.0 - (1.0 - var17) * var19;
            this.shopPrices.put(var5, var21);
            this.saveShopState();
            var1.sendMessage(
               ChatColor.GREEN + "Куплено " + var6 + "× " + this.prettyName(var5) + " за " + var9 + " монет. Остаток: " + this.getBalance(var1.getUniqueId())
            );
            this.openShopRefresh(var1, var2);
         }
      }
   }

   private void openShopRefresh(Player var1, ShopHolder var2) {
      List var3 = this.sellableShopMaterials();
      Material var4 = null;

      for (int var5 = 0; var5 < 45; var5++) {
         var4 = var2.lots.get(var5);
         if (var4 != null) {
            break;
         }
      }

      int var7 = var4 == null ? 0 : var3.indexOf(var4);
      int var6 = var7 < 0 ? 0 : var7 / 45;
      this.openShop(var1, var6);
   }

   private void openAuction(Player var1) {
      AuctionHolder var2 = new AuctionHolder();
      long var3 = Math.max(1L, this.getConfig().getLong("auction.duration-minutes", 30L));
      var2.inventory = Bukkit.createInventory(var2, 54, ChatColor.DARK_AQUA + "Аукцион (" + this.auctions.size() + "/20 лотов)");
      int var5 = 0;

      for (AuctionLot var7 : this.auctions.values()) {
         if (var5 >= 45) {
            break;
         }

         if (!var7.expired()) {
            ItemStack var8 = var7.item.clone();
            ItemMeta var9 = var8.getItemMeta();
            if (var9 != null) {
               ArrayList var10 = new ArrayList();
               var10.add(ChatColor.GRAY + "Продавец: " + ChatColor.WHITE + var7.sellerName);
               var10.add(ChatColor.GRAY + "Мин. цена: " + ChatColor.GOLD + var7.minPrice + " монет");
               if (var7.topBidder != null) {
                  var10.add(ChatColor.GRAY + "Текущая ставка: " + ChatColor.GREEN + var7.topBid + " от " + var7.topBidderName);
               } else {
                  var10.add(ChatColor.GRAY + "Ставок пока нет");
               }

               long var11 = Math.max(0L, (var7.endsAtMillis - System.currentTimeMillis()) / 60000L);
               var10.add(ChatColor.GRAY + "Осталось: " + var11 + " мин.");
               if (var7.buyout > 0L) {
                  var10.add(ChatColor.GRAY + "Выкуп сразу: " + ChatColor.GOLD + var7.buyout + " монет");
               }
               var10.add(ChatColor.GREEN + "Предмет на хранении — продавец может быть не в сети");
               var10.add(ChatColor.YELLOW + "ЛКМ — ставка +10%, ПКМ — ставка +1");
               if (var7.buyout > 0L) {
                  var10.add(ChatColor.YELLOW + "Shift+клик — купить сразу");
               }
               var9.setLore(var10);
               var9.setDisplayName(ChatColor.AQUA + "Лот #" + var7.id + ": " + this.pretty(var8));
               var9.getPersistentDataContainer().set(new NamespacedKey(this, "auction_id"), PersistentDataType.INTEGER, var7.id);
               var8.setItemMeta(var9);
            }

            var2.slotToLot.put(var5, var7.id);
            var2.inventory.setItem(var5++, var8);
         }
      }

      var2.inventory
         .setItem(
            49,
            this.named(
               Material.HOPPER,
               ChatColor.GOLD + "Твой баланс: " + ChatColor.YELLOW + this.getBalance(var1.getUniqueId()) + " монет",
               List.of(
                  ChatColor.GRAY + "Выставление: /auction sell <мин. цена> [выкуп]",
                  ChatColor.GRAY + "Выигранные и возвращённые предметы: /storage",
                  ChatColor.GRAY + "Отмена своего лота: /auction cancel <id>",
                  ChatColor.GRAY + "Побеждает высшая ставка после " + var3 + " мин.",
                  ChatColor.GRAY + "Комиссия продавца: " + this.getConfig().getInt("auction.seller-fee-percent", 5) + "%"
               )
            )
         );
      if (!this.auctionViewers.contains(var1.getUniqueId())) {
         this.auctionViewers.add(var1.getUniqueId());
      }

      var1.openInventory(var2.inventory);
   }

   private void auctionSell(Player var1, long var2, long varBuyout) {
      if (varBuyout != 0L && (varBuyout < var2 || varBuyout > 1000000000L)) {
         var1.sendMessage(ChatColor.RED + "Цена выкупа должна быть не меньше минимальной цены (и не больше 1 000 000 000).");
         return;
      }
      if (var2 >= 1L && var2 <= 1000000000L) {
         if (this.auctions.size() >= 20) {
            var1.sendMessage(ChatColor.RED + "Доска аукциона заполнена (20 лотов).");
         } else {
            ItemStack var4 = var1.getInventory().getItemInMainHand();
            if (var4.getType().isAir()) {
               var1.sendMessage(ChatColor.RED + "Возьми продаваемый предмет в основную руку.");
            } else {
               long var5 = Math.max(1L, this.getConfig().getLong("auction.duration-minutes", 30L));
               ItemStack var7 = var4.clone();
               var1.getInventory().setItemInMainHand(null);
               var1.updateInventory();
               AuctionLot var8 = new AuctionLot(
                  this.nextAuctionId++, var1.getUniqueId(), var1.getName(), var7, var2, var5 * 60000L
               );
               var8.buyout = varBuyout;
               this.auctions.put(var8.id, var8);
               this.saveAuctions();
               if (varBuyout > 0L) {
                  var1.sendMessage(ChatColor.GRAY + "Цена мгновенного выкупа: " + varBuyout + " монет.");
               }
               var1.sendMessage(ChatColor.GRAY + "Предмет на хранении аукциона: лот купят и без тебя, монеты придут на баланс.");
               var1.sendMessage(
                  ChatColor.GREEN
                     + "Лот #"
                     + var8.id
                     + " выставлен: "
                     + var7.getAmount()
                     + "× "
                     + this.pretty(var7)
                     + " за "
                     + var2
                     + "+ монет на "
                     + var5
                     + " мин."
               );
            }
         }
      } else {
         var1.sendMessage(ChatColor.RED + "Минимальная цена должна быть от 1 до 1 000 000 000.");
      }
   }

   private void auctionCancel(Player var1, String var2) {
      int var3;
      try {
         var3 = Integer.parseInt(var2);
      } catch (NumberFormatException var6) {
         var1.sendMessage(ChatColor.RED + "ID лота должен быть числом.");
         return;
      }

      AuctionLot var4 = this.auctions.get(var3);
      if (var4 == null) {
         var1.sendMessage(ChatColor.RED + "Лот не найден.");
      } else if (!var4.seller.equals(var1.getUniqueId()) && !var1.hasPermission("serverutilities.admin")) {
         var1.sendMessage(ChatColor.RED + "Можно отменить только свой лот.");
      } else {
         this.auctions.remove(var3);
         this.saveAuctions();
         this.deliver(var4.seller, var4.item.clone(), "возврат снятого лота #" + var3);
         if (var4.topBidder != null) {
            this.setBalance(var4.topBidder, this.saturatingAdd(this.getBalance(var4.topBidder), var4.topBid));
            Player var5 = Bukkit.getPlayer(var4.topBidder);
            if (var5 != null) {
               var5.sendMessage(ChatColor.GREEN + "Аукцион #" + var3 + " отменён продавцом, ставка возвращена.");
            }

            this.saveBalances();
         }

         var1.sendMessage(ChatColor.GREEN + "Лот #" + var3 + " снят с аукциона, предмет возвращён.");
      }
   }

   private void placeBid(Player var1, AuctionLot var2, long var3) {
      if (var2.seller.equals(var1.getUniqueId())) {
         var1.sendMessage(ChatColor.RED + "Нельзя перебивать себя на собственном лоте.");
      } else {
         long var5 = Math.max(var2.minPrice, var2.topBid + 1L);
         if (var3 < var5) {
            var1.sendMessage(ChatColor.RED + "Минимальная ставка сейчас: " + var5 + " монет.");
         } else if (this.getBalance(var1.getUniqueId()) < var3) {
            var1.sendMessage(ChatColor.RED + "Недостаточно монет. Нужно: " + var3 + ", баланс: " + this.getBalance(var1.getUniqueId()));
         } else {
            this.setBalance(var1.getUniqueId(), this.getBalance(var1.getUniqueId()) - var3);
            if (var2.topBidder != null) {
               this.setBalance(var2.topBidder, this.saturatingAdd(this.getBalance(var2.topBidder), var2.topBid));
               Player var7 = Bukkit.getPlayer(var2.topBidder);
               if (var7 != null) {
                  var7.sendMessage(ChatColor.YELLOW + "Твою ставку на лот #" + var2.id + " перебили (" + var3 + " монет). Деньги возвращены.");
               }
            }

            var2.topBidder = var1.getUniqueId();
            var2.topBidderName = var1.getName();
            var2.topBid = var3;
            this.saveBalances();
            this.saveAuctions();
            var1.sendMessage(ChatColor.GREEN + "Ставка " + var3 + " монет на лот #" + var2.id + " принята!");
         }
      }
   }

   private void tickAuctions() {
      boolean var1 = false;

      for (AuctionLot var3 : new ArrayList<>(this.auctions.values())) {
         if (var3.expired()) {
            this.auctions.remove(var3.id);
            var1 = true;
            if (var3.topBidder != null) {
               // Победитель получает предмет всегда: в инвентарь, если он в сети, иначе в /storage.
               this.deliver(var3.topBidder, var3.item.clone(), "выигрыш аукциона #" + var3.id + " за " + var3.topBid + " монет");
            }

            if (var3.topBidder == null) {
               this.deliver(var3.seller, var3.item.clone(), "аукцион #" + var3.id + " завершился без ставок — предмет возвращён");
            } else {
               Player var12 = Bukkit.getPlayer(var3.seller);
               long var5 = Math.max(0L, Math.min(50L, this.getConfig().getLong("auction.seller-fee-percent", 5L)));
               long var7 = var3.topBid - this.safeMultiply(var3.topBid, var5) / 100L;
               this.setBalance(var3.seller, this.saturatingAdd(this.getBalance(var3.seller), var7));
               if (var12 != null) {
                  var12.sendMessage(ChatColor.GOLD + "Аукцион #" + var3.id + " завершён: +" + var7 + " монет (комиссия " + (var3.topBid - var7) + ").");
               }
            }
         }
      }

      if (var1) {
         this.saveAuctions();
         this.saveBalances();
      }

      for (UUID var10 : new ArrayList<>(this.auctionViewers)) {
         Player var13 = Bukkit.getPlayer(var10);
         if (var13 != null && var13.isOnline()) {
            Inventory var14 = var13.getOpenInventory().getTopInventory();
            if (var14.getHolder() instanceof AuctionHolder) {
               this.openAuction(var13);
            }
         } else {
            this.auctionViewers.remove(var10);
         }
      }

      this.flushPendingReturns();
   }

   @EventHandler
   public void onClick(InventoryClickEvent var1) {
      InventoryHolder var2 = var1.getView().getTopInventory().getHolder();
      if (var2 instanceof AuctionHolder var13) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
            if (var1.getWhoClicked() instanceof Player var18) {
               Integer var24 = var13.slotToLot.get(var1.getSlot());
               if (var24 != null) {
                  AuctionLot var25 = this.auctions.get(var24);
                  if (var25 != null && !var25.expired()) {
                     if (var1.isShiftClick()) {
                        this.buyNow(var18, var25);
                        this.openAuction(var18);
                        return;
                     }
                     long var26;
                     if (var1.isRightClick()) {
                        var26 = Math.max(var25.minPrice, var25.topBid + 1L);
                     } else {
                        var26 = Math.max(Math.max(var25.minPrice, var25.topBid + 1L), (long)Math.ceil(Math.max(var25.currentPrice(), var25.minPrice) * 1.1));
                     }

                     this.placeBid(var18, var25, var26);
                     this.openAuction(var18);
                  } else {
                     var18.sendMessage(ChatColor.RED + "Лот уже недоступен.");
                     this.openAuction(var18);
                  }
               }
            }
         }
      } else if (var2 instanceof BuyerHolder var12) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
            if (var1.getWhoClicked() instanceof Player var17) {
               this.sellBuyerLot(var17, var12, var1.getSlot());
            }
         }
      } else if (var2 instanceof ShopHolder var11) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
            if (var1.getWhoClicked() instanceof Player var16) {
               int var21 = var1.getSlot();
               if (var21 != 48 && var21 != 50) {
                  this.buyShopLot(var16, var11, var21, var1.isRightClick());
               } else {
                  this.openShop(var16, var21 == 48 ? this.currentPage(var11, false) : this.currentPage(var11, true));
               }
            }
         }
      } else if (var2 instanceof TradeHolder var10) {
         var1.setCancelled(true);
         if (var1.getWhoClicked() instanceof Player var15) {
            this.handleTradeClick(var15, var10, var1);
         }
      } else if (var2 instanceof ClanHolder var9) {
         var1.setCancelled(true);
      } else if (var2 instanceof StorageHolder varSh) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory() && var1.getWhoClicked() instanceof Player varSp) {
            this.handleStorageClick(varSp, varSh, var1.getSlot());
         }
      } else {
         if (var2 instanceof BoardHolder) {
            var1.setCancelled(true);
            if (var1.getClickedInventory() != var1.getView().getTopInventory()) {
               return;
            }

            if (!(var1.getWhoClicked() instanceof Player var3)) {
               return;
            }

            ItemStack var14 = var1.getCurrentItem();
            if (var14 == null || var14.getType().isAir()) {
               return;
            }

            ItemMeta var5 = var14.getItemMeta();
            if (var5 == null) {
               return;
            }

            Integer var6 = var5.getPersistentDataContainer().get(new NamespacedKey(this, "order_id"), PersistentDataType.INTEGER);
            if (var6 == null) {
               return;
            }

            Order var7 = this.orders.get(var6);
            if (var7 == null) {
               var3.sendMessage(ChatColor.RED + "Заказ уже недоступен.");
               this.openBoard(var3);
               return;
            }

            if (var7.owner.equals(var3.getUniqueId())) {
               var3.sendMessage(ChatColor.RED + "Нельзя выполнить собственный заказ.");
               return;
            }

            if (var7.escrow) {
               this.fulfillEscrowOrder(var3, var7);
               return;
            }

            Player var8 = Bukkit.getPlayer(var7.owner);
            if (var8 == null || !var8.isOnline()) {
               var3.sendMessage(ChatColor.RED + "Владелец заказа должен быть в сети для завершения обмена.");
               return;
            }

            if (var7.moneyAmount > 0L) {
               if (!this.hasItems(var3, var7.wanted)) {
                  var3.sendMessage(ChatColor.RED + "В инвентаре недостаточно нужных предметов.");
                  return;
               }

               if (this.getBalance(var8.getUniqueId()) < var7.moneyAmount) {
                  var3.sendMessage(ChatColor.RED + "У владельца больше недостаточно монет; заказ пока нельзя выполнить.");
                  return;
               }

               this.removeItems(var3, var7.wanted);
               this.setBalance(var8.getUniqueId(), this.getBalance(var8.getUniqueId()) - var7.moneyAmount);
               this.setBalance(var3.getUniqueId(), this.saturatingAdd(this.getBalance(var3.getUniqueId()), var7.moneyAmount));
               this.giveOrDrop(var8, var7.offered == null ? null : var7.offered.clone());
               var8.sendMessage(
                  ChatColor.GREEN + var3.getName() + " выполнил ваш заказ #" + var7.id + ". Предмет доставлен, списано " + var7.moneyAmount + " монет."
               );
               var3.sendMessage(ChatColor.GREEN + "Заказ #" + var7.id + " выполнен за " + var7.moneyAmount + " монет!");
            } else {
               if (var7.offered == null) {
                  var3.sendMessage(ChatColor.RED + "Заказ уже недоступен.");
                  return;
               }

               if (!this.hasItems(var3, var7.wanted) || !this.hasItems(var8, var7.offered)) {
                  var3.sendMessage(ChatColor.RED + "В инвентаре недостаточно предметов для обмена.");
                  return;
               }

               this.removeItems(var3, var7.wanted);
               this.removeItems(var8, var7.offered);
               this.giveOrDrop(var3, var7.offered.clone());
               this.giveOrDrop(var8, var7.wanted.clone());
               var8.sendMessage(ChatColor.GREEN + var3.getName() + " выполнил ваш заказ #" + var7.id + ". Предмет доставлен в инвентарь.");
               var3.sendMessage(ChatColor.GREEN + "Заказ #" + var7.id + " выполнен!");
            }

            this.orders.remove(var7.id);
            this.saveOrders();
            this.saveBalances();
            this.openBoard(var3);
         }
      }
   }

   private int currentPage(ShopHolder var1, boolean var2) {
      List var3 = this.sellableShopMaterials();
      Material var4 = null;

      for (int var5 = 0; var5 < 45; var5++) {
         var4 = var1.lots.get(var5);
         if (var4 != null) {
            break;
         }
      }

      int var8 = var4 == null ? 0 : var3.indexOf(var4);
      int var6 = var8 < 0 ? 0 : var8 / 45;
      int var7 = Math.max(1, (var3.size() + 44) / 45);
      var6 += var2 ? 1 : -1;
      if (var6 < 0) {
         var6 = 0;
      }

      if (var6 >= var7) {
         var6 = var7 - 1;
      }

      return var6;
   }

   @EventHandler
   public void onDrag(InventoryDragEvent var1) {
      InventoryHolder var2 = var1.getView().getTopInventory().getHolder();
      if (var2 instanceof BuyerHolder
         || var2 instanceof ShopHolder
         || var2 instanceof BoardHolder
         || var2 instanceof AuctionHolder
         || var2 instanceof TradeHolder
         || var2 instanceof ClanHolder
         || var2 instanceof StorageHolder) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onPreLogin(AsyncPlayerPreLoginEvent var1) {
      if (this.authEnabled()) {
         String var2 = var1.getName().toLowerCase(Locale.ROOT);
         if (!this.accounts.containsKey(var2)) {
            long var3 = Math.max(0L, this.getConfig().getLong("auth.register-grace-seconds", 60L));
            if (var3 <= 0L) {
               var1.disallow(Result.KICK_OTHER, "§cАккаунт не зарегистрирован. Администратор включил свободную регистрацию позже.");
            } else {
               synchronized (this.pendingRegistrations) {
                  this.pendingRegistrations.add(var2);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onJoin(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      this.flushPendingReturns();
      if (!this.authEnabled()) {
         this.authenticated.add(var2.getUniqueId());
         this.applyFrozen(var2, false);
         this.showBoard(var2);
         this.notifyStorage(var2);
      } else {
         String var3 = var2.getName().toLowerCase(Locale.ROOT);
         boolean var4 = !this.accounts.containsKey(var3);
         if (!var4) {
            synchronized (this.pendingRegistrations) {
               this.pendingRegistrations.remove(var3);
            }
         }

         this.authenticated.remove(var2.getUniqueId());
         this.applyFrozen(var2, true);
         if (var4) {
            long var8 = Math.max(30L, this.getConfig().getLong("auth.register-grace-seconds", 60L));
            var2.sendMessage(ChatColor.YELLOW + "С возвращением! Зарегистрируйся: /reg <пароль> <пароль>");
            var2.sendMessage(ChatColor.GRAY + "На это есть " + var8 + " секунд.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
               if (var2.isOnline() && !this.authenticated.contains(var2.getUniqueId())) {
                  var2.kickPlayer("§cТы не зарегистрировался вовремя.\n§eЗайди снова и используй /reg");
               }
            }, var8 * 20L);
         } else {
            var2.sendMessage(ChatColor.YELLOW + "Вход: /login <пароль>");
            this.startLoginTimeout(var2);
         }
      }
   }

   private void startLoginTimeout(Player var1) {
      long var2 = Math.max(10L, this.getConfig().getLong("auth.login-timeout-seconds", 90L));
      Bukkit.getScheduler().runTaskLater(this, () -> {
         if (var1.isOnline() && !this.authenticated.contains(var1.getUniqueId())) {
            var1.kickPlayer("§cВремя на вход истекло. Используй /login <пароль>");
         }
      }, var2 * 20L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      this.offlineNames.put(var2, var1.getPlayer().getName());
      this.authenticated.remove(var2);
      this.scoreboards.remove(var2);
      BukkitTask var3 = this.autoSellTasks.remove(var2);
      if (var3 != null) {
         var3.cancel();
      }

      TradeSession var4 = this.tradeOffers.remove(var2);
      if (var4 != null) {
         Player var5 = Bukkit.getPlayer(var4.other(var2));
         if (var5 != null) {
            var5.closeInventory();
            var5.sendMessage(ChatColor.RED + "Партнёр вышел — обмен отменён.");
         }

         this.abortTradeSession(var4);
      }

      TradeSession var7 = this.pendingTrades.remove(var2);
      if (var7 != null) {
         Player var6 = Bukkit.getPlayer(var7.other(var2));
         if (var6 != null) {
            var6.closeInventory();
            var6.sendMessage(ChatColor.RED + "Партнёр вышел — обмен отменён.");
         }

         this.abortTradeSession(var7);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onCommandPreprocess(PlayerCommandPreprocessEvent var1) {
      if (this.authEnabled()) {
         Player var2 = var1.getPlayer();
         if (!this.authenticated.contains(var2.getUniqueId())) {
            String var3 = var1.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
            if (!var3.equals("reg") && !var3.equals("register") && !var3.equals("login") && !var3.equals("auth") && !var3.equals("l")) {
               var1.setCancelled(true);
               Account var4 = this.accounts.get(var2.getName().toLowerCase(Locale.ROOT));
               var2.sendMessage(
                  var4 == null ? ChatColor.RED + "Сначала зарегистрируйся: /reg <пароль> <пароль>" : ChatColor.RED + "Сначала войди: /login <пароль>"
               );
            } else {
               if (var3.equals("l")) {
                  this.handleLogin(var2, var1.getMessage().substring(2).trim().isEmpty() ? new String[0] : var1.getMessage().substring(2).trim().split("\\s+"));
                  var1.setCancelled(true);
               }
            }
         }
      }
   }

   private void applyFrozen(Player var1, boolean var2) {
      if (this.getConfig().getBoolean("auth.freeze-until-login", true)) {
         try {
            var1.setInvulnerable(var2);
            var1.setSilent(var2);
            if (var2) {
               var1.setGameMode(GameMode.SPECTATOR);
            } else {
               GameMode var3 = this.lastGameModes.get(var1.getUniqueId());
               var1.setGameMode(var3 == null ? GameMode.SURVIVAL : var3);
            }
         } catch (Throwable var4) {
         }
      }
   }

   @EventHandler
   public void onGameModeChange(PlayerGameModeChangeEvent var1) {
      if (this.authenticated.contains(var1.getPlayer().getUniqueId())) {
         this.lastGameModes.put(var1.getPlayer().getUniqueId(), var1.getNewGameMode());
      }
   }

   private boolean authEnabled() {
      return this.getConfig().getBoolean("auth.enabled", true);
   }

   private boolean isAuthenticated(Player var1) {
      return !this.authEnabled() || this.authenticated.contains(var1.getUniqueId());
   }

   private boolean isAuthOk(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (this.isAuthenticated(var2)) {
            return true;
         } else {
            Account var3 = this.accounts.get(var2.getName().toLowerCase(Locale.ROOT));
            var2.sendMessage(
               var3 == null ? ChatColor.RED + "Сначала зарегистрируйся: /reg <пароль> <пароль>" : ChatColor.RED + "Сначала войди: /login <пароль>"
            );
            return false;
         }
      } else {
         return true;
      }
   }

   private void handleRegister(Player var1, String[] var2) {
      if (!this.authEnabled()) {
         var1.sendMessage(ChatColor.GRAY + "Регистрация отключена на этом сервере.");
      } else {
         String var3 = var1.getName().toLowerCase(Locale.ROOT);
         if (this.accounts.containsKey(var3)) {
            var1.sendMessage(ChatColor.RED + "Ты уже зарегистрирован. Используй /login <пароль>");
         } else if (var2.length < 1) {
            var1.sendMessage(ChatColor.RED + "Использование: /reg <пароль> <повтор пароля>");
         } else {
            String var4 = var2[0];
            if (var2.length >= 2 && !var4.equals(var2[1])) {
               var1.sendMessage(ChatColor.RED + "Пароли не совпадают.");
            } else {
               int var5 = Math.max(3, this.getConfig().getInt("auth.min-password-length", 4));
               if (var4.length() < var5) {
                  var1.sendMessage(ChatColor.RED + "Пароль должен быть не короче " + var5 + " символов.");
               } else {
                  String var6 = this.randomSalt();
                  String var7 = this.hashPassword(var6, var4);
                  this.accounts.put(var3, new Account(var1.getName(), var6, var7, System.currentTimeMillis()));
                  this.saveAccounts();
                  this.completeAuth(var1);
                  var1.sendMessage(ChatColor.GREEN + "Аккаунт создан! Добро пожаловать.");
               }
            }
         }
      }
   }

   private void handleLogin(Player var1, String[] var2) {
      if (!this.authEnabled()) {
         var1.sendMessage(ChatColor.GRAY + "Авторизация не требуется.");
      } else if (this.authenticated.contains(var1.getUniqueId())) {
         var1.sendMessage(ChatColor.GRAY + "Ты уже вошёл.");
      } else {
         String var3 = var1.getName().toLowerCase(Locale.ROOT);
         Account var4 = this.accounts.get(var3);
         if (var4 == null) {
            var1.sendMessage(ChatColor.RED + "Ты ещё не зарегистрирован. /reg <пароль> <пароль>");
         } else if (var2.length < 1) {
            var1.sendMessage(ChatColor.RED + "Использование: /login <пароль>");
         } else {
            int var5 = Math.max(1, this.getConfig().getInt("auth.max-login-tries", 5));
            int var6 = this.loginAttempts.merge(var3, 1, Integer::sum);
            if (this.hashPassword(var4.salt, var2[0]).equals(var4.hash)) {
               this.loginAttempts.remove(var3);
               this.completeAuth(var1);
               var1.sendMessage(ChatColor.GREEN + "Вход выполнен. Приятной игры!");
            } else {
               this.saveBalances();
               if (var6 >= var5) {
                  var1.kickPlayer("§cСлишком много неудачных попыток входа.");
               } else {
                  var1.sendMessage(ChatColor.RED + "Неверный пароль. Осталось попыток: " + (var5 - var6));
               }
            }
         }
      }
   }

   private void completeAuth(Player var1) {
      this.authenticated.add(var1.getUniqueId());
      this.applyFrozen(var1, false);
      this.showBoard(var1);
      this.flushPendingReturns();
      this.notifyStorage(var1);
   }

   private String randomSalt() {
      byte[] var1 = new byte[16];
      RANDOM.nextBytes(var1);
      return Base64.getEncoder().encodeToString(var1);
   }

   private String hashPassword(String var1, String var2) {
      try {
         MessageDigest var3 = MessageDigest.getInstance("SHA-256");
         byte[] var4 = var3.digest((var1 + var2).getBytes(StandardCharsets.UTF_8));
         return Base64.getEncoder().encodeToString(var4);
      } catch (Exception var5) {
         throw new IllegalStateException(var5);
      }
   }

   private void handlePay(Player var1, String[] var2) {
      if (var2.length < 2) {
         var1.sendMessage(ChatColor.RED + "Использование: /pay <игрок> <сумма>");
      } else {
         long var3;
         try {
            var3 = Long.parseLong(var2[1]);
         } catch (NumberFormatException var14) {
            var1.sendMessage(ChatColor.RED + "Сумма должна быть числом.");
            return;
         }

         if (var3 >= 1L && var3 <= 1000000000L) {
            Player var5 = Bukkit.getPlayerExact(var2[0]);
            if (var5 != null && var5.isOnline()) {
               if (var5.getUniqueId().equals(var1.getUniqueId())) {
                  var1.sendMessage(ChatColor.RED + "Нельзя перевести монеты самому себе.");
               } else if (!this.isAuthenticated(var5)) {
                  var1.sendMessage(ChatColor.RED + "Этому игроку нельзя переводить — он ещё не вошёл на сервер.");
               } else {
                  long var6 = this.getConfig().getLong("pay.fee-percent", 0L);
                  if (var6 < 0L) {
                     var6 = 0L;
                  }

                  if (var6 > 50L) {
                     var6 = 50L;
                  }

                  long var8 = var3 * var6 / 100L;
                  long var12 = this.getBalance(var1.getUniqueId());
                  if (var12 < var3 + var8) {
                     var1.sendMessage(
                        ChatColor.RED + "Недостаточно монет (нужно " + (var3 + var8) + (var8 > 0L ? ", включая комиссию " + var8 : "") + "). Баланс: " + var12
                     );
                  } else {
                     this.setBalance(var1.getUniqueId(), var12 - var3 - var8);
                     this.setBalance(var5.getUniqueId(), this.saturatingAdd(this.getBalance(var5.getUniqueId()), var3));
                     this.saveBalances();
                     var1.sendMessage(
                        ChatColor.GREEN
                           + "Переведено "
                           + var3
                           + " монет игроку "
                           + var5.getName()
                           + (var8 > 0L ? " (комиссия " + var8 + ")" : "")
                           + ". Твой баланс: "
                           + this.getBalance(var1.getUniqueId())
                     );
                     var5.sendMessage(ChatColor.GOLD + var1.getName() + " перевёл тебе " + var3 + " монет. Баланс: " + this.getBalance(var5.getUniqueId()));
                  }
               }
            } else {
               var1.sendMessage(ChatColor.RED + "Игрок \"" + var2[0] + "\" сейчас не в сети.");
            }
         } else {
            var1.sendMessage(ChatColor.RED + "Сумма перевода: от 1 до 1 000 000 000 монет.");
         }
      }
   }

   private void openTop(Player var1) {
      TopHolder var2 = new TopHolder();
      var2.inventory = Bukkit.createInventory(var2, 54, ChatColor.DARK_PURPLE + "Топ продавцов сервера");
      ArrayList var3 = new ArrayList();

      for (Entry var5 : this.soldByPlayer.entrySet()) {
         long var6 = 0L;

         for (int var9 : (Iterable<Integer>)(Iterable)(((Map)var5.getValue()).values())) {
            var6 += var9;
         }

         if (var6 > 0L) {
            var3.add(Map.entry((UUID)var5.getKey(), var6));
         }
      }

      var3.sort(Comparator.<Entry>comparingLong(var0 -> (Long)var0.getValue()).reversed());
      int var16 = 0;
      int var17 = Math.min(var3.size(), 10);

      for (int var18 = 0; var18 < var17; var18++) {
         Entry var7 = (Entry)var3.get(var18);
         String var19 = this.resolveName((UUID)var7.getKey());
         Material var20 = var18 == 0 ? Material.GOLD_BLOCK : (var18 == 1 ? Material.IRON_BLOCK : (var18 == 2 ? Material.COPPER_BLOCK : Material.PAPER));
         String var10 = "-";
         long var11 = 0L;
         Map var13 = this.soldByPlayer.get(var7.getKey());
         if (var13 != null) {
            for (Entry var15 : (Iterable<Entry>)(Iterable)(var13.entrySet())) {
               if (((Integer)var15.getValue()).intValue() > var11) {
                  var11 = ((Integer)var15.getValue()).intValue();
                  var10 = this.prettyName((Material)var15.getKey()) + " × " + var11;
               }
            }
         }

         ItemStack var21 = this.named(
            var20,
            ChatColor.AQUA + "#" + (var18 + 1) + " " + var19,
            List.of(ChatColor.GRAY + "Продано предметов: " + var7.getValue(), ChatColor.GRAY + "Чаще всего: " + var10)
         );
         var2.inventory.setItem(var16++, var21);
      }

      if (var17 == 0) {
         var2.inventory
            .setItem(
               13,
               this.named(
                  Material.BARRIER, ChatColor.GRAY + "Пока никто ничего не продал", List.of(ChatColor.GRAY + "Продавай предметы скупщику или через /autosell")
               )
            );
      }

      var2.inventory
         .setItem(
            49,
            this.named(
               Material.GOLD_INGOT,
               ChatColor.GOLD + "Твой баланс: " + ChatColor.YELLOW + this.getBalance(var1.getUniqueId()) + " монет",
               List.of(ChatColor.GRAY + "Твоё место в топе: " + this.myTopPlace(var1.getUniqueId()))
            )
         );
      var1.openInventory(var2.inventory);
   }

   private String myTopPlace(UUID var1) {
      ArrayList var2 = new ArrayList();
      long var3 = 0L;

      for (Entry var6 : this.soldByPlayer.entrySet()) {
         long var7 = 0L;

         for (int var10 : (Iterable<Integer>)(Iterable)(((Map)var6.getValue()).values())) {
            var7 += var10;
         }

         if (var7 > 0L) {
            if (((UUID)var6.getKey()).equals(var1)) {
               var3 = var7;
            }

            var2.add(var7);
         }
      }

      if (var3 <= 0L) {
         return "вне топа";
      } else {
         int var11 = 1;

         for (long var13 : (Iterable<Long>)(Iterable)(var2)) {
            if (var13 > var3) {
               var11++;
            }
         }

         return "#" + var11;
      }
   }

   private String resolveName(UUID var1) {
      Player var2 = Bukkit.getPlayer(var1);
      if (var2 != null) {
         return var2.getName();
      } else {
         String var3 = this.offlineNames.get(var1);
         if (var3 != null) {
            return var3;
         } else {
            try {
               OfflinePlayer var4 = Bukkit.getOfflinePlayer(var1);
               String var5 = var4.getName();
               return var5 == null ? var1.toString().substring(0, 8) : var5;
            } catch (Exception var6) {
               return var1.toString().substring(0, 8);
            }
         }
      }
   }

   private void recordSale(UUID var1, Material var2, int var3) {
      this.soldByPlayer.computeIfAbsent(var1, var0 -> new LinkedHashMap<>()).merge(var2, var3, Integer::sum);
      Player var4 = Bukkit.getPlayer(var1);
      if (var4 != null) {
         this.offlineNames.put(var1, var4.getName());
      }
   }

   private void toggleAutoSell(Player var1) {
      UUID var2 = var1.getUniqueId();
      BukkitTask var3 = this.autoSellTasks.get(var2);
      if (var3 != null) {
         var3.cancel();
         this.autoSellTasks.remove(var2);
         var1.sendMessage(ChatColor.YELLOW + "Автоскупщик остановлен.");
      } else {
         long var4 = Math.max(2L, this.getConfig().getLong("autosell.period-seconds", 5L));
         long var6 = Math.max(0L, this.getConfig().getLong("autosell.idle-stop-seconds", 120L));
         AutoSellHolder var8 = new AutoSellHolder(var1.getUniqueId());
         this.autoSellStates.put(var2, var8);
         this.autoSellTasks.put(var2, Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player var5 = Bukkit.getPlayer(var2);
            if (var5 != null && var5.isOnline() && this.isAuthenticated(var5)) {
               if (var6 > 0L && (System.currentTimeMillis() - var8.lastActivity) / 1000L > var6) {
                  var5.sendMessage(ChatColor.YELLOW + "Автоскупщик выключен из-за отсутствия активности.");
                  BukkitTask var7 = this.autoSellTasks.remove(var2);
                  this.autoSellStates.remove(var2);
                  if (var7 != null) {
                     var7.cancel();
                  }
               } else {
                  this.runAutoSellOnce(var5, var8);
               }
            } else {
               BukkitTask var6x = this.autoSellTasks.remove(var2);
               this.autoSellStates.remove(var2);
               if (var6x != null) {
                  var6x.cancel();
               }
            }
         }, var4 * 20L, var4 * 20L));
         var1.sendMessage(
            ChatColor.GREEN
               + "Автоскупщик запущен: каждые "
               + var4
               + " сек продаёт лучший предмет по текущему курсу"
               + (var6 > 0L ? ", стоп через " + var6 + " сек бездействия" : "")
               + "."
         );
         var1.sendMessage(ChatColor.GRAY + "Выбрать конкретный предмет: /autosell pick");
      }
   }

   private void runAutoSellOnce(Player var1, AutoSellHolder var2) {
      List var3 = this.sellableSlots(var1);
      if (!var3.isEmpty()) {
         long var4 = Math.max(1L, this.getConfig().getLong("autosell.item-change-every-seconds", 15L));
         boolean var6 = var2.selectedSlot >= 0 && var2.selectionExpiresAt > System.currentTimeMillis() && var3.contains(var2.selectedSlot);
         int var7;
         if (var6) {
            var7 = var2.selectedSlot;
         } else {
            var7 = this.pickBestSlot(var1, var3);
         }

         ItemStack var8 = this.copyTemplate(var1.getInventory().getItem(var7));
         if (var8 != null) {
            this.sellOneOf(var1, var8);
         }
      }
   }

   private List<Integer> sellableSlots(Player var1) {
      ArrayList var2 = new ArrayList();
      PlayerInventory var3 = var1.getInventory();
      ItemStack[] var4 = var3.getStorageContents();
      if (var4 != null) {
         for (int var5 = 0; var5 < var4.length; var5++) {
            ItemStack var6 = var4[var5];
            if (var6 != null && !var6.getType().isAir() && this.unitPrice(var6.getType()) >= 0L) {
               var2.add(var5);
            }
         }
      }

      return var2;
   }

   private int pickBestSlot(Player var1, List<Integer> var2) {
      int var3 = (Integer)var2.get(0);
      long var4 = -1L;

      for (int var7 : var2) {
         ItemStack var8 = var1.getInventory().getItem(var7);
         if (var8 != null) {
            long var9 = this.safeMultiply(this.unitPrice(var8.getType()), var8.getAmount());
            if (var9 > var4) {
               var4 = var9;
               var3 = var7;
            }
         }
      }

      return var3;
   }

   private ItemStack copyTemplate(ItemStack var1) {
      if (var1 != null && !var1.getType().isAir()) {
         ItemStack var2 = var1.clone();
         var2.setAmount(1);
         return var2;
      } else {
         return null;
      }
   }

   private void sellOneOf(Player var1, ItemStack var2) {
      Material var3 = var2.getType();
      long var4 = this.unitPrice(var3);
      if (var4 >= 0L) {
         int var6 = -1;
         ItemStack[] var7 = var1.getInventory().getStorageContents();

         for (int var8 = 0; var8 < var7.length; var8++) {
            ItemStack var9 = var7[var8];
            if (var9 != null && var9.getType() == var3 && this.isPlainStack(var9)) {
               var6 = var8;
               break;
            }
         }

         if (var6 >= 0) {
            ItemStack var10 = var7[var6].clone();
            var10.setAmount(1);
            var7[var6].setAmount(var7[var6].getAmount() - 1);
            if (var7[var6].getAmount() <= 0) {
               var7[var6] = null;
            }

            var1.getInventory().setStorageContents(var7);
            this.setBalance(var1.getUniqueId(), this.saturatingAdd(this.getBalance(var1.getUniqueId()), var4));
            MarketStats var11 = this.market
               .computeIfAbsent(var3, var0 -> new MarketStats());
            this.decay(var11);
            var11.pressure = var11.pressure
               + this.getConfig().getDouble("buyer.sell-pressure-weight", 1.0) * this.getConfig().getDouble("autosell.pressure-multiplier", 0.5);
            var11.totalSold = this.saturatingAdd(var11.totalSold, 1L);
            var11.lastUpdated = System.currentTimeMillis();
            this.recordSale(var1.getUniqueId(), var3, 1);
            this.saveBalances();
            this.saveMarket();
            this.saveSales();
            var1.sendMessage(
               ChatColor.DARK_GRAY
                  + "[Авто] "
                  + ChatColor.GREEN
                  + "−1× "
                  + this.prettyName(var3)
                  + " +"
                  + var4
                  + " монет ("
                  + this.getBalance(var1.getUniqueId())
                  + ")"
            );
         }
      }
   }

   private boolean isPlainStack(ItemStack var1) {
      if (!var1.hasItemMeta()) {
         return true;
      } else {
         ItemMeta var2 = var1.getItemMeta();
         return var2 == null || !var2.hasEnchants() && !var2.hasDisplayName() && !var2.hasLore();
      }
   }

   private void openAutoSellMenu(Player var1) {
      AutoSellMenuHolder var2 = new AutoSellMenuHolder();
      var2.inventory = Bukkit.createInventory(var2, 54, ChatColor.DARK_GREEN + "Автоскупщик: выбери предмет");
      List var3 = this.sellableSlots(var1);
      int var4 = 0;

      for (int var6 : (Iterable<Integer>)(Iterable)(var3)) {
         if (var4 >= 45) {
            break;
         }

         ItemStack var7 = var1.getInventory().getItem(var6);
         if (var7 != null) {
            ItemStack var8 = var7.clone();
            var8.setAmount(Math.min(1, var8.getMaxStackSize()));
            ItemMeta var9 = var8.getItemMeta();
            if (var9 != null) {
               long var10 = this.unitPrice(var8.getType());
               var9.setDisplayName(ChatColor.GREEN + this.pretty(var8));
               var9.setLore(List.of(ChatColor.GRAY + "Курс: " + var10 + " монет/шт", ChatColor.YELLOW + "ЛКМ — продавать этот предмет"));
               var8.setItemMeta(var9);
            }

            var2.slotToInvIndex.put(var4, var6);
            var2.inventory.setItem(var4++, var8);
         }
      }

      if (var4 == 0) {
         var2.inventory.setItem(13, this.named(Material.BARRIER, ChatColor.RED + "Нечего продавать", List.of()));
      }

      var1.openInventory(var2.inventory);
   }

   @EventHandler
   public void onAutoSellMenuClick(InventoryClickEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof AutoSellMenuHolder var2) {
         var1.setCancelled(true);
         if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
            if (var1.getWhoClicked() instanceof Player var6) {
               Integer var7 = var2.slotToInvIndex.get(var1.getSlot());
               if (var7 != null) {
                  AutoSellHolder var5 = this.autoSellStates.get(var6.getUniqueId());
                  if (var5 != null) {
                     var5.selectedSlot = var7;
                     var5.selectionExpiresAt = System.currentTimeMillis()
                        + Math.max(1L, this.getConfig().getLong("autosell.item-change-every-seconds", 15L)) * 1000L;
                     var5.lastActivity = System.currentTimeMillis();
                     var6.closeInventory();
                     var6.sendMessage(ChatColor.GREEN + "Автоскупщик теперь продаёт: " + this.prettyName(var6.getInventory().getItem(var7).getType()));
                  }
               }
            }
         }
      }
   }

   private void tickBoards() {
      for (Player var2 : Bukkit.getOnlinePlayers()) {
         this.updateBoard(var2);
      }
   }

   private void showBoard(Player var1) {
      this.updateBoard(var1);
   }

   private void updateBoard(Player var1) {
      if (this.getConfig().getBoolean("scoreboard.enabled", true)) {
         try {
            Scoreboard var2 = this.scoreboards.get(var1.getUniqueId());
            if (var2 == null) {
               var2 = Bukkit.getScoreboardManager().getNewScoreboard();
               Objective var3 = var2.registerNewObjective(
                  "tradeorders_money", Criteria.DUMMY, "" + ChatColor.GOLD + ChatColor.BOLD + "Баланс", RenderType.INTEGER
               );
               var3.setDisplaySlot(DisplaySlot.SIDEBAR);
               this.scoreboards.put(var1.getUniqueId(), var2);
            }

            Objective var8 = var2.getObjective("tradeorders_money");
            if (var8 == null) {
               this.scoreboards.remove(var1.getUniqueId());
               return;
            }

            var8.getScore(ChatColor.YELLOW + "Монеты:").setScore(3);
            var8.getScore("" + ChatColor.WHITE + this.getBalance(var1.getUniqueId())).setScore(2);
            Object var4 = this.getConfig().getString("scoreboard.footer", "/shop /auction /top");
            var8.getScore("" + ChatColor.GRAY + var4).setScore(1);

            for (String var6 : new ArrayList<>(var2.getEntries())) {
               if (var2.getObjective("tradeorders_money") == null) {
                  break;
               }

               if (var8.getScore(var6) != null
                  && !var6.startsWith(ChatColor.YELLOW + "Монеты")
                  && !var6.startsWith("" + ChatColor.GRAY + var4)
                  && !var6.matches("\\d+")) {
               }
            }

            var1.setScoreboard(var2);
         } catch (Throwable var7) {
         }
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent var1) {
      if (var1.getInventory().getHolder() instanceof AuctionHolder) {
         this.auctionViewers.remove(var1.getPlayer().getUniqueId());
      }

      if (var1.getInventory().getHolder() instanceof TradeHolder var2) {
         if (!(var1.getPlayer() instanceof Player var5)) {
            return;
         }

         TradeSession var6 = this.pendingTrades.get(var5.getUniqueId());
         if (var6 != null
            && (
               var5.getOpenInventory().getTopInventory() == null
                  || !(var5.getOpenInventory().getTopInventory().getHolder() instanceof TradeHolder)
            )) {
            this.cancelTrade(var5, var6, true);
         }
      }
   }

   /** Переносит старые «отложенные возвраты» аукциона (до 1.4.0) в личное хранилище. */
   private void flushPendingReturns() {
      if (!this.pendingReturns.isEmpty()) {
         for (Entry<UUID, List<ItemStack>> varE : new ArrayList<>(this.pendingReturns.entrySet())) {
            for (ItemStack varIt : varE.getValue()) {
               if (varIt != null && !varIt.getType().isAir()) {
                  this.storage.computeIfAbsent(varE.getKey(), k -> new ArrayList<>()).add(varIt.clone());
               }
            }
         }

         this.pendingReturns.clear();
         this.saveStorage();
         this.savePendingReturns();
      }
   }

   // ===================== Хранилище (/storage) =====================

   private File storageFile() {
      return new File(this.getDataFolder(), "storage.yml");
   }

   private void loadStorage() {
      this.storage.clear();
      File varF = this.storageFile();
      if (!varF.exists()) {
         return;
      }
      YamlConfiguration varY = YamlConfiguration.loadConfiguration(varF);
      ConfigurationSection varS = varY.getConfigurationSection("players");
      if (varS == null) {
         return;
      }
      for (String varK : varS.getKeys(false)) {
         try {
            UUID varU = UUID.fromString(varK);
            List<?> varL = varS.getList(varK);
            List<ItemStack> varItems = new ArrayList<>();
            if (varL != null) {
               for (Object varO : varL) {
                  if (varO instanceof ItemStack varIs && !varIs.getType().isAir()) {
                     varItems.add(varIs);
                  }
               }
            }
            if (!varItems.isEmpty()) {
               this.storage.put(varU, varItems);
            }
         } catch (Exception varEx) {
            this.getLogger().warning("Skipping invalid storage entry " + varK + ": " + varEx.getMessage());
         }
      }
   }

   private void saveStorage() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }
         YamlConfiguration varY = new YamlConfiguration();
         for (Entry<UUID, List<ItemStack>> varE : this.storage.entrySet()) {
            if (!varE.getValue().isEmpty()) {
               varY.set("players." + varE.getKey(), new ArrayList<>(varE.getValue()));
            }
         }
         varY.save(this.storageFile());
      } catch (Exception varEx) {
         this.getLogger().severe("Could not save storage: " + varEx.getMessage());
      }
   }

   private int storageCount(UUID varU) {
      List<ItemStack> varL = this.storage.get(varU);
      return varL == null ? 0 : varL.size();
   }

   /**
    * Доставка предмета игроку: если он в сети и вошёл в аккаунт — в инвентарь,
    * всё, что не поместилось (или если игрок не в сети), — в личное хранилище. Ничего не выбрасывается на землю.
    */
   private void deliver(UUID varU, ItemStack varItem, String varReason) {
      if (varItem == null || varItem.getType().isAir()) {
         return;
      }
      Player varP = Bukkit.getPlayer(varU);
      if (varP != null && varP.isOnline() && this.isAuthenticated(varP)) {
         Map<Integer, ItemStack> varLeft = varP.getInventory().addItem(varItem.clone());
         varP.updateInventory();
         if (varLeft.isEmpty()) {
            varP.sendMessage(ChatColor.GREEN + "Получено: " + this.amountName(varItem) + " (" + varReason + ").");
            return;
         }
         for (ItemStack varRest : varLeft.values()) {
            this.storage.computeIfAbsent(varU, k -> new ArrayList<>()).add(varRest);
         }
         this.saveStorage();
         varP.sendMessage(ChatColor.YELLOW + "Инвентарь полон: " + this.amountName(varItem) + " (" + varReason + ") отправлено в /storage.");
         return;
      }
      this.storage.computeIfAbsent(varU, k -> new ArrayList<>()).add(varItem.clone());
      this.saveStorage();
      if (varP != null && varP.isOnline()) {
         varP.sendMessage(ChatColor.YELLOW + this.amountName(varItem) + " (" + varReason + ") отправлено в /storage.");
      }
   }

   private void notifyStorage(Player varP) {
      int varN = this.storageCount(varP.getUniqueId());
      if (varN > 0) {
         varP.sendMessage(ChatColor.GOLD + "В твоём хранилище есть предметы (" + varN + " стак(ов)) — забери их: " + ChatColor.YELLOW + "/storage");
      }
   }

   private void openStorage(Player varP, int varPage) {
      List<ItemStack> varL = this.storage.getOrDefault(varP.getUniqueId(), List.of());
      int varPages = Math.max(1, (varL.size() + 44) / 45);
      varPage = Math.max(0, Math.min(varPage, varPages - 1));
      StorageHolder varH = new StorageHolder();
      varH.page = varPage;
      varH.inventory = Bukkit.createInventory(varH, 54, ChatColor.DARK_GREEN + "Хранилище (" + (varPage + 1) + "/" + varPages + ")");
      for (int varI = 0; varI < 45; varI++) {
         int varIdx = varPage * 45 + varI;
         if (varIdx >= varL.size()) {
            break;
         }
         varH.inventory.setItem(varI, varL.get(varIdx).clone());
      }
      ItemStack varPane = this.named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.RESET + " ", List.of());
      for (int varI = 45; varI < 54; varI++) {
         varH.inventory.setItem(varI, varPane);
      }
      if (varPage > 0) {
         varH.inventory.setItem(45, this.named(Material.ARROW, ChatColor.YELLOW + "← Назад", List.of()));
      }
      if (varPage < varPages - 1) {
         varH.inventory.setItem(53, this.named(Material.ARROW, ChatColor.YELLOW + "Вперёд →", List.of()));
      }
      varH.inventory.setItem(49, this.named(Material.CHEST, ChatColor.GOLD + "Забрать всё", List.of(
         ChatColor.GRAY + "Предметов в хранилище: " + varL.size(),
         ChatColor.GRAY + "Сюда приходят предметы по заказам, выигрыши",
         ChatColor.GRAY + "и возвраты аукциона, пока ты был не в сети.",
         ChatColor.YELLOW + "Клик по предмету — забрать его")));
      varP.openInventory(varH.inventory);
   }

   /** Забрать предмет из хранилища по индексу. Возвращает false, если в инвентаре нет места. */
   private boolean takeFromStorage(Player varP, int varIdx) {
      List<ItemStack> varL = this.storage.get(varP.getUniqueId());
      if (varL == null || varIdx < 0 || varIdx >= varL.size()) {
         return true;
      }
      ItemStack varItem = varL.remove(varIdx);
      Map<Integer, ItemStack> varLeft = varP.getInventory().addItem(varItem.clone());
      boolean varOk = varLeft.isEmpty();
      if (!varOk) {
         ItemStack varRest = varLeft.values().iterator().next();
         varL.add(varIdx, varRest);
      }
      if (varL.isEmpty()) {
         this.storage.remove(varP.getUniqueId());
      }
      this.saveStorage();
      varP.updateInventory();
      return varOk;
   }

   private void handleStorageClick(Player varP, StorageHolder varH, int varSlot) {
      if (varSlot == 45) {
         this.openStorage(varP, varH.page - 1);
         return;
      }
      if (varSlot == 53) {
         this.openStorage(varP, varH.page + 1);
         return;
      }
      if (varSlot == 49) {
         int varTaken = 0;
         while (this.storageCount(varP.getUniqueId()) > 0) {
            int varBefore = this.storageCount(varP.getUniqueId());
            boolean varOk = this.takeFromStorage(varP, 0);
            if (!varOk) {
               break;
            }
            varTaken += varBefore - this.storageCount(varP.getUniqueId());
         }
         if (this.storageCount(varP.getUniqueId()) > 0) {
            varP.sendMessage(ChatColor.YELLOW + "Инвентарь заполнен — часть предметов осталась в хранилище.");
         } else if (varTaken > 0) {
            varP.sendMessage(ChatColor.GREEN + "Все предметы забраны из хранилища.");
         }
         this.openStorage(varP, varH.page);
         return;
      }
      if (varSlot >= 0 && varSlot < 45) {
         if (!this.takeFromStorage(varP, varH.page * 45 + varSlot)) {
            varP.sendMessage(ChatColor.RED + "Нет места в инвентаре.");
         }
         this.openStorage(varP, varH.page);
      }
   }

   // ===================== Заказы с оплатой на хранении =====================

   private void fulfillEscrowOrder(Player varBuyer, Order varO) {
      if (!this.hasItems(varBuyer, varO.wanted)) {
         varBuyer.sendMessage(ChatColor.RED + "В инвентаре недостаточно нужных предметов: " + this.amountName(varO.wanted) + ".");
         return;
      }
      if (varO.moneyAmount <= 0L && varO.offered == null) {
         varBuyer.sendMessage(ChatColor.RED + "Заказ уже недоступен.");
         return;
      }
      // Сначала снимаем заказ с доски, чтобы его нельзя было выполнить дважды.
      this.orders.remove(varO.id);
      this.removeItems(varBuyer, varO.wanted);
      varBuyer.updateInventory();
      if (varO.moneyAmount > 0L) {
         this.setBalance(varBuyer.getUniqueId(), this.saturatingAdd(this.getBalance(varBuyer.getUniqueId()), varO.moneyAmount));
         varBuyer.sendMessage(ChatColor.GREEN + "Заказ #" + varO.id + " выполнен: +" + varO.moneyAmount + " монет!");
      } else {
         this.deliver(varBuyer.getUniqueId(), varO.offered.clone(), "оплата за заказ #" + varO.id);
         varBuyer.sendMessage(ChatColor.GREEN + "Заказ #" + varO.id + " выполнен!");
      }
      this.deliver(varO.owner, varO.wanted.clone(), "заказ #" + varO.id + " выполнил " + varBuyer.getName());
      this.saveOrders();
      this.saveBalances();
      this.openBoard(varBuyer);
   }

   // ===================== Мгновенный выкуп лота =====================

   private void buyNow(Player varP, AuctionLot varLot) {
      if (varLot.buyout <= 0L) {
         varP.sendMessage(ChatColor.RED + "У этого лота нет цены выкупа — делай ставки (ЛКМ/ПКМ).");
         return;
      }
      if (varLot.seller.equals(varP.getUniqueId())) {
         varP.sendMessage(ChatColor.RED + "Нельзя купить собственный лот.");
         return;
      }
      long varPrice = Math.max(varLot.buyout, varLot.topBid);
      long varOwnBid = varP.getUniqueId().equals(varLot.topBidder) ? varLot.topBid : 0L;
      if (this.getBalance(varP.getUniqueId()) + varOwnBid < varPrice) {
         varP.sendMessage(ChatColor.RED + "Недостаточно монет. Нужно: " + varPrice + ", баланс: " + this.getBalance(varP.getUniqueId()));
         return;
      }
      this.auctions.remove(varLot.id);
      // Возвращаем ставку предыдущему лидеру (в том числе самому покупателю).
      if (varLot.topBidder != null) {
         this.setBalance(varLot.topBidder, this.saturatingAdd(this.getBalance(varLot.topBidder), varLot.topBid));
         Player varPrev = Bukkit.getPlayer(varLot.topBidder);
         if (varPrev != null && !varPrev.getUniqueId().equals(varP.getUniqueId())) {
            varPrev.sendMessage(ChatColor.YELLOW + "Лот #" + varLot.id + " выкупил " + varP.getName() + " — твоя ставка возвращена.");
         }
      }
      this.setBalance(varP.getUniqueId(), this.getBalance(varP.getUniqueId()) - varPrice);
      long varFeePct = Math.max(0L, Math.min(50L, this.getConfig().getLong("auction.seller-fee-percent", 5L)));
      long varToSeller = varPrice - this.safeMultiply(varPrice, varFeePct) / 100L;
      this.setBalance(varLot.seller, this.saturatingAdd(this.getBalance(varLot.seller), varToSeller));
      Player varSeller = Bukkit.getPlayer(varLot.seller);
      if (varSeller != null) {
         varSeller.sendMessage(ChatColor.GOLD + "Лот #" + varLot.id + " выкуплен игроком " + varP.getName() + ": +" + varToSeller + " монет (комиссия " + (varPrice - varToSeller) + ").");
      }
      this.deliver(varP.getUniqueId(), varLot.item.clone(), "выкуп лота #" + varLot.id + " за " + varPrice + " монет");
      this.saveAuctions();
      this.saveBalances();
   }

   @EventHandler
   public void onMobKill(EntityDeathEvent var1) {
      Player var2 = var1.getEntity().getKiller();
      if (var2 != null) {
         if (this.isAuthenticated(var2)) {
            long var3 = this.getConfig().getLong("mob-rewards." + var1.getEntityType().name(), 0L);
            if (var3 > 0L) {
               this.setBalance(var2.getUniqueId(), this.saturatingAdd(this.getBalance(var2.getUniqueId()), var3));
               this.saveBalances();
               var2.sendMessage(ChatColor.GOLD + "+" + var3 + " монет за убийство моба. Баланс: " + this.getBalance(var2.getUniqueId()));
            }
         }
      }
   }

   private long unitPrice(Material var1) {
      double var2 = this.autoBasePrice(var1);
      if (var2 < 0.0) {
         return -1L;
      } else {
         MarketStats var4 = this.market.get(var1);
         if (var4 == null) {
            return Math.max(1L, (long)Math.floor(var2));
         } else {
            this.decay(var4);
            double var5 = Math.max(1.0, this.getConfig().getDouble("buyer.saturation-units", 100.0));
            double var7 = Math.max(0.05, Math.min(0.95, this.getConfig().getDouble("buyer.minimum-multiplier", 0.2)));
            double var9 = Math.min(1.0, var4.pressure / var5);
            double var11 = Math.max(var7, 1.0 - (1.0 - var7) * var9);
            return Math.max(1L, (long)Math.floor(var2 * var11));
         }
      }
   }

   private double autoBasePrice(Material var1) {
      if (var1 != null && !var1.isAir() && var1.isItem()) {
         double var2 = this.getConfig().getDouble("buyer.base-prices." + var1.name(), -1.0);
         if (var2 >= 0.0) {
            return var2;
         } else if (this.isJunkLike(var1)) {
            return 0.5;
         } else if (var1.name().contains("ENCHANTED") || var1 == Material.ENCHANTED_BOOK) {
            return 12.0;
         } else if (var1 == Material.NETHERITE_INGOT || var1 == Material.NETHERITE_SCRAP) {
            return 40.0;
         } else if (var1 == Material.DIAMOND_BLOCK) {
            return 135.0;
         } else if (var1 == Material.EMERALD_BLOCK) {
            return 90.0;
         } else if (var1 == Material.GOLD_BLOCK) {
            return 36.0;
         } else if (var1 == Material.IRON_BLOCK) {
            return 27.0;
         } else if (var1.name().endsWith("_BLOCK")) {
            return 9.0;
         } else if (var1.name().contains("SHULKER") || var1.name().contains("DRAGON_BREATH") || var1.name().contains("TOTEM")) {
            return 20.0;
         } else if (var1.name().contains("AMETHYST") || var1.name().contains("PRISMARINE") || var1.name().contains("OBSIDIAN")) {
            return 4.0;
         } else if (var1.name().contains("MUSIC_DISC")) {
            return 15.0;
         } else if (var1.name().startsWith("GOLDEN_") || var1.name().startsWith("DIAMOND_") || var1.name().startsWith("NETHERITE_")) {
            return 8.0;
         } else if (var1.name().startsWith("IRON_")) {
            return 5.0;
         } else if (var1.name().startsWith("STONE_")) {
            return 1.5;
         } else if (var1.name().startsWith("WOODEN_")) {
            return 1.2;
         } else if (var1.name().contains("POTION")) {
            return 6.0;
         } else if (var1.name().contains("SPAWN_EGG")) {
            return 50.0;
         } else if (var1.name().contains("BUCKET") && var1 != Material.BUCKET) {
            return 6.0;
         } else if (var1.name().contains("BED") || var1.name().contains("_BOAT") || var1.name().contains("MINECART")) {
            return 4.0;
         } else if (this.isFoodLike(var1)) {
            return 2.0;
         } else {
            return !var1.name().contains("SEEDS")
                  && !var1.name().contains("SAPLING")
                  && !var1.name().contains("_SNOWBALL")
                  && var1 != Material.SNOWBALL
                  && var1 != Material.STICK
                  && var1 != Material.FEATHER
                  && var1 != Material.LEATHER
                  && var1 != Material.CLAY_BALL
               ? 2.0
               : 1.0;
         }
      } else {
         return -1.0;
      }
   }

   private boolean isJunkLike(Material var1) {
      String var2 = var1.name();
      return var2.equals("DEAD_BUSH")
         || var2.equals("COBWEB")
         || var2.equals("ROTTEN_FLESH")
         || var2.equals("POISONOUS_POTATO")
         || var2.equals("STICK")
         || var2.equals("FEATHER")
         || var2.equals("FLINT")
         || var2.equals("STRING");
   }

   private boolean isFoodLike(Material var1) {
      try {
         return var1.isEdible();
      } catch (Throwable var3) {
         return false;
      }
   }

   private void decay(MarketStats var1) {
      long var2 = System.currentTimeMillis();
      double var4 = Math.max(0.1, this.getConfig().getDouble("buyer.half-life-hours", 6.0));
      double var6 = (var2 - var1.lastUpdated) / 3600000.0;
      if (var6 > 0.0) {
         var1.pressure = var1.pressure * Math.pow(0.5, Math.min(var6 / var4, 100.0));
      }

      var1.lastUpdated = var2;
   }

   private long safeMultiply(long var1, long var3) {
      try {
         return Math.multiplyExact(var1, var3);
      } catch (ArithmeticException var6) {
         return Long.MAX_VALUE;
      }
   }

   private boolean hasItems(Player var1, ItemStack var2) {
      return this.hasItems(var1, var2, var2.getAmount());
   }

   private boolean hasItems(Player var1, ItemStack var2, int var3) {
      int var4 = this.countSimilar(var1, var2);
      return var4 >= var3;
   }

   private void removeItems(Player var1, ItemStack var2) {
      int var3 = var2.getAmount();
      var3 = this.removeFromArray(var1.getInventory().getStorageContents(), var2, var3);
      var1.getInventory().setStorageContents(this.safeContents(var1.getInventory().getStorageContents()));
      ItemStack[] var4 = var1.getInventory().getArmorContents();
      int var5 = this.removeFromArray(var4, var2, var3);
      if (var5 != var3) {
         var1.getInventory().setArmorContents(var4);
         var3 = var5;
      }

      ItemStack var6 = var1.getInventory().getItemInOffHand();
      if (var3 > 0 && var6 != null && var6.isSimilar(var2)) {
         int var7 = Math.min(var3, var6.getAmount());
         var6.setAmount(var6.getAmount() - var7);
         var1.getInventory().setItemInOffHand(var6.getAmount() <= 0 ? null : var6);
         var3 -= var7;
      }

      if (var3 > 0) {
         var1.updateInventory();
      }
   }

   private int removeFromArray(ItemStack[] var1, ItemStack var2, int var3) {
      if (var1 == null) {
         return var3;
      } else {
         for (int var4 = 0; var4 < var1.length && var3 > 0; var4++) {
            ItemStack var5 = var1[var4];
            if (var5 != null && !var5.getType().isAir() && var5.isSimilar(var2)) {
               int var6 = Math.min(var3, var5.getAmount());
               var5.setAmount(var5.getAmount() - var6);
               if (var5.getAmount() <= 0) {
                  var1[var4] = null;
               }

               var3 -= var6;
            }
         }

         return var3;
      }
   }

   private ItemStack[] safeContents(ItemStack[] var1) {
      return var1 == null ? new ItemStack[0] : var1;
   }

   private void giveOrDrop(Player var1, ItemStack var2) {
      if (var2 != null && !var2.getType().isAir()) {
         HashMap var3 = var1.getInventory().addItem(new ItemStack[]{var2});

         for (ItemStack var5 : (Iterable<ItemStack>)(Iterable)(var3.values())) {
            var1.getWorld().dropItemNaturally(var1.getLocation(), var5);
         }

         var1.updateInventory();
      }
   }

   private String amountName(ItemStack var1) {
      return var1 == null ? "" : var1.getAmount() + "× " + this.pretty(var1);
   }

   private String pretty(ItemStack var1) {
      if (var1 == null) {
         return "";
      } else {
         ItemMeta var2 = var1.getItemMeta();
         return var2 != null && var2.hasDisplayName() ? ChatColor.stripColor(var2.getDisplayName()) : this.prettyName(var1.getType());
      }
   }

   private String prettyName(Material var1) {
      String[] var2 = var1.name().toLowerCase().split("_");
      StringBuilder var3 = new StringBuilder();

      for (String var7 : var2) {
         if (!var7.isEmpty()) {
            if (var3.length() > 0) {
               var3.append(' ');
            }

            var3.append(Character.toUpperCase(var7.charAt(0))).append(var7.substring(1));
         }
      }

      return var3.toString();
   }

   private ItemStack named(Material var1, String var2, List<String> var3) {
      ItemStack var4 = new ItemStack(var1);
      ItemMeta var5 = var4.getItemMeta();
      if (var5 != null) {
         var5.setDisplayName(var2);
         var5.setLore(var3);
         var4.setItemMeta(var5);
      }

      return var4;
   }

   private void loadOrders() {
      this.orders.clear();
      if (this.ordersFile.exists()) {
         YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.ordersFile);
         this.nextId = var1.getInt("next-id", 1);
         ConfigurationSection var2 = var1.getConfigurationSection("orders");
         if (var2 != null) {
            for (String var4 : var2.getKeys(false)) {
               try {
                  int var5 = Integer.parseInt(var4);
                  ConfigurationSection var6 = var2.getConfigurationSection(var4);
                  if (var6 != null) {
                     UUID var7 = UUID.fromString(var6.getString("owner"));
                     String var8 = var6.getString("owner-name", "Unknown");
                     ItemStack var9 = var6.getItemStack("wanted");
                     ItemStack var10 = var6.getItemStack("offered");
                     long var11 = var6.getLong("money", 0L);
                     if (var9 != null && !var9.getType().isAir()) {
                        Order varOrd = new Order(var5, var7, var8, var9, var10, var11);
                        varOrd.escrow = var6.getBoolean("escrow", false);
                        this.orders.put(var5, varOrd);
                     }
                  }
               } catch (Exception var13) {
                  this.getLogger().warning("Skipping invalid order " + var4 + ": " + var13.getMessage());
               }
            }
         }
      }
   }

   private void saveOrders() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();
         var1.set("next-id", this.nextId);

         for (Order var3 : this.orders.values()) {
            String var4 = "orders." + var3.id + ".";
            var1.set(var4 + "owner", var3.owner.toString());
            var1.set(var4 + "owner-name", var3.ownerName);
            var1.set(var4 + "wanted", var3.wanted);
            if (var3.offered != null) {
               var1.set(var4 + "offered", var3.offered);
            }

            var1.set(var4 + "money", var3.moneyAmount);
            var1.set(var4 + "escrow", var3.escrow);
         }

         var1.save(this.ordersFile);
      } catch (Exception var5) {
         this.getLogger().severe("Could not save orders: " + var5.getMessage());
      }
   }

   private File balancesFile() {
      return new File(this.getDataFolder(), "balances.yml");
   }

   private long getBalance(UUID var1) {
      return this.balances.getOrDefault(var1, 0L);
   }

   private long saturatingAdd(long var1, long var3) {
      try {
         return Math.addExact(var1, var3);
      } catch (ArithmeticException var6) {
         return Long.MAX_VALUE;
      }
   }

   private void setBalance(UUID var1, long var2) {
      this.balances.put(var1, var2);
   }

   private void loadBalances() {
      this.balances.clear();
      File var1 = this.balancesFile();
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         ConfigurationSection var3 = var2.getConfigurationSection("balances");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               try {
                  this.balances.put(UUID.fromString(var5), var3.getLong(var5));
               } catch (Exception var7) {
                  this.getLogger().warning("Skipping invalid balance UUID: " + var5);
               }
            }
         }
      }
   }

   private void saveBalances() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();

         for (Entry var3 : this.balances.entrySet()) {
            var1.set("balances." + var3.getKey(), var3.getValue());
         }

         var1.save(this.balancesFile());
      } catch (Exception var4) {
         this.getLogger().severe("Could not save balances: " + var4.getMessage());
      }
   }

   private File marketFile() {
      return new File(this.getDataFolder(), "market.yml");
   }

   private void loadMarket() {
      this.market.clear();
      File var1 = this.marketFile();
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         ConfigurationSection var3 = var2.getConfigurationSection("items");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               Material var6 = Material.matchMaterial(var5);
               if (var6 == null) {
                  this.getLogger().warning("Skipping invalid market item: " + var5);
               } else {
                  ConfigurationSection var7 = var3.getConfigurationSection(var5);
                  if (var7 != null) {
                     MarketStats var8 = new MarketStats();
                     var8.pressure = var7.getDouble("pressure", 0.0);
                     var8.totalSold = var7.getLong("total-sold", 0L);
                     var8.lastUpdated = var7.getLong("updated", System.currentTimeMillis());
                     this.market.put(var6, var8);
                  }
               }
            }
         }
      }
   }

   private void saveMarket() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();

         for (Entry var3 : this.market.entrySet()) {
            String var4 = "items." + ((Material)var3.getKey()).name() + ".";
            var1.set(var4 + "pressure", ((MarketStats)var3.getValue()).pressure);
            var1.set(var4 + "total-sold", ((MarketStats)var3.getValue()).totalSold);
            var1.set(var4 + "updated", ((MarketStats)var3.getValue()).lastUpdated);
         }

         var1.save(this.marketFile());
      } catch (Exception var5) {
         this.getLogger().severe("Could not save market: " + var5.getMessage());
      }
   }

   private File shopFile() {
      return new File(this.getDataFolder(), "shop.yml");
   }

   private void loadShopState() {
      this.shopPrices.clear();
      this.shopPurchased.clear();
      File var1 = this.shopFile();
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         ConfigurationSection var3 = var2.getConfigurationSection("prices");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               Material var6 = Material.matchMaterial(var5);
               if (var6 != null) {
                  this.shopPrices.put(var6, var3.getDouble(var5, 1.0));
               }
            }
         }

         ConfigurationSection var14 = var2.getConfigurationSection("purchases");
         if (var14 != null) {
            for (String var16 : var14.getKeys(false)) {
               Material var7 = Material.matchMaterial(var16);
               if (var7 != null) {
                  ConfigurationSection var8 = var14.getConfigurationSection(var16);
                  if (var8 != null) {
                     LinkedHashMap var9 = new LinkedHashMap();

                     for (String var11 : var8.getKeys(false)) {
                        try {
                           var9.put(UUID.fromString(var11), var8.getInt(var11));
                        } catch (Exception var13) {
                        }
                     }

                     this.shopPurchased.put(var7, var9);
                  }
               }
            }
         }
      }
   }

   private void saveShopState() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();

         for (Entry var3 : this.shopPrices.entrySet()) {
            var1.set("prices." + ((Material)var3.getKey()).name(), var3.getValue());
         }

         for (Entry var8 : this.shopPurchased.entrySet()) {
            for (Entry var5 : (Iterable<Entry>)(Iterable)(((Map)var8.getValue()).entrySet())) {
               var1.set("purchases." + ((Material)var8.getKey()).name() + "." + var5.getKey(), var5.getValue());
            }
         }

         var1.save(this.shopFile());
      } catch (Exception var6) {
         this.getLogger().severe("Could not save shop state: " + var6.getMessage());
      }
   }

   private void loadAccounts() {
      this.accounts.clear();
      if (this.accountsFile.exists()) {
         YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.accountsFile);
         ConfigurationSection var2 = var1.getConfigurationSection("accounts");
         if (var2 != null) {
            for (String var4 : var2.getKeys(false)) {
               ConfigurationSection var5 = var2.getConfigurationSection(var4);
               if (var5 != null) {
                  String var6 = var5.getString("name", var4);
                  String var7 = var5.getString("salt", "");
                  String var8 = var5.getString("hash", "");
                  if (!var7.isEmpty() && !var8.isEmpty()) {
                     this.accounts
                        .put(
                           var4.toLowerCase(Locale.ROOT),
                           new Account(var6, var7, var8, var5.getLong("registered-at", 0L))
                        );
                  }
               }
            }
         }
      }
   }

   private void saveAccounts() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();

         for (Entry var3 : this.accounts.entrySet()) {
            String var4 = "accounts." + (String)var3.getKey() + ".";
            var1.set(var4 + "name", ((Account)var3.getValue()).name);
            var1.set(var4 + "salt", ((Account)var3.getValue()).salt);
            var1.set(var4 + "hash", ((Account)var3.getValue()).hash);
            var1.set(var4 + "registered-at", ((Account)var3.getValue()).registeredAt);
         }

         var1.save(this.accountsFile);
      } catch (Exception var5) {
         this.getLogger().severe("Could not save accounts: " + var5.getMessage());
      }
   }

   private void loadSales() {
      this.soldByPlayer.clear();
      this.offlineNames.clear();
      File var1 = this.salesFile != null ? this.salesFile : new File(this.getDataFolder(), "sales.yml");
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         ConfigurationSection var3 = var2.getConfigurationSection("sales");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               try {
                  UUID var6 = UUID.fromString(var5);
                  ConfigurationSection var7 = var3.getConfigurationSection(var5);
                  if (var7 != null) {
                     LinkedHashMap var8 = new LinkedHashMap();

                     for (String var10 : var7.getKeys(false)) {
                        Material var11 = Material.matchMaterial(var10);
                        if (var11 != null) {
                           var8.put(var11, var7.getInt(var10));
                        }
                     }

                     if (!var8.isEmpty()) {
                        this.soldByPlayer.put(var6, var8);
                     }
                  }
               } catch (Exception var13) {
                  this.getLogger().warning("Skipping invalid sales record: " + var5);
               }
            }
         }

         ConfigurationSection var14 = var2.getConfigurationSection("names");
         if (var14 != null) {
            for (String var16 : var14.getKeys(false)) {
               try {
                  this.offlineNames.put(UUID.fromString(var16), var14.getString(var16));
               } catch (Exception var12) {
               }
            }
         }
      }
   }

   private void saveSales() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();

         for (Entry var3 : this.soldByPlayer.entrySet()) {
            for (Entry var5 : (Iterable<Entry>)(Iterable)(((Map)var3.getValue()).entrySet())) {
               var1.set("sales." + var3.getKey() + "." + ((Material)var5.getKey()).name(), var5.getValue());
            }
         }

         for (Entry var8 : this.offlineNames.entrySet()) {
            var1.set("names." + var8.getKey(), var8.getValue());
         }

         var1.save(this.salesFile != null ? this.salesFile : new File(this.getDataFolder(), "sales.yml"));
      } catch (Exception var6) {
         this.getLogger().severe("Could not save sales: " + var6.getMessage());
      }
   }

   private File auctionStateFile() {
      return new File(this.getDataFolder(), "auctions.yml");
   }

   private void loadAuctions() {
      this.auctions.clear();
      this.pendingReturns.clear();
      this.nextAuctionId = 1;
      File var1 = this.auctionStateFile();
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         this.nextAuctionId = var2.getInt("next-auction-id", 1);
         ConfigurationSection var3 = var2.getConfigurationSection("lots");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               try {
                  int var6 = Integer.parseInt(var5);
                  ConfigurationSection var7 = var3.getConfigurationSection(var5);
                  if (var7 != null) {
                     ItemStack var8 = var7.getItemStack("item");
                     if (var8 != null && !var8.getType().isAir()) {
                        long var9 = Math.max(1L, this.getConfig().getLong("auction.duration-minutes", 30L)) * 60000L;
                        AuctionLot var11 = new AuctionLot(
                           var6, UUID.fromString(var7.getString("seller")), var7.getString("seller-name", "Unknown"), var8, var7.getLong("min-price", 1L), 0L
                        );
                        var11.endsAtMillis = var7.getLong("ends-at", System.currentTimeMillis() + var9);
                        var11.buyout = var7.getLong("buyout", 0L);
                        String var12 = var7.getString("top-bidder");
                        if (var12 != null && !var12.isEmpty()) {
                           var11.topBidder = UUID.fromString(var12);
                           var11.topBidderName = var7.getString("top-bidder-name", "Unknown");
                           var11.topBid = var7.getLong("top-bid", 0L);
                        }

                        this.auctions.put(var6, var11);
                     }
                  }
               } catch (Exception var13) {
                  this.getLogger().warning("Skipping invalid auction lot " + var5 + ": " + var13.getMessage());
               }
            }
         }

         ConfigurationSection var15 = var2.getConfigurationSection("pending-returns");
         if (var15 != null) {
            for (String var17 : var15.getKeys(false)) {
               try {
                  UUID var18 = UUID.fromString(var17);
                  List var19 = var15.getList(var17);
                  ArrayList var20 = new ArrayList();
                  if (var19 != null) {
                     for (Object var21 : var19) {
                        if (var21 instanceof ItemStack var22) {
                           var20.add(var22);
                        }
                     }
                  }

                  if (var20 != null && !var20.isEmpty()) {
                     this.pendingReturns.put(var18, new ArrayList<>(var20));
                  }
               } catch (Exception var14) {
                  this.getLogger().warning("Skipping invalid pending return for " + var17);
               }
            }
         }
      }
   }

   private void saveAuctions() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();
         var1.set("next-auction-id", this.nextAuctionId);

         for (AuctionLot var3 : this.auctions.values()) {
            String var4 = "lots." + var3.id + ".";
            var1.set(var4 + "seller", var3.seller.toString());
            var1.set(var4 + "seller-name", var3.sellerName);
            var1.set(var4 + "item", var3.item);
            var1.set(var4 + "min-price", var3.minPrice);
            var1.set(var4 + "ends-at", var3.endsAtMillis);
            if (var3.buyout > 0L) {
               var1.set(var4 + "buyout", var3.buyout);
            }
            if (var3.topBidder != null) {
               var1.set(var4 + "top-bidder", var3.topBidder.toString());
               var1.set(var4 + "top-bidder-name", var3.topBidderName);
               var1.set(var4 + "top-bid", var3.topBid);
            }
         }

         for (Entry var7 : this.pendingReturns.entrySet()) {
            var1.set("pending-returns." + var7.getKey(), new ArrayList((Collection)var7.getValue()));
         }

         var1.save(this.auctionStateFile());
      } catch (Exception var5) {
         this.getLogger().severe("Could not save auctions: " + var5.getMessage());
      }
   }

   private void savePendingReturns() {
      this.saveAuctions();
   }

   private void handleTradeCommand(Player var1, String[] var2) {
      TradeSession var3 = this.pendingTrades.get(var1.getUniqueId());
      if (var2.length == 0) {
         if (var3 != null) {
            this.openTradeView(var1, var3);
         } else {
            this.sendTradeHelp(var1);
         }
      } else {
         String var4 = var2[0].toLowerCase(Locale.ROOT);
         if (var4.equals("accept")) {
            TradeSession var10 = this.tradeOffers.remove(var1.getUniqueId());
            if (var10 == null) {
               var1.sendMessage(ChatColor.RED + "Тебе никто не предлагал обмен.");
            } else {
               Player var11 = Bukkit.getPlayer(var10.initiator);
               if (var11 != null && var11.isOnline() && this.isAuthenticated(var11)) {
                  this.pendingTrades.put(var10.initiator, var10);
                  this.openTradeView(var11, var10);
                  this.openTradeView(var1, var10);
                  var11.sendMessage(ChatColor.GREEN + var1.getName() + " принял обмен!");
               } else {
                  var1.sendMessage(ChatColor.RED + "Инициатор обмена уже вышел.");
               }
            }
         } else if (!var4.equals("cancel") && !var4.equals("stop")) {
            if (var4.equals("request")) {
               if (var2.length < 2) {
                  this.sendTradeHelp(var1);
               } else if (var3 != null) {
                  var1.sendMessage(ChatColor.RED + "Сначала заверши текущий обмен: /trade cancel");
               } else {
                  Player var9 = Bukkit.getPlayerExact(var2[1]);
                  if (var9 != null && var9.isOnline()) {
                     if (var9.getUniqueId().equals(var1.getUniqueId())) {
                        var1.sendMessage(ChatColor.RED + "Нельзя обмениваться с собой.");
                     } else if (!this.isAuthenticated(var9)) {
                        var1.sendMessage(ChatColor.RED + "Этот игрок ещё не авторизован.");
                     } else if (!this.pendingTrades.containsKey(var9.getUniqueId()) && !this.tradeOffers.containsKey(var9.getUniqueId())) {
                        double var6 = Math.max(2.0, this.getConfig().getDouble("trade.max-distance-blocks", 10.0));
                        if (var1.getWorld() == var9.getWorld() && !(var1.getLocation().distanceSquared(var9.getLocation()) > var6 * var6)) {
                           TradeSession var8 = new TradeSession(
                              var1.getUniqueId(), var1.getName(), var9.getUniqueId(), var9.getName()
                           );
                           this.tradeOffers.put(var9.getUniqueId(), var8);
                           var1.sendMessage(ChatColor.GOLD + "Запрос обмена отправлен " + var9.getName() + ".");
                           var9.sendMessage(ChatColor.GOLD + var1.getName() + " предлагает обмен. Принять: /trade accept");
                           Bukkit.getScheduler().runTaskLater(this, () -> {
                              if (this.tradeOffers.get(var9.getUniqueId()) == var8) {
                                 this.tradeOffers.remove(var9.getUniqueId());
                                 if (var9.isOnline()) {
                                    var9.sendMessage(ChatColor.RED + "Запрос обмена истёк.");
                                 }

                                 if (var1.isOnline()) {
                                    var1.sendMessage(ChatColor.RED + "Партнёр не ответил на запрос обмена.");
                                 }
                              }
                           }, Math.max(5L, this.getConfig().getLong("trade.request-timeout-seconds", 30L)) * 20L);
                        } else {
                           var1.sendMessage(ChatColor.RED + "Партнёр слишком далеко (максимум " + (int)var6 + " блоков).");
                        }
                     } else {
                        var1.sendMessage(ChatColor.RED + "Этот игрок уже в обмене.");
                     }
                  } else {
                     var1.sendMessage(ChatColor.RED + "Игрок не в сети.");
                  }
               }
            } else {
               this.sendTradeHelp(var1);
            }
         } else {
            TradeSession var5 = var3 != null ? var3 : this.tradeOffers.remove(var1.getUniqueId());
            if (var5 == null) {
               var1.sendMessage(ChatColor.RED + "У тебя нет активного обмена.");
            } else {
               this.cancelTrade(var1, var5, true);
            }
         }
      }
   }

   private void sendTradeHelp(Player var1) {
      var1.sendMessage(ChatColor.GRAY + "/trade request <игрок> — предложить обмен (рядом, в пределах 10 блоков)");
      var1.sendMessage(ChatColor.GRAY + "/trade accept — принять предложенный обмен");
      var1.sendMessage(ChatColor.GRAY + "/trade cancel — отменить обмен (предметы вернутся)");
   }

   private void openTradeView(Player var1, TradeSession var2) {
      boolean var3 = var1.getUniqueId().equals(var2.initiator);
      TradeRole var4 = var3
         ? TradeRole.INITIATOR
         : TradeRole.PARTNER;
      Inventory var5 = var3 ? var2.initiatorView : var2.partnerView;
      if (var5 == null) {
         TradeHolder var6 = new TradeHolder(var1.getUniqueId(), var4, var2);
         var5 = Bukkit.createInventory(var6, 27, ChatColor.DARK_AQUA + "Обмен с " + (var3 ? var2.partnerName : var2.initiatorName));
         var6.inventory = var5;
         if (var3) {
            var2.initiatorView = var5;
         } else {
            var2.partnerView = var5;
         }
      }

      this.refreshTradeViews(var2);
      var1.openInventory(var5);
   }

   private void refreshTradeViews(TradeSession var1) {
      this.fillTradeView(var1, var1.initiatorView, TradeRole.INITIATOR);
      this.fillTradeView(var1, var1.partnerView, TradeRole.PARTNER);
   }

   private void fillTradeView(TradeSession var1, Inventory var2, TradeRole var3) {
      if (var2 != null) {
         UUID var4 = var3 == TradeRole.INITIATOR ? var1.initiator : var1.partner;
         Object var5 = var3 == TradeRole.INITIATOR ? var1.partnerName : var1.initiatorName;
         Inventory var6 = var3 == TradeRole.INITIATOR ? var1.partnerView : var1.initiatorView;
         ArrayList var7 = new ArrayList();

         for (ItemStack var9 : this.collectFromView(var2, 0, 9)) {
            var7.add(var9);
         }

         ItemStack[] var15 = new ItemStack[27];
         int var16 = 0;

         for (ItemStack var11 : (Iterable<ItemStack>)(Iterable)(var7)) {
            if (var16 >= 9) {
               break;
            }

            var15[var16++] = var11;
         }

         if (var6 != null) {
            int var17 = 9;

            for (ItemStack var14 : var6.getContents()) {
               if (var14 != null && !var14.getType().isAir()) {
                  if (var17 >= 18) {
                     break;
                  }

                  var15[var17++] = var14.clone();
               }
            }
         }

         var2.setContents(var15);
         boolean var18 = var4.equals(var1.initiator) ? var1.initiatorReady : var1.partnerReady;
         boolean var20 = var3 == TradeRole.INITIATOR ? var1.partnerReady : var1.initiatorReady;
         var2.setItem(18, this.named(Material.CHEST, ChatColor.GOLD + "Твоё предложение", List.of(ChatColor.GRAY + "Слева сверху — твои предметы.")));
         var2.setItem(
            19,
            this.named(Material.HOPPER, ChatColor.GRAY + "Предложение партнёра", List.of(ChatColor.GRAY + "Справа сверху — его предметы (только просмотр)."))
         );
         var2.setItem(
            22,
            this.named(
               var18 ? Material.LIME_STAINED_GLASS_PANE : Material.GREEN_DYE,
               var18 ? ChatColor.GREEN + "Подтверждено!" : ChatColor.GREEN + "Подтвердить обмен",
               List.of(ChatColor.GRAY + "Нажми, когда всё разложено.")
            )
         );
         var2.setItem(
            26,
            this.named(
               var20 ? Material.LIME_STAINED_GLASS : Material.GRAY_STAINED_GLASS, "" + ChatColor.AQUA + var5 + ": " + (var20 ? "готов" : "не готов"), List.of()
            )
         );
      }
   }

   private void handleTradeClick(Player var1, TradeHolder var2, InventoryClickEvent var3) {
      TradeSession var4 = this.pendingTrades.get(var1.getUniqueId());
      if (var4 == null) {
         var1.closeInventory();
      } else {
         int var5 = var3.getSlot();
         if (var5 == 22) {
            var3.setCancelled(true);
            this.confirmTrade(var1, var4);
         } else if (var5 >= 18) {
            var3.setCancelled(true);
         } else if (var3.getClickedInventory() != var3.getView().getTopInventory()) {
            var3.setCancelled(true);
         } else if (var5 >= 9) {
            var3.setCancelled(true);
         } else if (var4.initiatorReady && var4.partnerReady) {
            var3.setCancelled(true);
         } else {
            this.resetTradeReadiness(var4);
         }
      }
   }

   private void resetTradeReadiness(TradeSession var1) {
      if (var1.initiatorReady || var1.partnerReady) {
         var1.initiatorReady = false;
         var1.partnerReady = false;
         this.notifyBoth(var1, ChatColor.YELLOW + "Содержимое изменилось — подтвердите заново.");
         this.refreshTradeViews(var1);
      }
   }

   private void confirmTrade(Player var1, TradeSession var2) {
      boolean var3 = var1.getUniqueId().equals(var2.initiator);
      if (var3) {
         var2.initiatorReady = true;
      } else {
         var2.partnerReady = true;
      }

      this.refreshTradeViews(var2);
      if (var2.initiatorReady && var2.partnerReady) {
         this.completeTrade(var2);
      } else {
         this.notifyBoth(var2, ChatColor.GOLD + "Ожидание подтверждения партнёра...");
      }
   }

   private void completeTrade(TradeSession var1) {
      List var2 = this.collectFromView(var1.initiatorView, 0, 9);
      List var3 = this.collectFromView(var1.partnerView, 0, 9);
      Player var4 = Bukkit.getPlayer(var1.initiator);
      Player var5 = Bukkit.getPlayer(var1.partner);
      if (var4 != null && var5 != null && var4.isOnline() && var5.isOnline()) {
         var1.clearBothViews();

         for (ItemStack var7 : (Iterable<ItemStack>)(Iterable)(var2)) {
            this.giveOrDrop(var5, var7);
         }

         for (ItemStack var9 : (Iterable<ItemStack>)(Iterable)(var3)) {
            this.giveOrDrop(var4, var9);
         }

         this.pendingTrades.remove(var1.initiator);
         this.pendingTrades.remove(var1.partner);
         var4.closeInventory();
         var5.closeInventory();
         var4.sendMessage(ChatColor.GREEN + "Обмен завершён успешно!");
         var5.sendMessage(ChatColor.GREEN + "Обмен завершён успешно!");
      } else {
         this.abortTradeSession(var1);
      }
   }

   private List<ItemStack> collectFromView(Inventory var1, int var2, int var3) {
      ArrayList var4 = new ArrayList();
      if (var1 == null) {
         return var4;
      } else {
         for (int var5 = var2; var5 < var3 && var5 < var1.getSize(); var5++) {
            ItemStack var6 = var1.getItem(var5);
            if (var6 != null && !var6.getType().isAir()) {
               var4.add(var6.clone());
            }
         }

         return var4;
      }
   }

   private void cancelTrade(Player var1, TradeSession var2, boolean var3) {
      this.pendingTrades.remove(var2.initiator);
      this.pendingTrades.remove(var2.partner);
      this.tradeOffers.remove(var2.initiator);
      this.tradeOffers.remove(var2.partner);
      this.abortTradeSession(var2);
      if (var3) {
         var1.closeInventory();
         Player var4 = var2.otherPlayer(var1);
         if (var4 != null) {
            var4.closeInventory();
            var4.sendMessage(ChatColor.RED + var1.getName() + " отменил обмен.");
         }

         var1.sendMessage(ChatColor.RED + "Ты отменил обмен.");
      }
   }

   private void abortTradeSession(TradeSession var1) {
      Player var2 = Bukkit.getPlayer(var1.initiator);
      Player var3 = Bukkit.getPlayer(var1.partner);
      if (var1.initiatorView != null) {
         for (ItemStack var5 : this.collectFromView(var1.initiatorView, 0, 9)) {
            if (var2 != null) {
               this.giveOrDrop(var2, var5);
            }
         }

         var1.initiatorView.clear();
      }

      if (var1.partnerView != null) {
         for (ItemStack var7 : this.collectFromView(var1.partnerView, 0, 9)) {
            if (var3 != null) {
               this.giveOrDrop(var3, var7);
            }
         }

         var1.partnerView.clear();
      }

      this.pendingTrades.remove(var1.initiator);
      this.pendingTrades.remove(var1.partner);
   }

   private void notifyBoth(TradeSession var1, String var2) {
      Player var3 = Bukkit.getPlayer(var1.initiator);
      Player var4 = Bukkit.getPlayer(var1.partner);
      if (var3 != null) {
         var3.sendMessage(var2);
      }

      if (var4 != null) {
         var4.sendMessage(var2);
      }
   }

   private void handleClanCommand(Player var1, String[] var2) {
      Integer var3 = this.playerClan.get(var1.getUniqueId());
      Clan var4 = var3 == null ? null : this.clansById.get(var3);
      if (var2.length == 0) {
         if (var4 == null) {
            this.sendClanHelp(var1);
         } else {
            this.showClanInfo(var1, var4);
         }
      } else {
         String var5 = var2[0].toLowerCase(Locale.ROOT);
         switch (var5) {
            case "create":
               if (var4 != null) {
                  var1.sendMessage(ChatColor.RED + "Ты уже в клане «" + var4.name + "». Сначала /clan leave");
                  return;
               } else if (var2.length < 3) {
                  var1.sendMessage(ChatColor.RED + "Использование: /clan create <тег> <название>");
                  return;
               } else {
                  String var24 = var2[1];
                  String var31 = var2[2];
                  if (!var24.matches("[A-Za-z0-9]{2,6}")) {
                     var1.sendMessage(ChatColor.RED + "Тег: 2-6 символов (латиница/цифры), без пробелов.");
                     return;
                  } else if (var31.length() > 24) {
                     var1.sendMessage(ChatColor.RED + "Название максимум 24 символа.");
                     return;
                  } else {
                     String var35 = var24.toUpperCase(Locale.ROOT);

                     for (Clan var38 : this.clansById.values()) {
                        if (var38.tag.equalsIgnoreCase(var35)) {
                           var1.sendMessage(ChatColor.RED + "Клан с таким тегом уже есть.");
                           return;
                        }
                     }

                     long var37 = Math.max(0L, this.getConfig().getLong("clan.create-cost", 500L));
                     if (var37 > 0L && this.getBalance(var1.getUniqueId()) < var37) {
                        var1.sendMessage(ChatColor.RED + "Нужно " + var37 + " монет на создание клана.");
                        return;
                     }

                     if (var37 > 0L) {
                        this.setBalance(var1.getUniqueId(), this.getBalance(var1.getUniqueId()) - var37);
                     }

                     Clan var13 = new Clan(
                        this.nextClanId++, var35, var31, var1.getUniqueId()
                     );
                     this.clansById.put(var13.id, var13);
                     var13.members
                        .put(
                           var1.getUniqueId(),
                           new ClanMember(
                              var1.getUniqueId(), var1.getName(), ClanRank.LEADER, System.currentTimeMillis()
                           )
                        );
                     this.playerClan.put(var1.getUniqueId(), var13.id);
                     this.saveClans();
                     this.saveBalances();
                     var1.sendMessage(ChatColor.GREEN + "Клан [" + var35 + "] " + var31 + " создан! Приглашай: /clan invite <ник>");
                     return;
                  }
               }
            case "invite":
               if (var4 != null && var4.isOfficer(var1.getUniqueId())) {
                  if (var2.length < 2) {
                     var1.sendMessage(ChatColor.RED + "Использование: /clan invite <ник>");
                     return;
                  }

                  Player var23 = Bukkit.getPlayerExact(var2[1]);
                  if (var23 != null && var23.isOnline() && this.isAuthenticated(var23)) {
                     if (this.playerClan.containsKey(var23.getUniqueId())) {
                        var1.sendMessage(ChatColor.RED + "Этот игрок уже в клане.");
                        return;
                     }

                     int var30 = var4.maxMembers(this);
                     if (var4.members.size() >= var30) {
                        var1.sendMessage(ChatColor.RED + "Клан заполнен (максимум " + var30 + ", ур. " + var4.level + "). Улучши: /clan upgrade");
                        return;
                     }

                     this.pendingClanInvites.put(var23.getUniqueId(), new long[]{var4.id, System.currentTimeMillis()});
                     var1.sendMessage(ChatColor.GOLD + "Приглашение отправлено " + var23.getName() + ".");
                     var23.sendMessage(ChatColor.GOLD + "[" + var4.tag + "] " + var1.getName() + " приглашает в клан «" + var4.name + "». Принять: /clan join");
                     return;
                  }

                  var1.sendMessage(ChatColor.RED + "Игрок не в сети или не авторизован.");
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Приглашать могут только лидер и офицеры.");
               return;
            case "join":
               long[] var22 = this.pendingClanInvites.remove(var1.getUniqueId());
               if (var22 == null) {
                  var1.sendMessage(ChatColor.RED + "У тебя нет приглашений в клан.");
                  return;
               } else {
                  Clan var29 = this.clansById.get((int)var22[0]);
                  if (var29 == null) {
                     var1.sendMessage(ChatColor.RED + "Клан уже распущен.");
                     return;
                  } else if (System.currentTimeMillis() - var22[1] > 60000L) {
                     var1.sendMessage(ChatColor.RED + "Приглашение истекло.");
                     return;
                  } else {
                     if (var29.members.size() >= var29.maxMembers(this)) {
                        var1.sendMessage(ChatColor.RED + "Клан заполнен, попроси лидера улучшить его: /clan upgrade");
                        return;
                     }

                     var29.members
                        .put(
                           var1.getUniqueId(),
                           new ClanMember(
                              var1.getUniqueId(), var1.getName(), ClanRank.MEMBER, System.currentTimeMillis()
                           )
                        );
                     this.playerClan.put(var1.getUniqueId(), var29.id);
                     this.saveClans();
                     this.notifyClan(var29, ChatColor.GREEN + var1.getName() + " вступил в клан.", var1.getUniqueId());
                     var1.sendMessage(ChatColor.GREEN + "Ты вступил в клан [" + var29.tag + "] " + var29.name);
                     return;
                  }
               }
            case "leave":
               if (var4 == null) {
                  var1.sendMessage(ChatColor.RED + "Ты не в клане.");
                  return;
               } else {
                  if (var4.leader.equals(var1.getUniqueId()) && var4.members.size() > 1) {
                     var1.sendMessage(ChatColor.RED + "Ты лидер: передай лидерство (/clan promote <ник> leader) или распусти клан /clan disband");
                     return;
                  }

                  var4.members.remove(var1.getUniqueId());
                  this.playerClan.remove(var1.getUniqueId());
                  if (var4.members.isEmpty()) {
                     this.clansById.remove(var4.id);
                     var1.sendMessage(ChatColor.GRAY + "Клан распущен (ты был последним участником).");
                  } else {
                     this.notifyClan(var4, ChatColor.YELLOW + var1.getName() + " покинул клан.", null);
                     var1.sendMessage(ChatColor.GREEN + "Ты вышел из клана.");
                  }

                  this.saveClans();
                  return;
               }
            case "kick":
               if (var4 != null && var4.isOfficer(var1.getUniqueId())) {
                  if (var2.length < 2) {
                     var1.sendMessage(ChatColor.RED + "Использование: /clan kick <ник>");
                     return;
                  }

                  UUID var21 = this.findClanMemberByName(var4, var2[1]);
                  if (var21 == null) {
                     var1.sendMessage(ChatColor.RED + "Такого участника нет в клане.");
                     return;
                  }

                  ClanMember var28 = var4.members.get(var21);
                  if (var21.equals(var4.leader)) {
                     var1.sendMessage(ChatColor.RED + "Нельзя выгнать лидера.");
                     return;
                  }

                  if (var28.rank == ClanRank.OFFICER && !var4.leader.equals(var1.getUniqueId())) {
                     var1.sendMessage(ChatColor.RED + "Офицера может выгнать только лидер.");
                     return;
                  }

                  var4.members.remove(var21);
                  this.playerClan.remove(var21);
                  this.saveClans();
                  Player var34 = Bukkit.getPlayer(var21);
                  if (var34 != null) {
                     var34.sendMessage(ChatColor.RED + "Тебя исключили из клана [" + var4.tag + "].");
                  }

                  this.notifyClan(var4, ChatColor.YELLOW + var28.name + " исключён из клана.", null);
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Выгонять могут только лидер и офицеры.");
               return;
            case "promote":
               if (var4 != null && var4.leader.equals(var1.getUniqueId())) {
                  if (var2.length < 3) {
                     var1.sendMessage(ChatColor.RED + "Использование: /clan promote <ник> <leader|officer|member>");
                     return;
                  }

                  UUID var20 = this.findClanMemberByName(var4, var2[1]);
                  if (var20 == null) {
                     var1.sendMessage(ChatColor.RED + "Этого участника нет в клане.");
                     return;
                  }

                  String var27 = var2[2].toLowerCase(Locale.ROOT);
                  if (var27.equals("leader")) {
                     var4.members.get(var20).rank = ClanRank.LEADER;
                     ClanMember var33 = var4.members.get(var4.leader);
                     if (var33 != null) {
                        var33.rank = ClanRank.OFFICER;
                     }

                     var4.leader = var20;
                     this.saveClans();
                     var1.sendMessage(ChatColor.GREEN + var2[1] + " теперь лидер клана.");
                     this.notifyClan(var4, ChatColor.GOLD + var2[1] + " — новый лидер!", null);
                     return;
                  }

                  ClanRank var32;
                  if (var27.equals("officer")) {
                     var32 = ClanRank.OFFICER;
                  } else {
                     if (!var27.equals("member")) {
                        var1.sendMessage(ChatColor.RED + "Ранги: leader, officer, member");
                        return;
                     }

                     var32 = ClanRank.MEMBER;
                  }

                  if (var20.equals(var4.leader)) {
                     var1.sendMessage(ChatColor.RED + "Лидера нельзя понизить (сначала передай лидерство).");
                     return;
                  }

                  var4.members.get(var20).rank = var32;
                  this.saveClans();
                  var1.sendMessage(ChatColor.GREEN + var2[1] + " теперь " + var32.display + ".");
                  this.notifyClan(var4, ChatColor.GOLD + var2[1] + " — " + var32.display + ".", null);
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Назначать ранги может только лидер.");
               return;
            case "deposit":
               if (var4 == null) {
                  var1.sendMessage(ChatColor.RED + "Ты не в клане.");
                  return;
               } else if (var2.length < 2) {
                  var1.sendMessage(ChatColor.RED + "Использование: /clan deposit <сумма>");
                  return;
               } else {
                  long var19;
                  try {
                     var19 = Long.parseLong(var2[1]);
                  } catch (NumberFormatException var15) {
                     var1.sendMessage(ChatColor.RED + "Сумма должна быть числом.");
                     return;
                  }

                  if (var19 >= 1L && var19 <= 1000000000L) {
                     if (this.getBalance(var1.getUniqueId()) < var19) {
                        var1.sendMessage(ChatColor.RED + "Недостаточно личных монет.");
                        return;
                     }

                     this.setBalance(var1.getUniqueId(), this.getBalance(var1.getUniqueId()) - var19);
                     var4.balance = this.saturatingAdd(var4.balance, var19);
                     this.saveClans();
                     this.saveBalances();
                     var1.sendMessage(ChatColor.GREEN + "В казну внесено " + var19 + " монет. Казна: " + var4.balance);
                     this.notifyClan(var4, ChatColor.GRAY + var1.getName() + " пополнил казну на " + var19 + ".", var1.getUniqueId());
                     return;
                  }

                  var1.sendMessage(ChatColor.RED + "Сумма: от 1 до 1 000 000 000.");
                  return;
               }
            case "withdraw":
               if (var4 != null && var4.isOfficer(var1.getUniqueId())) {
                  if (var2.length < 2) {
                     var1.sendMessage(ChatColor.RED + "Использование: /clan withdraw <сумма>");
                     return;
                  }

                  long var18;
                  try {
                     var18 = Long.parseLong(var2[1]);
                  } catch (NumberFormatException var14) {
                     var1.sendMessage(ChatColor.RED + "Сумма должна быть числом.");
                     return;
                  }

                  if (var18 >= 1L && var18 <= var4.balance) {
                     var4.balance -= var18;
                     this.setBalance(var1.getUniqueId(), this.saturatingAdd(this.getBalance(var1.getUniqueId()), var18));
                     this.saveClans();
                     this.saveBalances();
                     var1.sendMessage(ChatColor.GREEN + "Снято из казны: " + var18 + ". Остаток: " + var4.balance);
                     this.notifyClan(var4, ChatColor.GRAY + var1.getName() + " снял из казны " + var18 + ".", var1.getUniqueId());
                     return;
                  }

                  var1.sendMessage(ChatColor.RED + "В казне " + var4.balance + " монет.");
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Снимать с казны могут только лидер и офицеры.");
               return;
            case "disband":
               if (var4 != null && var4.leader.equals(var1.getUniqueId())) {
                  if (var4.balance > 0L) {
                     var1.sendMessage(ChatColor.RED + "Сначала выведи казну: /clan withdraw " + var4.balance);
                     return;
                  }

                  for (UUID var26 : new ArrayList<>(var4.members.keySet())) {
                     this.playerClan.remove(var26);
                     Player var10 = Bukkit.getPlayer(var26);
                     if (var10 != null) {
                        var10.sendMessage(ChatColor.RED + "Клан [" + var4.tag + "] распущен.");
                     }
                  }

                  this.clansById.remove(var4.id);
                  this.claims.onClanDisband(var4.id);
                  this.saveClans();
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Распускать клан может только лидер.");
               return;
            case "upgrade":
               if (var4 != null && var4.leader.equals(var1.getUniqueId())) {
                  int var16 = this.clanMaxLevel();
                  if (var4.level >= var16) {
                     var1.sendMessage(ChatColor.RED + "Клан уже максимального " + var16 + " уровня.");
                     return;
                  }

                  long var25 = this.clanUpgradeCost(var4.level);
                  boolean var11 = this.getConfig().getBoolean("clan.upgrade.pay-from-treasury", true);
                  if (var11) {
                     if (var4.balance < var25) {
                        var1.sendMessage(ChatColor.RED + "В казне нужно " + var25 + " монет (есть " + var4.balance + "). Пополни: /clan deposit");
                        return;
                     }

                     var4.balance -= var25;
                  } else {
                     if (this.getBalance(var1.getUniqueId()) < var25) {
                        var1.sendMessage(ChatColor.RED + "Нужно " + var25 + " личных монет.");
                        return;
                     }

                     this.setBalance(var1.getUniqueId(), this.getBalance(var1.getUniqueId()) - var25);
                  }

                  var4.level++;
                  this.saveClans();
                  this.saveBalances();
                  int var12 = Math.max(0, this.getConfig().getInt("clan.upgrade.member-bonus", 5));
                  var1.sendMessage(
                     ChatColor.GREEN + "Клан улучшен до уровня " + var4.level + "! (" + var25 + " монет). Максимум участников теперь: " + var4.maxMembers(this)
                  );
                  this.notifyClan(
                     var4,
                     ChatColor.GOLD
                        + var1.getName()
                        + " улучшил клан до уровня "
                        + var4.level
                        + " (+ slots: "
                        + var12
                        + ", макс. участников: "
                        + var4.maxMembers(this)
                        + ").",
                     null
                  );
                  return;
               }

               var1.sendMessage(ChatColor.RED + "Улучшать клан может только лидер.");
               return;
            case "private":
               this.claims.handleCommand(var1, var4, var2);
               return;
            case "list":
               if (this.clansById.isEmpty()) {
                  var1.sendMessage(ChatColor.GRAY + "Пока нет ни одного клана.");
                  return;
               }

               var1.sendMessage(ChatColor.GOLD + "Кланы сервера:");

               for (Clan var9 : this.clansById.values()) {
                  var1.sendMessage(
                     ChatColor.YELLOW
                        + "["
                        + var9.tag
                        + "] "
                        + var9.name
                        + ChatColor.GRAY
                        + " — ур. "
                        + var9.level
                        + ", участников: "
                        + var9.members.size()
                        + "/"
                        + var9.maxMembers(this)
                        + ", казна: "
                        + var9.balance
                  );
               }

               return;
            default:
               this.sendClanHelp(var1);
         }
      }
   }

   private void sendClanHelp(Player var1) {
      var1.sendMessage(ChatColor.GRAY + "/clan create <тег> <название> — создать клан");
      var1.sendMessage(ChatColor.GRAY + "/clan invite <ник> • /clan join • /clan leave • /clan kick <ник>");
      var1.sendMessage(ChatColor.GRAY + "/clan promote <ник> <leader|officer|member> — ранги");
      var1.sendMessage(ChatColor.GRAY + "/clan deposit|withdraw <сумма> — казна клана");
      var1.sendMessage(ChatColor.GRAY + "/clan upgrade — улучшить клан за монеты из казны (больше слотов участников)");
      var1.sendMessage(ChatColor.GRAY + "/clan private — приват территории (лидер получает ядро-маяк, ПКМ по нему — выбор чанков)");
      var1.sendMessage(ChatColor.GRAY + "/clan private info — сколько чанков заприватено и цена следующего");
      var1.sendMessage(ChatColor.GRAY + "/clan private borders — показать/скрыть границы приватов");
      var1.sendMessage(ChatColor.GRAY + "/clan list • /clan disband");
   }

   private void showClanInfo(Player var1, Clan var2) {
      ClanRank var3 = var2.rankOf(var1.getUniqueId());
      var1.sendMessage(ChatColor.GOLD + "Клан [" + var2.tag + "] " + var2.name + " — твоя роль: " + (var3 == null ? "-" : var3.display));
      var1.sendMessage(
         ChatColor.GRAY
            + "Лидер: "
            + this.resolveName(var2.leader)
            + " • Участников: "
            + var2.members.size()
            + "/"
            + var2.maxMembers(this)
            + " • Уровень: "
            + var2.level
            + "/"
            + this.clanMaxLevel()
            + " • Казна: "
            + var2.balance
            + " монет"
      );
      if (var2.level < this.clanMaxLevel()) {
         var1.sendMessage(
            ChatColor.GRAY + "Улучшение до ур. " + (var2.level + 1) + " стоит " + this.clanUpgradeCost(var2.level) + " монет из казны (/clan upgrade)."
         );
      }

      StringBuilder var4 = new StringBuilder(ChatColor.YELLOW + "Состав: ");

      for (ClanMember var6 : var2.members.values()) {
         var4.append(var6.rank.display).append(' ').append(var6.name).append(ChatColor.YELLOW + ", ");
      }

      String var7 = var4.toString();
      if (var7.endsWith(", ")) {
         var7 = var7.substring(0, var7.length() - 2);
      }

      var1.sendMessage(var7);
   }

   private UUID findClanMemberByName(Clan var1, String var2) {
      for (ClanMember var4 : var1.members.values()) {
         if (var4.name.equalsIgnoreCase(var2)) {
            return var4.uuid;
         }
      }

      return null;
   }

   private void notifyClan(Clan var1, String var2, UUID var3) {
      for (UUID var5 : var1.members.keySet()) {
         if (var3 == null || !var5.equals(var3)) {
            Player var6 = Bukkit.getPlayer(var5);
            if (var6 != null) {
               var6.sendMessage(ChatColor.DARK_PURPLE + "[" + var1.tag + "] " + var2);
            }
         }
      }
   }

   private File clansFile() {
      return new File(this.getDataFolder(), "clans.yml");
   }

   private void loadClans() {
      this.clansById.clear();
      this.playerClan.clear();
      File var1 = this.clansFile();
      if (var1.exists()) {
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         this.nextClanId = var2.getInt("next-clan-id", 1);
         ConfigurationSection var3 = var2.getConfigurationSection("clans");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               try {
                  int var6 = Integer.parseInt(var5);
                  ConfigurationSection var7 = var3.getConfigurationSection(var5);
                  if (var7 != null) {
                     Clan var8 = new Clan(
                        var6, var7.getString("tag", "?"), var7.getString("name", "?"), UUID.fromString(var7.getString("leader"))
                     );
                     var8.balance = var7.getLong("balance", 0L);
                     var8.level = Math.max(1, var7.getInt("level", 1));
                     ConfigurationSection var9 = var7.getConfigurationSection("members");
                     if (var9 != null) {
                        for (String var11 : var9.getKeys(false)) {
                           UUID var12 = UUID.fromString(var11);
                           ClanMember var13 = new ClanMember(
                              var12,
                              var9.getString(var11 + ".name", "Unknown"),
                              ClanRank.valueOf(var9.getString(var11 + ".rank", "MEMBER")),
                              var9.getLong(var11 + ".joined", 0L)
                           );
                           var8.members.put(var12, var13);
                           this.playerClan.put(var12, var6);
                        }
                     }

                     this.clansById.put(var6, var8);
                  }
               } catch (Exception var14) {
                  this.getLogger().warning("Skipping invalid clan entry: " + var14.getMessage());
               }
            }
         }
      }
   }

   private void saveClans() {
      try {
         if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder");
         }

         YamlConfiguration var1 = new YamlConfiguration();
         var1.set("next-clan-id", this.nextClanId);

         for (Clan var3 : this.clansById.values()) {
            String var4 = "clans." + var3.id + ".";
            var1.set(var4 + "tag", var3.tag);
            var1.set(var4 + "name", var3.name);
            var1.set(var4 + "leader", var3.leader.toString());
            var1.set(var4 + "balance", var3.balance);
            var1.set(var4 + "level", var3.level);

            for (ClanMember var6 : var3.members.values()) {
               String var7 = var4 + "members." + var6.uuid + ".";
               var1.set(var7 + "name", var6.name);
               var1.set(var7 + "rank", var6.rank.name());
               var1.set(var7 + "joined", var6.joinedAt);
            }
         }

         var1.save(this.clansFile());
      } catch (Exception var8) {
         this.getLogger().severe("Could not save clans: " + var8.getMessage());
      }
   }

   // ---- доступ для модуля приватов (ClanClaims) ----
   Clan su_clanOf(UUID var1) {
      Integer var2 = this.playerClan.get(var1);
      return var2 == null ? null : this.clansById.get(var2);
   }

   Clan su_clanById(int var1) {
      return this.clansById.get(var1);
   }

   void su_saveClans() {
      this.saveClans();
   }

   long su_getBalance(UUID var1) {
      return this.getBalance(var1);
   }

   void su_setBalance(UUID var1, long var2) {
      this.setBalance(var1, var2);
   }

   void su_saveBalances() {
      this.saveBalances();
   }

   void su_notifyClan(Clan var1, String var2, UUID var3) {
      this.notifyClan(var1, var2, var3);
   }

   long su_safeAdd(long var1, long var3) {
      return this.saturatingAdd(var1, var3);
   }

   long su_safeMultiply(long var1, long var3) {
      return this.safeMultiply(var1, var3);
   }

   static final class Account {
      final String name;
      final String salt;
      final String hash;
      long registeredAt;

      Account(String var1, String var2, String var3, long var4) {
         this.name = var1;
         this.salt = var2;
         this.hash = var3;
         this.registeredAt = var4;
      }
   }

   static final class AuctionHolder implements InventoryHolder {
      Inventory inventory;
      final Map<Integer, Integer> slotToLot = new LinkedHashMap<>();

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class AuctionLot {
      final int id;
      final UUID seller;
      final String sellerName;
      final ItemStack item;
      long minPrice;
      UUID topBidder;
      String topBidderName;
      long topBid;
      long endsAtMillis;
      /** Цена мгновенного выкупа (0 — нет). */
      long buyout;

      AuctionLot(int var1, UUID var2, String var3, ItemStack var4, long var5, long var7) {
         this.id = var1;
         this.seller = var2;
         this.sellerName = var3;
         this.item = var4;
         this.minPrice = var5;
         this.topBid = 0L;
         this.endsAtMillis = System.currentTimeMillis() + var7;
      }

      long currentPrice() {
         return Math.max(this.minPrice, this.topBid);
      }

      boolean expired() {
         return System.currentTimeMillis() >= this.endsAtMillis;
      }
   }

   static final class AutoSellHolder {
      final UUID owner;
      int selectedIndex = -1;
      int selectedSlot = -1;
      long lastActivity = System.currentTimeMillis();
      long selectionExpiresAt = 0L;

      AutoSellHolder(UUID var1) {
         this.owner = var1;
      }
   }

   private static final class AutoSellMenuHolder implements InventoryHolder {
      Inventory inventory;
      final Map<Integer, Integer> slotToInvIndex = new LinkedHashMap<>();

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class BoardHolder implements InventoryHolder {
      Inventory inventory;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class BuyerHolder implements InventoryHolder {
      Inventory inventory;
      final Map<Integer, Listing> lots = new LinkedHashMap<>();

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class Clan {
      final int id;
      String tag;
      String name;
      UUID leader;
      long balance;
      int level = 1;
      final Map<UUID, ClanMember> members = new LinkedHashMap<>();

      Clan(int var1, String var2, String var3, UUID var4) {
         this.id = var1;
         this.tag = var2;
         this.name = var3;
         this.leader = var4;
      }

      ClanRank rankOf(UUID var1) {
         ClanMember var2 = this.members.get(var1);
         return var2 == null ? null : var2.rank;
      }

      boolean isOfficer(UUID var1) {
         ClanRank var2 = this.rankOf(var1);
         return var2 == ClanRank.LEADER || var2 == ClanRank.OFFICER;
      }

      int maxMembers(ru.manus.tradeorders.TradeOrdersPlugin var1) {
         int var2 = Math.max(2, var1.getConfig().getInt("clan.max-members", 10));
         int var3 = Math.max(0, var1.getConfig().getInt("clan.upgrade.member-bonus", 5));
         return var2 + var3 * Math.max(0, this.level - 1);
      }
   }

   private static final class ClanHolder implements InventoryHolder {
      Inventory inventory;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class ClanMember {
      final UUID uuid;
      String name;
      ClanRank rank;
      long joinedAt;

      ClanMember(UUID var1, String var2, ClanRank var3, long var4) {
         this.uuid = var1;
         this.name = var2;
         this.rank = var3;
         this.joinedAt = var4;
      }
   }

   static enum ClanRank {
      LEADER("§6Лидер"),
      OFFICER("§5Офицер"),
      MEMBER("§7Участник");

      final String display;

      private ClanRank(String nullxx) {
         this.display = nullxx;
      }
   }

   static final class Listing {
      final ItemStack template;
      int amount;

      Listing(ItemStack var1, int var2) {
         this.template = var1;
         this.amount = var2;
      }
   }

   static final class MarketStats {
      double pressure;
      long totalSold;
      long lastUpdated;
   }

   private static final class OGuiEntry {
      final int slot;
      final Material material;
      final String title;
      final List<String> lore;

      OGuiEntry(int var1, Material var2, String var3, List<String> var4) {
         this.slot = var1;
         this.material = var2;
         this.title = var3;
         this.lore = var4;
      }
   }

   static final class StorageHolder implements InventoryHolder {
      Inventory inventory;
      int page;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class OGuiHolder implements InventoryHolder {
      Inventory inventory;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class Order {
      final int id;
      final UUID owner;
      final String ownerName;
      final ItemStack wanted;
      final ItemStack offered;
      final long moneyAmount;
      /** true — оплата (предметы или монеты) уже забрана у владельца и хранится в заказе. */
      boolean escrow;

      Order(int var1, UUID var2, String var3, ItemStack var4, ItemStack var5, long var6) {
         this.id = var1;
         this.owner = var2;
         this.ownerName = var3;
         this.wanted = var4;
         this.offered = var5;
         this.moneyAmount = var6;
      }
   }

   static final class ShopHolder implements InventoryHolder {
      Inventory inventory;
      final Map<Integer, Material> lots = new LinkedHashMap<>();
      final Map<Integer, Integer> stackSizes = new LinkedHashMap<>();

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class TopHolder implements InventoryHolder {
      Inventory inventory;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static final class TradeHolder implements InventoryHolder {
      final UUID owner;
      final TradeRole role;
      final TradeSession session;
      Inventory inventory;

      TradeHolder(UUID var1, TradeRole var2, TradeSession var3) {
         this.owner = var1;
         this.role = var2;
         this.session = var3;
      }

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   static enum TradeRole {
      INITIATOR,
      PARTNER;
   }

   static final class TradeSession {
      final UUID initiator;
      final UUID partner;
      final String initiatorName;
      final String partnerName;
      Inventory initiatorView;
      Inventory partnerView;
      boolean initiatorReady;
      boolean partnerReady;
      long createdAtMillis = System.currentTimeMillis();

      TradeSession(UUID var1, String var2, UUID var3, String var4) {
         this.initiator = var1;
         this.initiatorName = var2;
         this.partner = var3;
         this.partnerName = var4;
      }

      UUID other(UUID var1) {
         return var1.equals(this.initiator) ? this.partner : this.initiator;
      }

      Player otherPlayer(Player var1) {
         Player var2 = Bukkit.getPlayer(this.other(var1.getUniqueId()));
         return var2 != null && var2.isOnline() ? var2 : null;
      }

      void clearBothViews() {
         if (this.initiatorView != null) {
            this.initiatorView.clear();
         }

         if (this.partnerView != null) {
            this.partnerView.clear();
         }
      }

      List<ItemStack> collectItems() {
         ArrayList var1 = new ArrayList();
         if (this.initiatorView != null) {
            for (ItemStack var5 : this.initiatorView.getContents()) {
               if (var5 != null && !var5.getType().isAir()) {
                  var1.add(var5.clone());
               }
            }
         }

         if (this.partnerView != null) {
            for (ItemStack var9 : this.partnerView.getContents()) {
               if (var9 != null && !var9.getType().isAir()) {
                  var1.add(var9.clone());
               }
            }
         }

         return var1;
      }
   }
}
