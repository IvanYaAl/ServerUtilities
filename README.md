# ServerUtilities

**Русский** · [English](#english) · [Español](#español)

Плагин «всё в одном» для серверов **Paper 1.21.8** (нужна **Java 21**): экономика, заказы, магазин, аукцион, кланы с приватами территорий, авторизация и многое другое.
Автор: **IvanYaAl** · Лицензия: Apache 2.0 (указание автора обязательно).

> **Актуальная версия — `ServerUtilities-2.0.0.jar`**

---

<a id="русский"></a>

## Оглавление

1. [Что умеет плагин](#ru-features)
2. [Установка](#ru-install)
3. [Обновление со старой версии](#ru-update)
4. [Команды](#ru-commands)
5. [Приваты кланов](#ru-claims)
6. [Хранилище, заказы и аукцион](#ru-storage)
7. [Права](#ru-perms)
8. [Файлы плагина](#ru-files)
9. [Сборка из исходников](#ru-build)
10. [История версий](#ru-changelog)
11. [Известные ограничения](#ru-limits)

<a id="ru-features"></a>

## Что умеет плагин

- **Экономика:** своя валюта (монеты), награды за убийство мобов, переводы `/pay`, топ игроков `/top`, скорборд с балансом.
- **Торговля:** доска заказов игроков, скупщик всех предметов `/buyer`, магазин сервера `/shop`, аукцион `/auction` со ставками и мгновенным выкупом, автопродажа `/autosell`, безопасный обмен `/trade`.
- **Хранение:** оплата заказов и лоты аукциона хранятся у плагина, поэтому торговать можно, даже когда владелец не в сети. Все доставки, которые игрок не смог получить сразу, ждут его в `/storage`.
- **Кланы:** ранги, казна, улучшения и **приваты территорий** с картой чанков и границами из частиц.
- **Авторизация:** `/reg` и `/login`, до входа игрок заморожен.
- **Главное меню** `/ogui` со всеми функциями.
- **Безопасные обновления:** бэкапы данных и автоматическое дополнение конфига.

<a id="ru-install"></a>

## Установка

1. Скачайте `ServerUtilities-2.0.0.jar` из этого репозитория (файл → **Download raw file**).
2. Положите его в папку `plugins/` сервера Paper 1.21.x.
3. Перезапустите сервер. В `plugins/ServerUtilities/` появится `config.yml`, где настраиваются цены, комиссии и лимиты.

<a id="ru-update"></a>

## Обновление со старой версии

1. Замените старый jar в `plugins/` на новый и перезапустите сервер.
2. **Конфиги удалять не нужно.** Новые настройки сами допишутся в `config.yml`, ваши значения останутся как есть.
3. Перед обновлением все файлы данных автоматически копируются в `plugins/ServerUtilities/backups/<дата>_<старая>-to-<новая>/` (хранятся последние 10 копий).
4. Если какой-то файл данных повреждён, плагин сохранит его копию `*.broken-<дата>.yml` и напишет об этом в консоль. Восстановить файл можно из `backups/`.

<a id="ru-commands"></a>

## Команды

| Команда | Описание |
|---|---|
| `/ogui` (`/menu`, `/gui`) | Главное меню со всеми функциями |
| `/money` | Баланс монет |
| `/pay <игрок> <сумма>` | Перевести монеты (с комиссией из конфига) |
| `/top` | Топ игроков |
| `/orders` (`/orderboard`) | Доска заказов: клик по заказу выполняет его |
| `/order create <нужно> <даю>` | Заказ за предметы: в правой руке образец нужного предмета, в левой — оплата |
| `/order create <нужно> money <монеты>` | Заказ за монеты |
| `/order cancel <id>` | Отменить свой заказ, оплата вернётся |
| `/buyer` | Скупщик: покупает любые предметы, цена меняется со спросом |
| `/shop` (`/store`) | Магазин сервера: ЛКМ — стак, ПКМ — одна штука |
| `/auction` (`/ah`) | Аукцион: ЛКМ — ставка +10%, ПКМ — +1, Shift+клик — выкуп |
| `/auction sell <мин. цена> [выкуп]` | Выставить предмет из руки |
| `/auction cancel <id>` | Снять свой лот |
| `/autosell` | Автопродажа предметов |
| `/trade <игрок>` | Безопасный обмен с подтверждением |
| `/storage` (`/stash`, `/sklad`) | Личное хранилище доставленных предметов |
| `/reg <пароль> <пароль>` · `/login <пароль>` | Регистрация и вход |
| `/clan create <тег> <название>` | Создать клан |
| `/clan invite <ник>` · `/clan join` · `/clan leave` · `/clan kick <ник>` | Состав клана |
| `/clan promote <ник> <leader\|officer\|member>` | Ранги |
| `/clan deposit <сумма>` · `/clan withdraw <сумма>` | Казна клана |
| `/clan upgrade` | Улучшить клан (больше мест) |
| `/clan private` | Получить ядро привата (только лидер) |
| `/clan private info` | Сколько чанков заприватено и цена следующего |
| `/clan private borders` | Показать или скрыть границы приватов |
| `/clan list` · `/clan disband` | Список кланов, роспуск |

<a id="ru-claims"></a>

## Приваты кланов

1. **Лидер** пишет `/clan private` и получает **ядро привата** — особый маяк.
2. Ядро ставится в нужном чанке, и этот чанк сразу приватится.
3. **ПКМ по ядру** открывает карту чанков 9×5: верх — север, ядро в центре.
   - зелёный — ваш чанк
   - жёлтый — можно купить (только рядом с вашей территорией)
   - красный — чанк другого клана
   - серый — свободен, но не граничит с вашей территорией
4. **Цена растёт с каждым чанком:** 5000 → 10000 → 15000 → … Платится из казны клана (`/clan deposit`).
5. **Участники клана пользуются приватом свободно:** строят, ломают, открывают сундуки и двери, а также могут докупать соседние чанки через меню ядра.
6. **Снимать чанки** (Shift+клик в меню) и **весь приват** (сломать ядро, зажав Shift) может только лидер. Ядро возвращается, деньги нет. При роспуске клана приват снимается.
7. **Границы привата из частиц:** зелёные — ваш клан, красные — чужие. Каждый игрок видит их только сам и включает или выключает кнопкой с глазом Эндера в меню ядра или командой `/clan private borders`. Выбор сохраняется.
8. **Чужие игроки на территории не могут:**
   - ломать и ставить блоки;
   - открывать сундуки, двери и кнопки;
   - пользоваться вёдрами и огнивом;
   - трогать рамки, стойки, животных и жителей.
9. **Территория защищена:**
   - от взрывов, огня, эндерменов и иссушителей;
   - от воды и лавы, которые текут снаружи;
   - от поршней, которые двигают блоки через границу.

   PvP не меняется. Над хотбаром показывается, на чьей территории находится игрок.

Настройки — в `config.yml`, секция `clan.private`:

| Параметр | По умолчанию | Описание |
|---|---|---|
| `base-cost` | 5000 | Цена первого чанка |
| `cost-step` | 5000 | Насколько дорожает каждый следующий |
| `max-chunks` | 16 | Максимум чанков у клана |
| `pay-from-treasury` | true | Платить из казны (false — с личного баланса) |
| `members-can-claim` | true | Участники могут докупать чанки |
| `officers-can-manage` | false | Офицеры могут снимать чанки |
| `protect-explosions` | true | Защита от взрывов, огня и мобов |
| `border-messages` | true | Надпись о территории над хотбаром |
| `borders.default-visible` | true | Показывать границы по умолчанию |
| `borders.interval-ticks` | 20 | Частота перерисовки (20 = 1 сек) |
| `borders.view-distance` | 48 | Дальность видимости границ, блоков |

<a id="ru-storage"></a>

## Хранилище, заказы и аукцион

- **Заказы:** при создании оплата (предметы или монеты) сразу забирается на хранение. Выполнить заказ можно, даже когда владельца нет в сети, и полученные предметы придут ему. При отмене оплата возвращается.
- **Аукцион:** предмет хранится у аукциона. Ставки и выкуп работают без продавца, монеты сразу приходят ему на баланс. Победитель получает предмет, даже если был не в сети, когда торги закончились.
- **`/storage`:** сюда приходят предметы, пока игрок не в сети или у него полный инвентарь. Клик по предмету забирает его, сундук внизу забирает всё. При входе приходит напоминание.

<a id="ru-perms"></a>

## Права

| Право | По умолчанию | Описание |
|---|---|---|
| `serverutilities.admin` | op | Все права администратора (включает `tradeorders.admin`) |
| `tradeorders.admin` | op | Отмена чужих заказов и лотов, доступ ко всем приватам |

<a id="ru-files"></a>

## Файлы плагина

Все в `plugins/ServerUtilities/`:

- **Настройки:** `config.yml`
- **Экономика:** `balances.yml`
- **Торговля:** `orders.yml`, `auctions.yml`, `market.yml`, `shop.yml`, `sales.yml`
- **Хранилище:** `storage.yml`
- **Кланы:** `clans.yml`, `claims.yml` (приваты и настройки границ)
- **Аккаунты:** `accounts.yml` (пароли хранятся в виде хэша)
- **Резервные копии:** `backups/`

<a id="ru-build"></a>

## Сборка из исходников

Исходный код лежит в папке [`source/`](source/). Понадобится JDK 21 и jar-файлы Paper API. Проще всего взять папку `libraries` уже запущенного сервера Paper 1.21.8:

```bash
cd source
./build.sh /путь/к/серверу/libraries
# результат: build/ServerUtilities-<версия>.jar
```

<a id="ru-changelog"></a>

## История версий

| Версия | Что нового |
|---|---|
| **2.0.0** | **Прорыв:** участники клана свободно пользуются приватом и могут докупать чанки. Визуальные границы приватов из частиц с переключателем в меню ядра и командой `/clan private borders` |
| 1.5.0 | Приваты кланов: `/clan private`, ядро-маяк, карта чанков, растущая цена, полная защита территории |
| 1.4.0 | Хранение для заказов и аукциона, `/storage`, мгновенный выкуп лотов, исправлен денежный заказ |
| 1.3.4 | Автодополнение `config.yml` без удаления, бэкапы при обновлении, защита повреждённых файлов |
| 1.3.3 | Исправлена ошибка в `plugin.yml`, из-за которой плагин не загружался и не работала ни одна команда. Работают все алиасы |
| 1.3.2 | Главное меню `/ogui` (не загружается — используйте 1.3.3+) |
| 1.3.1 | `/clan upgrade` — улучшение клана за монеты |
| 1.3.0 | Переименование в ServerUtilities: `/top`, `/autosell`, `/pay`, скорборд, `/trade`, кланы, авторизация |
| 1.2.0 | TradeOrders: скупщик всех предметов, `/shop`, `/auction` |
| 1.1.0 | TradeOrders: заказы, монеты, скупщик |

Все старые jar-файлы оставлены в репозитории.

<a id="ru-limits"></a>

## Известные ограничения

- Экономика своя и не связана с Vault и другими плагинами экономики.
- Тестировалось на Paper 1.21.8. На других версиях работа не гарантируется.
- Приватить можно в пределах карты 9×5 чанков вокруг ядра, один приват на клан.

---
---

<a id="english"></a>

# English

**[Русский](#русский)** · English · [Español](#español)

An all-in-one plugin for **Paper 1.21.8** servers (requires **Java 21**): economy, orders, shop, auction, clans with land claims, login system and more.
Author: **IvanYaAl** · License: Apache 2.0 (attribution required).

> **Current version: `ServerUtilities-2.0.0.jar`**

## Table of contents

1. [Features](#en-features)
2. [Installation](#en-install)
3. [Updating from an older version](#en-update)
4. [Commands](#en-commands)
5. [Clan land claims](#en-claims)
6. [Storage, orders and auction](#en-storage)
7. [Permissions](#en-perms)
8. [Plugin files](#en-files)
9. [Building from source](#en-build)
10. [Changelog](#en-changelog)
11. [Known limitations](#en-limits)

<a id="en-features"></a>

## Features

- **Economy:** custom currency (coins), mob kill rewards, `/pay` transfers, `/top` leaderboard, balance scoreboard.
- **Trading:**
  - player order board;
  - `/buyer` that buys any item;
  - `/shop`;
  - `/auction` with bids and instant buyout;
  - `/autosell`;
  - safe `/trade`.
- **Storage:** order payments and auction lots are held by the plugin, so trading works even when the owner is offline. Anything a player could not receive right away waits in `/storage`.
- **Clans:** ranks, treasury, upgrades and **land claims** with a chunk map and particle borders.
- **Login system:** `/reg` and `/login`, players are frozen until they log in.
- **Main menu** `/ogui` with every feature.
- **Safe updates:** data backups and automatic config completion.

<a id="en-install"></a>

## Installation

1. Download `ServerUtilities-2.0.0.jar` from this repository (file → **Download raw file**).
2. Put it into the `plugins/` folder of a Paper 1.21.x server.
3. Restart the server. `plugins/ServerUtilities/config.yml` will be created, where prices, fees and limits are configured.

<a id="en-update"></a>

## Updating from an older version

1. Replace the old jar in `plugins/` with the new one and restart the server.
2. **No need to delete configs.** New settings are added to `config.yml` automatically, your values stay untouched.
3. Before updating, all data files are automatically copied to `plugins/ServerUtilities/backups/<date>_<old>-to-<new>/` (the last 10 backups are kept).
4. If a data file is corrupted, the plugin saves a copy `*.broken-<date>.yml` and reports it in the console. You can restore the file from `backups/`.

<a id="en-commands"></a>

## Commands

| Command | Description |
|---|---|
| `/ogui` (`/menu`, `/gui`) | Main menu with all features |
| `/money` | Coin balance |
| `/pay <player> <amount>` | Send coins (fee set in config) |
| `/top` | Leaderboard |
| `/orders` (`/orderboard`) | Order board: click an order to fulfil it |
| `/order create <wanted> <offer>` | Item order: sample of the wanted item in main hand, payment in off-hand |
| `/order create <wanted> money <coins>` | Coin order |
| `/order cancel <id>` | Cancel your order, payment is returned |
| `/buyer` | Buyer: buys any item, price follows demand |
| `/shop` (`/store`) | Server shop: left click buys a stack, right click buys one |
| `/auction` (`/ah`) | Auction: left click +10% bid, right click +1, Shift+click buyout |
| `/auction sell <min price> [buyout]` | List the item in your hand |
| `/auction cancel <id>` | Remove your lot |
| `/autosell` | Automatic item selling |
| `/trade <player>` | Safe trade with confirmation |
| `/storage` (`/stash`, `/sklad`) | Personal storage of delivered items |
| `/reg <password> <password>` · `/login <password>` | Register and log in |
| `/clan create <tag> <name>` | Create a clan |
| `/clan invite <player>` · `/clan join` · `/clan leave` · `/clan kick <player>` | Clan members |
| `/clan promote <player> <leader\|officer\|member>` | Ranks |
| `/clan deposit <amount>` · `/clan withdraw <amount>` | Clan treasury |
| `/clan upgrade` | Upgrade the clan (more member slots) |
| `/clan private` | Get the claim core (leader only) |
| `/clan private info` | Claimed chunks and price of the next one |
| `/clan private borders` | Show or hide claim borders |
| `/clan list` · `/clan disband` | List clans, disband |

<a id="en-claims"></a>

## Clan land claims

1. The **leader** types `/clan private` and gets a **claim core**, a special beacon.
2. Place the core in the chunk you want, and that chunk is claimed immediately.
3. **Right-click the core** to open a 9×5 chunk map (north is up, the core is in the centre).
   - green: your chunk
   - yellow: can be bought (only next to your territory)
   - red: another clan's chunk
   - grey: free, but not next to your territory
4. **Each chunk costs more:** 5000 → 10000 → 15000 → … Paid from the clan treasury (`/clan deposit`).
5. **Clan members use the claim freely:** they build, break, open chests and doors, and can also buy neighbouring chunks through the core menu.
6. **Only the leader** can unclaim chunks (Shift+click in the menu) or remove the whole claim (break the core while sneaking). The core is returned, the money is not. Disbanding a clan removes its claim.
7. **Particle claim borders:** green for your clan, red for others. Each player sees them privately and toggles them with the Eye of Ender button in the core menu or with `/clan private borders`. The choice is saved.
8. **Outsiders on the territory cannot:**
   - break or place blocks;
   - open chests, doors or buttons;
   - use buckets or flint and steel;
   - touch item frames, armour stands, animals or villagers.
9. **The territory is protected from:**
   - explosions, fire, endermen and withers;
   - water and lava flowing in from outside;
   - pistons moving blocks across the border.

   PvP is unchanged. The action bar shows whose territory the player is on.

Settings are in `config.yml`, section `clan.private`:

| Option | Default | Description |
|---|---|---|
| `base-cost` | 5000 | Price of the first chunk |
| `cost-step` | 5000 | Price increase for each next chunk |
| `max-chunks` | 16 | Max chunks per clan |
| `pay-from-treasury` | true | Pay from treasury (false: leader's personal balance) |
| `members-can-claim` | true | Members can buy chunks |
| `officers-can-manage` | false | Officers can unclaim chunks |
| `protect-explosions` | true | Protection from explosions, fire and mobs |
| `border-messages` | true | Territory message in the action bar |
| `borders.default-visible` | true | Borders shown by default |
| `borders.interval-ticks` | 20 | Redraw interval (20 = 1 s) |
| `borders.view-distance` | 48 | Border view distance, blocks |

<a id="en-storage"></a>

## Storage, orders and auction

- **Orders:** the payment (items or coins) is taken into storage as soon as the order is created. Anyone can fulfil the order while the owner is offline, and the items are delivered to the owner. Cancelling returns the payment.
- **Auction:** the item is held by the auction. Bids and buyouts work without the seller, and the coins go straight to the seller's balance. The winner gets the item even if they were offline when the auction ended.
- **`/storage`:** items arrive here while the player is offline or their inventory is full. Click an item to take it, or click the chest at the bottom to take everything. Players get a reminder when they join.

<a id="en-perms"></a>

## Permissions

| Permission | Default | Description |
|---|---|---|
| `serverutilities.admin` | op | All admin rights (includes `tradeorders.admin`) |
| `tradeorders.admin` | op | Cancel other players' orders and lots, access to all claims |

<a id="en-files"></a>

## Plugin files

All in `plugins/ServerUtilities/`:

- **Settings:** `config.yml`
- **Economy:** `balances.yml`
- **Trading:** `orders.yml`, `auctions.yml`, `market.yml`, `shop.yml`, `sales.yml`
- **Storage:** `storage.yml`
- **Clans:** `clans.yml`, `claims.yml` (claims and border settings)
- **Accounts:** `accounts.yml` (passwords are stored hashed)
- **Backups:** `backups/`

<a id="en-build"></a>

## Building from source

The source code is in the [`source/`](source/) folder. You need JDK 21 and the Paper API jars. The easiest option is the `libraries` folder of a Paper 1.21.8 server that has already been started:

```bash
cd source
./build.sh /path/to/server/libraries
# output: build/ServerUtilities-<version>.jar
```

<a id="en-changelog"></a>

## Changelog

| Version | What's new |
|---|---|
| **2.0.0** | **Breakthrough:** clan members use the claim freely and can buy chunks. Particle claim borders with a toggle in the core menu and `/clan private borders` |
| 1.5.0 | Clan land claims: `/clan private`, beacon core, chunk map, rising price, full territory protection |
| 1.4.0 | Storage for orders and auction, `/storage`, instant lot buyout, coin order fixed |
| 1.3.4 | `config.yml` auto-completion without deleting, backups on update, protection of corrupted files |
| 1.3.3 | Fixed the `plugin.yml` bug that stopped the plugin from loading, so no command worked. All aliases work |
| 1.3.2 | `/ogui` main menu (does not load, use 1.3.3+) |
| 1.3.1 | `/clan upgrade`: clan upgrades for coins |
| 1.3.0 | Renamed to ServerUtilities: `/top`, `/autosell`, `/pay`, scoreboard, `/trade`, clans, login |
| 1.2.0 | TradeOrders: buyer for all items, `/shop`, `/auction` |
| 1.1.0 | TradeOrders: orders, coins, buyer |

All older jar files are kept in the repository.

<a id="en-limits"></a>

## Known limitations

- The economy is standalone and not connected to Vault or other economy plugins.
- Tested on Paper 1.21.8. Other versions are not guaranteed.
- Claims are limited to the 9×5 chunk map around the core, one claim per clan.

---
---

<a id="español"></a>

# Español

**[Русский](#русский)** · [English](#english) · Español

Plugin todo en uno para servidores **Paper 1.21.8** (requiere **Java 21**): economía, pedidos, tienda, subastas, clanes con protección de terreno, sistema de inicio de sesión y más.
Autor: **IvanYaAl** · Licencia: Apache 2.0 (se requiere atribución).

> **Versión actual: `ServerUtilities-2.0.0.jar`**

## Índice

1. [Funciones](#es-features)
2. [Instalación](#es-install)
3. [Actualizar desde una versión anterior](#es-update)
4. [Comandos](#es-commands)
5. [Protección de terreno de clanes](#es-claims)
6. [Almacén, pedidos y subasta](#es-storage)
7. [Permisos](#es-perms)
8. [Archivos del plugin](#es-files)
9. [Compilar desde el código fuente](#es-build)
10. [Historial de versiones](#es-changelog)
11. [Limitaciones conocidas](#es-limits)

<a id="es-features"></a>

## Funciones

- **Economía:** moneda propia (monedas), recompensas por matar mobs, transferencias `/pay`, ranking `/top`, marcador con el saldo.
- **Comercio:**
  - tablón de pedidos de jugadores;
  - comprador de cualquier objeto `/buyer`;
  - tienda `/shop`;
  - subasta `/auction` con pujas y compra inmediata;
  - venta automática `/autosell`;
  - intercambio seguro `/trade`.
- **Almacén:** los pagos de los pedidos y los lotes de la subasta los guarda el plugin, así que se puede comerciar aunque el dueño no esté conectado. Lo que el jugador no pudo recibir al momento le espera en `/storage`.
- **Clanes:** rangos, tesorería, mejoras y **protección de terreno** con mapa de chunks y bordes de partículas.
- **Inicio de sesión:** `/reg` y `/login`, el jugador queda congelado hasta entrar.
- **Menú principal** `/ogui` con todas las funciones.
- **Actualizaciones seguras:** copias de seguridad de los datos y completado automático de la configuración.

<a id="es-install"></a>

## Instalación

1. Descarga `ServerUtilities-2.0.0.jar` de este repositorio (archivo → **Download raw file**).
2. Colócalo en la carpeta `plugins/` de un servidor Paper 1.21.x.
3. Reinicia el servidor. Se creará `plugins/ServerUtilities/config.yml`, donde se configuran precios, comisiones y límites.

<a id="es-update"></a>

## Actualizar desde una versión anterior

1. Sustituye el jar antiguo en `plugins/` por el nuevo y reinicia el servidor.
2. **No hace falta borrar la configuración.** Los ajustes nuevos se añaden solos a `config.yml` y tus valores no cambian.
3. Antes de actualizar, todos los archivos de datos se copian automáticamente a `plugins/ServerUtilities/backups/<fecha>_<antigua>-to-<nueva>/` (se guardan las 10 últimas copias).
4. Si un archivo de datos está dañado, el plugin guarda una copia `*.broken-<fecha>.yml` y lo avisa en la consola. Puedes restaurar el archivo desde `backups/`.

<a id="es-commands"></a>

## Comandos

| Comando | Descripción |
|---|---|
| `/ogui` (`/menu`, `/gui`) | Menú principal con todas las funciones |
| `/money` | Saldo de monedas |
| `/pay <jugador> <cantidad>` | Enviar monedas (comisión según la configuración) |
| `/top` | Ranking de jugadores |
| `/orders` (`/orderboard`) | Tablón de pedidos: haz clic en un pedido para cumplirlo |
| `/order create <pido> <doy>` | Pedido por objetos: muestra del objeto pedido en la mano principal, pago en la mano secundaria |
| `/order create <pido> money <monedas>` | Pedido por monedas |
| `/order cancel <id>` | Cancelar tu pedido, el pago se devuelve |
| `/buyer` | Comprador: compra cualquier objeto, el precio sigue la demanda |
| `/shop` (`/store`) | Tienda del servidor: clic izquierdo compra un stack, clic derecho una unidad |
| `/auction` (`/ah`) | Subasta: clic izquierdo puja +10%, clic derecho +1, Shift+clic compra inmediata |
| `/auction sell <precio mín.> [compra inmediata]` | Subastar el objeto de la mano |
| `/auction cancel <id>` | Retirar tu lote |
| `/autosell` | Venta automática de objetos |
| `/trade <jugador>` | Intercambio seguro con confirmación |
| `/storage` (`/stash`, `/sklad`) | Almacén personal de objetos entregados |
| `/reg <contraseña> <contraseña>` · `/login <contraseña>` | Registro e inicio de sesión |
| `/clan create <etiqueta> <nombre>` | Crear un clan |
| `/clan invite <jugador>` · `/clan join` · `/clan leave` · `/clan kick <jugador>` | Miembros del clan |
| `/clan promote <jugador> <leader\|officer\|member>` | Rangos |
| `/clan deposit <cantidad>` · `/clan withdraw <cantidad>` | Tesorería del clan |
| `/clan upgrade` | Mejorar el clan (más plazas) |
| `/clan private` | Obtener el núcleo de protección (solo el líder) |
| `/clan private info` | Chunks protegidos y precio del siguiente |
| `/clan private borders` | Mostrar u ocultar los bordes de las protecciones |
| `/clan list` · `/clan disband` | Lista de clanes, disolver |

<a id="es-claims"></a>

## Protección de terreno de clanes

1. El **líder** escribe `/clan private` y recibe un **núcleo de protección**, un faro especial.
2. Coloca el núcleo en el chunk que quieras, y ese chunk queda protegido al instante.
3. **Clic derecho en el núcleo** abre un mapa de chunks de 9×5 (el norte arriba, el núcleo en el centro).
   - verde: tu chunk
   - amarillo: se puede comprar (solo junto a tu territorio)
   - rojo: chunk de otro clan
   - gris: libre, pero no está junto a tu territorio
4. **Cada chunk cuesta más:** 5000 → 10000 → 15000 → … Se paga desde la tesorería del clan (`/clan deposit`).
5. **Los miembros del clan usan la protección libremente:** construyen, rompen, abren cofres y puertas, y también pueden comprar chunks vecinos desde el menú del núcleo.
6. **Solo el líder** puede liberar chunks (Shift+clic en el menú) o quitar toda la protección (romper el núcleo agachado). El núcleo se devuelve, el dinero no. Al disolver el clan se quita su protección.
7. **Bordes de partículas:** verdes para tu clan, rojos para los demás. Cada jugador los ve solo para sí y los activa o desactiva con el botón del ojo de Ender en el menú del núcleo o con `/clan private borders`. La elección se guarda.
8. **Los jugadores ajenos en el territorio no pueden:**
   - romper ni colocar bloques;
   - abrir cofres, puertas ni botones;
   - usar cubos ni mecheros;
   - tocar marcos, soportes de armadura, animales ni aldeanos.
9. **El territorio está protegido contra:**
   - explosiones, fuego, endermans y withers;
   - agua y lava que entren desde fuera;
   - pistones que muevan bloques a través del borde.

   El PvP no cambia. La barra de acción muestra de quién es el territorio en el que está el jugador.

Ajustes en `config.yml`, sección `clan.private`:

| Opción | Por defecto | Descripción |
|---|---|---|
| `base-cost` | 5000 | Precio del primer chunk |
| `cost-step` | 5000 | Aumento de precio de cada chunk siguiente |
| `max-chunks` | 16 | Máximo de chunks por clan |
| `pay-from-treasury` | true | Pagar desde la tesorería (false: saldo personal del líder) |
| `members-can-claim` | true | Los miembros pueden comprar chunks |
| `officers-can-manage` | false | Los oficiales pueden liberar chunks |
| `protect-explosions` | true | Protección contra explosiones, fuego y mobs |
| `border-messages` | true | Mensaje de territorio en la barra de acción |
| `borders.default-visible` | true | Bordes visibles por defecto |
| `borders.interval-ticks` | 20 | Intervalo de redibujado (20 = 1 s) |
| `borders.view-distance` | 48 | Distancia de visión de los bordes, bloques |

<a id="es-storage"></a>

## Almacén, pedidos y subasta

- **Pedidos:** el pago (objetos o monedas) se guarda en el almacén en cuanto se crea el pedido. Cualquiera puede cumplirlo aunque el dueño no esté conectado, y los objetos le llegan al dueño. Al cancelar se devuelve el pago.
- **Subasta:** el objeto lo guarda la subasta. Las pujas y la compra inmediata funcionan sin el vendedor, y las monedas van directamente a su saldo. El ganador recibe el objeto aunque no estuviera conectado al terminar la subasta.
- **`/storage`:** aquí llegan los objetos mientras el jugador no está conectado o tiene el inventario lleno. Haz clic en un objeto para cogerlo, o en el cofre de abajo para cogerlo todo. Al entrar llega un recordatorio.

<a id="es-perms"></a>

## Permisos

| Permiso | Por defecto | Descripción |
|---|---|---|
| `serverutilities.admin` | op | Todos los permisos de administrador (incluye `tradeorders.admin`) |
| `tradeorders.admin` | op | Cancelar pedidos y lotes ajenos, acceso a todas las protecciones |

<a id="es-files"></a>

## Archivos del plugin

Todos en `plugins/ServerUtilities/`:

- **Ajustes:** `config.yml`
- **Economía:** `balances.yml`
- **Comercio:** `orders.yml`, `auctions.yml`, `market.yml`, `shop.yml`, `sales.yml`
- **Almacén:** `storage.yml`
- **Clanes:** `clans.yml`, `claims.yml` (protecciones y ajustes de bordes)
- **Cuentas:** `accounts.yml` (las contraseñas se guardan cifradas con hash)
- **Copias de seguridad:** `backups/`

<a id="es-build"></a>

## Compilar desde el código fuente

El código fuente está en la carpeta [`source/`](source/). Necesitas JDK 21 y los jar de Paper API. Lo más fácil es usar la carpeta `libraries` de un servidor Paper 1.21.8 que ya se haya iniciado:

```bash
cd source
./build.sh /ruta/al/servidor/libraries
# resultado: build/ServerUtilities-<versión>.jar
```

<a id="es-changelog"></a>

## Historial de versiones

| Versión | Novedades |
|---|---|
| **2.0.0** | **Gran avance:** los miembros del clan usan la protección libremente y pueden comprar chunks. Bordes de partículas con interruptor en el menú del núcleo y `/clan private borders` |
| 1.5.0 | Protección de terreno de clanes: `/clan private`, núcleo-faro, mapa de chunks, precio creciente, protección completa del territorio |
| 1.4.0 | Almacén para pedidos y subasta, `/storage`, compra inmediata de lotes, corregido el pedido por monedas |
| 1.3.4 | Completado automático de `config.yml` sin borrarlo, copias de seguridad al actualizar, protección de archivos dañados |
| 1.3.3 | Corregido el error de `plugin.yml` que impedía cargar el plugin, por lo que ningún comando funcionaba. Todos los alias funcionan |
| 1.3.2 | Menú principal `/ogui` (no carga, usa 1.3.3+) |
| 1.3.1 | `/clan upgrade`: mejoras del clan por monedas |
| 1.3.0 | Renombrado a ServerUtilities: `/top`, `/autosell`, `/pay`, marcador, `/trade`, clanes, inicio de sesión |
| 1.2.0 | TradeOrders: comprador de todos los objetos, `/shop`, `/auction` |
| 1.1.0 | TradeOrders: pedidos, monedas, comprador |

Todos los jar antiguos se conservan en el repositorio.

<a id="es-limits"></a>

## Limitaciones conocidas

- La economía es propia y no está conectada a Vault ni a otros plugins de economía.
- Probado en Paper 1.21.8. No se garantiza el funcionamiento en otras versiones.
- La protección se limita al mapa de 9×5 chunks alrededor del núcleo, una protección por clan.
