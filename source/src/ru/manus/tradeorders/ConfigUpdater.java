package ru.manus.tradeorders;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Безопасное обновление плагина:
 *  1) при смене версии делает резервную копию всех .yml из папки плагина;
 *  2) проверяет, что файлы данных читаются (битый файл копируется в *.broken-*.yml,
 *     чтобы плагин не перезаписал его пустым);
 *  3) дописывает в существующий config.yml новые настройки из новой версии,
 *     не меняя уже заданные пользователем значения.
 */
public final class ConfigUpdater {
    private static final String VERSION_FILE = ".plugin-version";
    private static final int KEEP_BACKUPS = 10;
    /** Секции-«списки»: если секция уже есть у пользователя, новые элементы не добавляем
     *  (иначе удалённые вручную мобы/предметы возвращались бы после каждого обновления). */
    private static final Set<String> USER_LISTS = Set.of("mob-rewards", "buyer.base-prices", "shop.sell-prices");

    private ConfigUpdater() {}

    public static void run(JavaPlugin plugin) {
        try {
            doRun(plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("ConfigUpdater: " + t);
        }
    }

    private static void doRun(JavaPlugin plugin) throws IOException {
        Logger log = plugin.getLogger();
        File dir = plugin.getDataFolder();
        String current = plugin.getDescription().getVersion();
        if (!dir.isDirectory()) { // первый запуск — нечего обновлять
            dir.mkdirs();
            writeVersion(dir, current);
            return;
        }
        File[] ymls = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (ymls == null) ymls = new File[0];
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

        // 1. Бэкап при смене версии
        String previous = readVersion(dir);
        if (ymls.length > 0 && !current.equals(previous)) {
            String from = previous == null ? "old" : previous;
            File bdir = new File(new File(dir, "backups"), ts + "_" + from + "-to-" + current);
            bdir.mkdirs();
            for (File f : ymls) Files.copy(f.toPath(), new File(bdir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("Обновление " + from + " -> " + current + ": резервная копия данных сохранена в " + dir.getName() + "/backups/" + bdir.getName());
            pruneBackups(new File(dir, "backups"));
        }

        // 2. Проверка, что файлы данных читаются
        for (File f : ymls) {
            if (f.getName().equals("config.yml")) continue;
            try {
                new YamlConfiguration().load(f);
            } catch (InvalidConfigurationException e) {
                File broken = new File(dir, f.getName().replace(".yml", "") + ".broken-" + ts + ".yml");
                Files.copy(f.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.severe("Файл " + f.getName() + " повреждён и не читается! Копия сохранена как " + broken.getName()
                        + ". Восстановите его из папки backups. Ошибка: " + e.getMessage());
            }
        }

        // 3. Дополнение config.yml новыми настройками
        File cfgFile = new File(dir, "config.yml");
        InputStream res = plugin.getResource("config.yml");
        if (cfgFile.isFile() && res != null) {
            YamlConfiguration defaults;
            try (Reader r = new InputStreamReader(res, StandardCharsets.UTF_8)) {
                defaults = YamlConfiguration.loadConfiguration(r);
            }
            YamlConfiguration user = new YamlConfiguration();
            boolean userOk = true;
            try {
                user.load(cfgFile);
            } catch (InvalidConfigurationException e) {
                userOk = false;
                log.severe("config.yml содержит ошибку и не будет изменён автоматически: " + e.getMessage());
            }
            if (userOk) {
                List<String> added = new ArrayList<>();
                merge(defaults, user, "", added);
                if (!added.isEmpty()) {
                    user.save(cfgFile);
                    log.info("В config.yml добавлены новые настройки (" + added.size() + "): " + String.join(", ", added));
                }
            }
        }
        writeVersion(dir, current);
        plugin.reloadConfig();
    }

    private static void merge(ConfigurationSection def, YamlConfiguration user, String prefix, List<String> added) {
        for (String key : def.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = def.get(key);
            boolean isSection = def.isConfigurationSection(key);
            if (!user.contains(path, true)) {
                if (isSection) copySection(def.getConfigurationSection(key), user, path);
                else user.set(path, value);
                copyComments(def, key, user, path);
                added.add(path);
            } else if (isSection && user.isConfigurationSection(path) && !USER_LISTS.contains(path)) {
                merge(def.getConfigurationSection(key), user, path, added);
            }
        }
    }

    private static void copySection(ConfigurationSection src, YamlConfiguration user, String path) {
        user.createSection(path);
        for (String k : src.getKeys(false)) {
            String p = path + "." + k;
            if (src.isConfigurationSection(k)) copySection(src.getConfigurationSection(k), user, p);
            else user.set(p, src.get(k));
            copyComments(src, k, user, p);
        }
    }

    private static void copyComments(ConfigurationSection src, String key, YamlConfiguration user, String path) {
        try {
            List<String> c = src.getComments(key);
            if (c != null && !c.isEmpty()) user.setComments(path, c);
            List<String> ic = src.getInlineComments(key);
            if (ic != null && !ic.isEmpty()) user.setInlineComments(path, ic);
        } catch (Throwable ignored) { }
    }

    private static String readVersion(File dir) {
        try {
            return Files.readString(new File(dir, VERSION_FILE).toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeVersion(File dir, String v) {
        try {
            Files.writeString(new File(dir, VERSION_FILE).toPath(), v, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private static void pruneBackups(File bdir) {
        File[] list = bdir.listFiles(File::isDirectory);
        if (list == null || list.length <= KEEP_BACKUPS) return;
        Arrays.sort(list, Comparator.comparing(File::getName));
        for (int i = 0; i < list.length - KEEP_BACKUPS; i++) deleteRec(list[i]);
    }

    private static void deleteRec(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteRec(c);
        f.delete();
    }
}
