package com.lazyman.blessing;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BlessingPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, String> pendingTarget = new ConcurrentHashMap<>();
    private final Map<String, List<ArmorStand>> holoGroups = new HashMap<>();
    private final Map<String, Location> holoAnchors = new HashMap<>();
    private final Map<String, String> holoTypeMap = new HashMap<>();
    private final Map<UUID, String> statusSession = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private BukkitTask holoCycleTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zhufu").setExecutor(this);
        getCommand("zhufu").setTabCompleter(this);
        loadHologramsFromConfig();
        startHologramCycle();
        startParticleEffect();
    }

    @Override
    public void onDisable() {
        if (holoCycleTask != null) holoCycleTask.cancel();
        cleanupAllHolograms();
    }

    private void cleanupAllHolograms() {
        for (List<ArmorStand> group : holoGroups.values()) {
            for (ArmorStand as : group) if (as != null && as.isValid()) as.remove();
        }
    }

    // --- 1. 指令处理与全补指南 ---
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) { openMainMenu(p); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload") || sub.equals("hologram")) {
            if (!p.hasPermission("blessing.admin")) {
                p.sendMessage(translate("&c权限不足。你默认仅能使用 /zhufu 指令。"));
                return true;
            }
        }

        if (sub.equals("help")) {
            sendHelpMessage(p);
            return true;
        }

        if (sub.equals("reload")) {
            reloadConfig();
            if (holoCycleTask != null) holoCycleTask.cancel();
            cleanupAllHolograms();
            holoGroups.clear(); holoAnchors.clear(); holoTypeMap.clear();
            loadHologramsFromConfig();
            startHologramCycle();
            p.sendMessage(translate("&a[系统] 配置文件重载成功 (Spigot 指令交互增强版)。"));
            return true;
        }

        if (sub.equals("hologram") && args.length >= 3) {
            String action = args[1].toLowerCase(); String id = args[2];
            if (action.equals("create")) {
                String type = (args.length >= 4) ? args[3] : "ALL";
                createHologramGroup(id, p.getLocation(), type);
                saveHoloToConfig(id, p.getLocation(), type);
                p.sendMessage(translate("&a[系统] 全息标定成功: " + id));
            } else if (action.equals("delete")) {
                removeHologram(id); p.sendMessage(translate("&c[系统] 全息已移除。"));
            }
            return true;
        }
        sendHelpMessage(p);
        return true;
    }

    private void sendHelpMessage(Player p) {
        p.sendMessage(translate("&b&l&m-----&r &#0fb6ff&l云琉祝福系统 指南 &b&l&m-----"));
        p.sendMessage(translate("&e/zhufu &7- 呼出主菜单"));
        p.sendMessage(translate("&e/zhufu help &7- 获取帮助指南"));
        if (p.hasPermission("blessing.admin")) {
            p.sendMessage(translate("&e/zhufu reload &7- 重载系统数据及轮播频率"));
            p.sendMessage(translate("&e/zhufu hologram create [ID] [分类] &7- 创建全息点"));
            p.sendMessage(translate("&e/zhufu hologram delete [ID] &7- 删除全息点"));
        }
        p.sendMessage(translate("&b&l&m------------------------------"));
    }

    // --- 2. 界面核心 ---
    private void openMainMenu(Player p) {
        statusSession.remove(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(null, 9, translate(getConfig().getString("menu.main_title")));
        inv.setItem(getConfig().getInt("menu.post_button.slot"), createGuiItem("menu.post_button"));
        inv.setItem(getConfig().getInt("menu.my_button.slot"), createGuiItem("menu.my_button"));
        inv.setItem(getConfig().getInt("menu.all_button.slot"), createGuiItem("menu.all_button"));
        p.openInventory(inv);
    }

    private void openAllBlessings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§e大家的祝福");
        List<String> bList = getConfig().getStringList("data.blessings");
        for (String s : bList) {
            String[] parts = s.split("\\|"); if (parts.length < 4) continue;
            inv.addItem(createSimpleItem(Material.PAPER, "&b" + parts[1] + " &f对 &d" + parts[2] + " &f的祝福", "&7预览: &f" + parts[3] + "\n&8[点击进入互动菜单]"));
        }
        inv.setItem(49, createSimpleItem(Material.ARROW, "&7« 返回主页面", "")); p.openInventory(inv);
    }

    private void openMyBlessings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b我的祝福管理");
        String uuid = p.getUniqueId().toString();
        List<String> my = getConfig().getStringList("data.blessings").stream().filter(s -> s.startsWith(uuid)).toList();
        for (String s : my) {
            String[] parts = s.split("\\|");
            inv.addItem(createSimpleItem(Material.PAPER, "&f发给 &d" + parts[2] + " &f的祝福", "&7预览: &e" + parts[3] + "\n&8[点击进行修改/撤回]"));
        }
        inv.setItem(49, createSimpleItem(Material.ARROW, "&7« 返回主页面", "")); p.openInventory(inv);
    }

    private void openSocialManage(Player p, String data, boolean isOwner) {
        String key = String.valueOf(data.hashCode());
        statusSession.put(p.getUniqueId(), (isOwner ? "OWNER|" : "GUEST|") + data);
        Inventory inv = Bukkit.createInventory(null, 9, "§9互动选项");
        if (isOwner) {
            inv.setItem(0, createSimpleItem(Material.APPLE, "&c点赞名单", "&7获赞: " + getMeta(key, "likes").size()));
            inv.setItem(2, createSimpleItem(Material.BOOK, "&a查看评论", "&7评论: " + getMeta(key, "comments").size()));
            inv.setItem(4, createSimpleItem(Material.NAME_TAG, "&e修改内容", ""));
            inv.setItem(6, createSimpleItem(Material.BARRIER, "&4撤回祝福", ""));
        } else {
            boolean liked = getMeta(key, "likes").contains(p.getName());
            inv.setItem(1, createSimpleItem(liked ? Material.REDSTONE : Material.GUNPOWDER, liked ? "&c[已点赞]" : "&a[点赞] 支持他", ""));
            inv.setItem(3, createSimpleItem(Material.PAPER, "&b写评论", ""));
            inv.setItem(5, createSimpleItem(Material.BOOK, "&e看评论区", ""));
        }
        inv.setItem(8, createSimpleItem(Material.ARROW, "&7« 返回上一级", "")); p.openInventory(inv);
    }

    // --- 3. 互动逻辑修复 (关键：恢复点击复制功能) ---
    private void handleSocialNavigation(Player p, int slot) {
        String sess = statusSession.get(p.getUniqueId()); if (sess == null) return;
        String[] parts = sess.split("\\|", 2);
        boolean isOwner = parts[0].equals("OWNER"); String data = parts[1];
        if (isOwner) {
            if (slot == 0) openLikers(p, data); else if (slot == 2) openComments(p, data);
            else if (slot == 4) {
                statusSession.put(p.getUniqueId(), "EDIT_MODE|" + data);
                String oldContent = data.split("\\|")[3];
                p.closeInventory();
                p.sendMessage(translate("&7&m----------------------------------------"));
                p.sendMessage(translate("&b请输入新内容 (取消输入 cancel)。"));

                // 使用 Spigot Bungee API 恢复点击粘贴
                TextComponent tc = new TextComponent(translate("&e&l[点击此处快捷粘贴原内容]"));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, oldContent));
                p.spigot().sendMessage(tc);

                p.sendMessage(translate("&7&m----------------------------------------"));
            } else if (slot == 6) openConfirm(p, data);
        } else {
            if (slot == 1) { toggleLike(p, String.valueOf(data.hashCode())); openSocialManage(p, data, false); }
            else if (slot == 3) { statusSession.put(p.getUniqueId(), "COMMENT_MODE|" + data); p.closeInventory(); p.sendMessage(translate("&b请输入评论内容...")); }
            else if (slot == 5) openComments(p, data);
        }
    }

    // --- 4. 颜色与 API 基础修复 ---
    private String translate(String text) {
        if (text == null) return "";
        // 跨版本 Hex 颜色支持
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String color = matcher.group(1);
            try {
                matcher.appendReplacement(sb, net.md_5.bungee.api.ChatColor.of("#" + color).toString());
            } catch (Exception e) {
                matcher.appendReplacement(sb, ""); // 降级处理
            }
        }
        return ChatColor.translateAlternateColorCodes('&', matcher.appendTail(sb).toString());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack cur = event.getCurrentItem();
        if (cur == null || !cur.hasItemMeta()) return;
        event.setCancelled(true);
        String title = event.getView().getTitle();

        if (cur.getType() == Material.ARROW) { handleNavBack(p, title); return; }

        if (title.equals(translate(getConfig().getString("menu.main_title")))) {
            int s = event.getRawSlot();
            if (s == getConfig().getInt("menu.post_button.slot")) openTargetMenu(p);
            else if (s == getConfig().getInt("menu.my_button.slot")) openMyBlessings(p);
            else if (s == getConfig().getInt("menu.all_button.slot")) openAllBlessings(p);
        } else if (title.contains("大家的祝福")) {
            List<String> list = getConfig().getStringList("data.blessings");
            if (event.getRawSlot() < list.size()) openSocialManage(p, list.get(event.getRawSlot()), false);
        } else if (title.contains("我的祝福管理")) {
            List<String> my = getConfig().getStringList("data.blessings").stream().filter(s -> s.startsWith(p.getUniqueId().toString())).toList();
            if (event.getRawSlot() < my.size()) openSocialManage(p, my.get(event.getRawSlot()), true);
        } else if (title.contains("互动选项")) {
            handleSocialNavigation(p, event.getRawSlot());
        } else if (title.contains("确认要撤回")) {
            if (event.getRawSlot() == 2) executeDelete(p);
            else if (event.getRawSlot() == 6) handleNavBack(p, title);
        } else if (title.equals(translate(getConfig().getString("target_menu.title")))) {
            int s = event.getRawSlot();
            String t = (s == getConfig().getInt("target_menu.self.slot")) ? "自己" : (s == getConfig().getInt("target_menu.others.slot")) ? "他人" : (s == getConfig().getInt("target_menu.server.slot")) ? "全服" : null;
            if (t != null) { pendingTarget.put(p.getUniqueId(), t); p.closeInventory(); p.sendMessage(translate(getConfig().getString("messages.enter_blessing"))); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer(); String msg = event.getMessage();
        if (pendingTarget.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
            String target = pendingTarget.remove(p.getUniqueId());
            if (!msg.equalsIgnoreCase("cancel")) saveBlessing(p, target, msg);
            Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
            return;
        }
        if (statusSession.containsKey(p.getUniqueId())) {
            String sess = statusSession.get(p.getUniqueId());
            if (sess.contains("_MODE")) {
                event.setCancelled(true); statusSession.remove(p.getUniqueId());
                String data = sess.split("\\|", 2)[1];
                if (sess.startsWith("COMMENT_MODE")) {
                    addMeta(String.valueOf(data.hashCode()), "comments", p.getName() + "§7: " + msg);
                    Bukkit.getScheduler().runTask(this, () -> openSocialManage(p, data, false));
                } else if (sess.startsWith("EDIT_MODE")) {
                    updateBlessing(data, msg);
                    Bukkit.getScheduler().runTask(this, () -> openMyBlessings(p));
                }
            }
        }
    }

    // --- 5. 其余逻辑维持 ---
    private void startHologramCycle() {
        long interval = Math.max(20L, getConfig().getLong("hologram.update_interval", 100L));
        holoCycleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<String> b = getConfig().getStringList("data.blessings");
            if (b.isEmpty() || holoAnchors.isEmpty()) return;
            for (String id : holoAnchors.keySet()) {
                String type = holoTypeMap.getOrDefault(id, "ALL");
                List<String> pool = type.equals("ALL") ? b : b.stream().filter(s -> s.split("\\|")[2].equals(type)).toList();
                if (pool.isEmpty()) continue;
                String[] d = pool.get(new Random().nextInt(pool.size())).split("\\|");
                updateHoloView(id, d[1], d[2], d[3]);
            }
        }, 0L, interval);
    }

    private void updateHoloView(String id, String sender, String target, String msg) {
        Location loc = holoAnchors.get(id); if (loc == null || loc.getWorld() == null) return;
        List<ArmorStand> group = holoGroups.computeIfAbsent(id, k -> new ArrayList<>());
        for (ArmorStand as : group) if (as != null && as.isValid()) as.remove();
        group.clear();
        List<String> lines = new ArrayList<>();
        lines.add(getConfig().getString("hologram.footer", "&7&m-----"));
        List<String> splitMsg = splitText(msg, getConfig().getInt("hologram.max_line_length", 24));
        for (int i = splitMsg.size() - 1; i >= 0; i--) lines.add("&f" + splitMsg.get(i));
        lines.add("&e" + sender + " &f对 &d" + target + " &f说：");
        lines.add(getConfig().getString("hologram.header", "&#0fb6ff✦ 云琉祝福 ✦"));
        for (int i = 0; i < lines.size(); i++) {
            ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, i * 0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setCustomNameVisible(true);
            as.setCustomName(translate(lines.get(i))); group.add(as);
        }
    }

    private void handleNavBack(Player p, String title) {
        if (title.contains("选择") || title.contains("管理") || title.contains("大家")) openMainMenu(p);
        else {
            String sess = statusSession.getOrDefault(p.getUniqueId(), "");
            if (sess.isEmpty()) { openMainMenu(p); return; }
            String[] parts = sess.split("\\|", 2);
            boolean wasOwner = parts[0].equals("OWNER"); String data = parts[1];
            if (title.contains("互动选项")) { if (wasOwner) openMyBlessings(p); else openAllBlessings(p); }
            else if (title.contains("名单") || title.contains("评论") || title.contains("撤回")) openSocialManage(p, data, wasOwner);
        }
    }

    private void openLikers(Player p, String data) {
        Inventory inv = Bukkit.createInventory(null, 54, "§d点赞名单");
        getMeta(String.valueOf(data.hashCode()), "likes").forEach(n -> {
            ItemStack h = new ItemStack(Material.PLAYER_HEAD); SkullMeta m = (SkullMeta) h.getItemMeta();
            m.setOwningPlayer(Bukkit.getOfflinePlayer(n)); m.setDisplayName("§e" + n); h.setItemMeta(m); inv.addItem(h);
        });
        inv.setItem(49, createSimpleItem(Material.ARROW, "&7« 返回", "")); p.openInventory(inv);
    }

    private void openComments(Player p, String d) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6评论详情");
        getMeta(String.valueOf(d.hashCode()), "comments").forEach(c -> inv.addItem(createSimpleItem(Material.PAPER, c, "")));
        inv.setItem(49, createSimpleItem(Material.ARROW, "&7« 返回", "")); p.openInventory(inv);
    }

    private void openConfirm(Player p, String d) {
        Inventory inv = Bukkit.createInventory(null, 9, "§4确认要撤回这条吗？");
        inv.setItem(2, createSimpleItem(Material.LIME_WOOL, "&a确认撤回", ""));
        inv.setItem(6, createSimpleItem(Material.RED_WOOL, "&c取消操作", "")); p.openInventory(inv);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String a, String[] args) {
        if (args.length == 1) return Arrays.asList("help", "reload", "hologram").stream().filter(st -> st.startsWith(args[0])).collect(Collectors.toList());
        return new ArrayList<>();
    }

    private void startParticleEffect() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Location l : holoAnchors.values()) l.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, l.clone().add(0, 0.5, 0), 5, 0.5, 0.5, 0.5, 0.1);
        }, 0L, 20L);
    }

    private void openTargetMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 9, translate(getConfig().getString("target_menu.title")));
        inv.setItem(getConfig().getInt("target_menu.self.slot"), createGuiItem("target_menu.self"));
        inv.setItem(getConfig().getInt("target_menu.others.slot"), createGuiItem("target_menu.others"));
        inv.setItem(getConfig().getInt("target_menu.server.slot"), createGuiItem("target_menu.server"));
        inv.setItem(8, createSimpleItem(Material.ARROW, "&7« 返回", "")); p.openInventory(inv);
    }

    private void saveBlessing(Player p, String t, String m) { List<String> l = getConfig().getStringList("data.blessings"); l.add(p.getUniqueId()+"|"+p.getName()+"|"+t+"|"+m); getConfig().set("data.blessings", l); saveConfig(); p.sendMessage(translate(getConfig().getString("messages.success"))); }
    private void updateBlessing(String old, String n) { List<String> l = getConfig().getStringList("data.blessings"); int i = l.indexOf(old); if(i!=-1) { String[] p = old.split("\\|"); l.set(i, p[0]+"|"+p[1]+"|"+p[2]+"|"+n); getConfig().set("data.blessings", l); saveConfig(); } }
    private void executeDelete(Player p) { String sess = statusSession.remove(p.getUniqueId()); String data = sess.split("\\|", 2)[1]; List<String> l = getConfig().getStringList("data.blessings"); l.remove(data); getConfig().set("data.blessings", l); saveConfig(); openMyBlessings(p); }
    private void addMeta(String k, String t, String v) { List<String> l = getMeta(k, t); l.add(v); getConfig().set("data.metadata."+k+"."+t, l); saveConfig(); }
    private List<String> getMeta(String k, String t) { return getConfig().getStringList("data.metadata."+k+"."+t); }
    private void toggleLike(Player p, String k) { List<String> l = getMeta(k, "likes"); if(l.contains(p.getName())) l.remove(p.getName()); else l.add(p.getName()); getConfig().set("data.metadata."+k+".likes", l); saveConfig(); }
    private void createHologramGroup(String id, Location loc, String type) { removeHologram(id); holoAnchors.put(id, loc.clone()); holoTypeMap.put(id, type); }
    private void removeHologram(String id) { if (holoGroups.containsKey(id)) { holoGroups.get(id).forEach(ArmorStand::remove); holoGroups.remove(id); } holoAnchors.remove(id); holoTypeMap.remove(id); getConfig().set("data.holograms." + id, null); saveConfig(); }
    private void loadHologramsFromConfig() { if (!getConfig().contains("data.holograms")) return; for (String id : getConfig().getConfigurationSection("data.holograms").getKeys(false)) { String v = getConfig().getString("data.holograms." + id); String[] p = v.split(","); holoAnchors.put(id, new Location(Bukkit.getWorld(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]))); if (p.length >= 5) holoTypeMap.put(id, p[4]); } }
    private void saveHoloToConfig(String id, Location l, String t) { getConfig().set("data.holograms." + id, l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + t); saveConfig(); }
    private List<String> splitText(String t, int l) { List<String> r = new ArrayList<>(); StringBuilder sb = new StringBuilder(); for (char c : t.toCharArray()) { sb.append(c); if (sb.length() >= l) { r.add(sb.toString()); sb.setLength(0); } } if (sb.length() > 0) r.add(sb.toString()); return r; }
    private ItemStack createGuiItem(String p) { ItemStack i = new ItemStack(Material.valueOf(getConfig().getString(p + ".material", "PAPER"))); ItemMeta m = i.getItemMeta(); m.setDisplayName(translate(getConfig().getString(p + ".name"))); m.setLore(getConfig().getStringList(p + ".lore").stream().map(this::translate).collect(Collectors.toList())); i.setItemMeta(m); return i; }
    private ItemStack createSimpleItem(Material m, String n, String lore) { ItemStack i = new ItemStack(m); ItemMeta mt = i.getItemMeta(); mt.setDisplayName(translate(n)); if (lore != null && !lore.isEmpty()) { List<String> l = new ArrayList<>(); for (String line : lore.split("\n")) l.add(translate(line)); mt.setLore(l); } i.setItemMeta(mt); return i; }
}
