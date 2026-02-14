package com.lazyman.blessing;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BlessingPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // --- 核心变量池 (严禁删减，必须闭环) ---
    private final Map<UUID, String> pendingTarget = new ConcurrentHashMap<>();
    private final Map<String, List<ArmorStand>> holoGroups = new HashMap<>();
    private final Map<String, Location> holoAnchors = new HashMap<>();
    private final Map<String, String> holoTypeMap = new HashMap<>();
    private final Map<UUID, String> statusSession = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownMap = new HashMap<>();

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
        for (List<ArmorStand> group : holoGroups.values()) {
            for (ArmorStand as : group) as.remove();
        }
    }

    // --- 1. 指令处理系统 (指令拦截深度修复) ---
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) { openMainMenu(p); return true; }

        String sub = args[0].toLowerCase();

        // 允许所有玩家的指令
        if (sub.equals("help")) { sendHelpMessage(p); return true; }

        // 管理指令拦截: reload
        if (sub.equals("reload")) {
            if (!p.hasPermission("blessing.admin")) {
                p.sendMessage("§c权限不足。你默认仅能使用 /zhufu 指令。");
                return true;
            }
            reloadConfig();
            for (List<ArmorStand> g : holoGroups.values()) g.forEach(ArmorStand::remove);
            holoGroups.clear(); holoAnchors.clear(); holoTypeMap.clear();
            loadHologramsFromConfig();
            p.sendMessage("§a[系统] 配置文件重载成功。");
            return true;
        }

        // 管理指令拦截: hologram (深度修复，只要包含该子指令，无论后续参数，直接阻断)
        if (sub.equals("hologram")) {
            if (!p.hasPermission("blessing.admin")) {
                p.sendMessage("§c权限不足。你默认仅能使用 /zhufu 指令。");
                return true;
            }
            // 只有通过权限检查后，才处理后续逻辑
            if (args.length >= 3) {
                String action = args[1].toLowerCase(); String id = args[2];
                if (action.equals("create")) {
                    String type = (args.length >= 4) ? args[3] : "ALL";
                    createHologramGroup(id, p.getLocation(), type);
                    saveHoloToConfig(id, p.getLocation(), type);
                    p.sendMessage("§a[系统] 全息标定成功: " + id);
                } else if (action.equals("delete")) {
                    removeHologram(id); p.sendMessage("§c[系统] 全息已移除。");
                }
            } else {
                p.sendMessage("§c[用法] /zhufu hologram create/delete [ID]...");
            }
            return true;
        }

        sendHelpMessage(p);
        return true;
    }

    private void sendHelpMessage(Player p) {
        p.sendMessage("§b§l§m-----§r §x§0§f§b§6§f§l云琉祝福系统 指南 §b§l§m-----");
        p.sendMessage("§e/zhufu §7- 呼出主控制菜单");
        p.sendMessage("§e/zhufu help §7- 获取指令帮助");
        if (p.hasPermission("blessing.admin")) {
            p.sendMessage("§e/zhufu reload §7- 重载系统数据");
            p.sendMessage("§e/zhufu hologram create [ID] [分类] §7- 创建全息标定点");
            p.sendMessage("§e/zhufu hologram delete [ID] §7- 移除全息标定点");
        }
        p.sendMessage("§b§l§m------------------------------");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String a, String[] args) {
        List<String> list = new ArrayList<>();
        if (!(s instanceof Player p)) return list;
        if (args.length == 1) {
            list.add("help");
            if (p.hasPermission("blessing.admin")) { list.add("hologram"); list.add("reload"); }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("hologram") && p.hasPermission("blessing.admin")) {
            list.addAll(Arrays.asList("create", "delete"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("delete") && p.hasPermission("blessing.admin")) {
            list.addAll(holoAnchors.keySet());
        } else if (args.length == 4 && args[1].equalsIgnoreCase("create") && p.hasPermission("blessing.admin")) {
            list.addAll(Arrays.asList("自己", "他人", "全服", "ALL"));
        }
        String last = args[args.length - 1].toLowerCase();
        return list.stream().filter(str -> str.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }

    // --- 2. 界面核心 ---
    private void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 9, color(getConfig().getString("menu.main_title")));
        inv.setItem(getConfig().getInt("menu.post_button.slot"), createGuiItem("menu.post_button"));
        inv.setItem(getConfig().getInt("menu.my_button.slot"), createGuiItem("menu.my_button"));
        inv.setItem(getConfig().getInt("menu.all_button.slot"), createGuiItem("menu.all_button"));
        p.openInventory(inv);
    }

    private void openAllBlessings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&e大家的祝福"));
        List<String> bList = getConfig().getStringList("data.blessings");
        for (String s : bList) {
            String[] parts = s.split("\\|");
            if (parts.length < 4) continue;
            inv.addItem(createSimpleItem(Material.PAPER, "§b" + parts[1] + " §f对 §d" + parts[2] + " §f的祝福", "§7预览: §f" + parts[3] + "\n§8[点击进入互动菜单]"));
        }
        inv.setItem(49, createSimpleItem(Material.ARROW, "§7« 返回主页面", ""));
        p.openInventory(inv);
    }

    private void openMyBlessings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&b我的祝福管理"));
        String uuid = p.getUniqueId().toString();
        List<String> my = getConfig().getStringList("data.blessings").stream().filter(s -> s.startsWith(uuid)).toList();
        for (String s : my) {
            String[] parts = s.split("\\|");
            inv.addItem(createSimpleItem(Material.PAPER, "§f发给 §d" + parts[2] + " §f的祝福", "§7预览: §e" + parts[3] + "\n§8[点击进行修改/撤回]"));
        }
        inv.setItem(49, createSimpleItem(Material.ARROW, "§7« 返回主页面", ""));
        p.openInventory(inv);
    }

    private void openSocialManage(Player p, String data, boolean isOwner) {
        String key = String.valueOf(data.hashCode());
        // 关键标识锁定：确保不管是主页还是互动页，前缀始终如一
        statusSession.put(p.getUniqueId(), (isOwner ? "OWNER_VIEW|" : "GUEST_VIEW|") + data);
        Inventory inv = Bukkit.createInventory(null, 9, color("§x§0§f§b§6§f§f互动选项"));
        if (isOwner) {
            inv.setItem(0, createSimpleItem(Material.APPLE, "§c点赞名单", "§7获赞: " + getMeta(key, "likes").size()));
            inv.setItem(2, createSimpleItem(Material.BOOK, "§a查看评论", "§7评论: " + getMeta(key, "comments").size()));
            inv.setItem(4, createSimpleItem(Material.NAME_TAG, "§e修改内容", ""));
            inv.setItem(6, createSimpleItem(Material.BARRIER, "§4撤回祝福", ""));
        } else {
            boolean liked = getMeta(key, "likes").contains(p.getName());
            inv.setItem(1, createSimpleItem(liked ? Material.REDSTONE : Material.GUNPOWDER, liked ? "§c[已点赞]" : "§a[点赞] 支持他", ""));
            inv.setItem(3, createSimpleItem(Material.PAPER, "§b写评论", ""));
            inv.setItem(5, createSimpleItem(Material.BOOK, "§e看评论区", ""));
        }
        inv.setItem(8, createSimpleItem(Material.ARROW, "§7« 返回上一级", "")); p.openInventory(inv);
    }

    // --- 3. 核心修正：多级导航状态锁定与返回逻辑 ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack cur = event.getCurrentItem();
        if (cur == null || !cur.hasItemMeta()) return;
        event.setCancelled(true);
        String title = LegacyComponentSerializer.legacySection().serialize(event.getView().title());

        if (cur.getType() == Material.ARROW) { handleNavBack(p, title); return; }

        if (title.contains(colorToString("menu.main_title"))) {
            int s = event.getRawSlot();
            if (s == getConfig().getInt("menu.post_button.slot")) openTargetMenu(p);
            else if (s == getConfig().getInt("menu.my_button.slot")) openMyBlessings(p);
            else if (s == getConfig().getInt("menu.all_button.slot")) openAllBlessings(p);
        } else if (title.contains("大家的祝福")) {
            // 锁定规则：从大家的祝福进入，一律传 false (访客)
            List<String> b = getConfig().getStringList("data.blessings");
            if (event.getRawSlot() < b.size()) openSocialManage(p, b.get(event.getRawSlot()), false);
        } else if (title.contains("我的祝福管理")) {
            List<String> my = getConfig().getStringList("data.blessings").stream().filter(s -> s.startsWith(p.getUniqueId().toString())).toList();
            if (event.getRawSlot() < my.size()) openSocialManage(p, my.get(event.getRawSlot()), true);
        } else if (title.contains("互动选项")) {
            handleSocialNavigation(p, event.getRawSlot());
        } else if (title.contains("确认要撤回")) {
            if (event.getRawSlot() == 2) executeDelete(p);
            else if (event.getRawSlot() == 6) {
                String s = statusSession.get(p.getUniqueId());
                if (s != null) openSocialManage(p, s.split("\\|", 2)[1], true);
            }
        } else if (title.contains(colorToString("target_menu.title"))) {
            int s = event.getRawSlot();
            String t = (s == getConfig().getInt("target_menu.self.slot")) ? "自己" : (s == getConfig().getInt("target_menu.others.slot")) ? "他人" : (s == getConfig().getInt("target_menu.server.slot")) ? "全服" : null;
            if (t != null) { pendingTarget.put(p.getUniqueId(), t); p.closeInventory(); p.sendMessage(color(getConfig().getString("messages.enter_blessing"))); }
        }
    }

    private void handleNavBack(Player p, String title) {
        if (title.contains("选择") || title.contains("管理") || title.contains("大家")) {
            openMainMenu(p);
        } else {
            String sess = statusSession.getOrDefault(p.getUniqueId(), "");
            if (sess.isEmpty()) { openMainMenu(p); return; }

            String data = sess.split("\\|", 2)[1];
            // 核心逻辑：凡是返回主互动页的，统一使用 sess 的前缀判定
            if (title.contains("名单") || title.contains("评论") || title.contains("撤回")) {
                openSocialManage(p, data, sess.startsWith("OWNER"));
            } else if (title.contains("互动选项")) {
                if (sess.startsWith("OWNER")) openMyBlessings(p); else openAllBlessings(p);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        String msg = LegacyComponentSerializer.legacySection().serialize(event.message());
        if (pendingTarget.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
            String target = pendingTarget.remove(p.getUniqueId());
            if (!msg.equalsIgnoreCase("cancel")) saveBlessing(p, target, msg);
            else p.sendMessage("§c已取消。");
            Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
            return;
        }
        if (statusSession.containsKey(p.getUniqueId())) {
            String sess = statusSession.get(p.getUniqueId());
            if (!sess.contains("_MODE")) return;
            event.setCancelled(true);
            statusSession.remove(p.getUniqueId());
            String data = sess.split("\\|", 2)[1];
            if (sess.startsWith("COMMENT_MODE")) {
                addMeta(String.valueOf(data.hashCode()), "comments", p.getName() + "§7: " + msg);
                p.sendMessage("§a发表成功。");
                Bukkit.getScheduler().runTask(this, () -> openSocialManage(p, data, false));
            } else if (sess.startsWith("EDIT_MODE")) {
                updateBlessing(data, msg);
                p.sendMessage("§a修改已保存。");
                Bukkit.getScheduler().runTask(this, () -> openMyBlessings(p));
            }
        }
    }

    // --- 4. 社会系统逻辑 ---
    private void handleSocialNavigation(Player p, int slot) {
        String sess = statusSession.get(p.getUniqueId()); if (sess == null) return;
        String data = sess.split("\\|", 2)[1];
        boolean isOwner = sess.startsWith("OWNER");
        if (isOwner) {
            if (slot == 0) openLikers(p, data, true);
            else if (slot == 2) openComments(p, data, true);
            else if (slot == 4) {
                statusSession.put(p.getUniqueId(), "EDIT_MODE|" + data);
                String old = data.split("\\|")[3]; p.closeInventory();
                p.sendMessage(color("§b请输入更新后的文字 (输入 cancel 取消)。\n").append(Component.text("§e§l[点击此处辅助修改]").clickEvent(ClickEvent.suggestCommand(old))));
            }
            else if (slot == 6) openConfirm(p, data);
        } else {
            if (slot == 1) { toggleLike(p, String.valueOf(data.hashCode())); openSocialManage(p, data, false); }
            else if (slot == 3) { statusSession.put(p.getUniqueId(), "COMMENT_MODE|" + data); p.closeInventory(); p.sendMessage("§b请输入评论内容 (输入 cancel 取消)..."); }
            else if (slot == 5) openComments(p, data, false);
        }
    }

    private void openLikers(Player p, String data, boolean isOwner) {
        String key = String.valueOf(data.hashCode());
        // 关键标识继承：进入列表页也要带上前台分配的标识
        statusSession.put(p.getUniqueId(), (isOwner ? "OWNER_VIEW|" : "GUEST_VIEW|") + data);
        Inventory inv = Bukkit.createInventory(null, 54, color("§d点赞名单"));
        getMeta(key, "likes").forEach(n -> {
            ItemStack h = new ItemStack(Material.PLAYER_HEAD); SkullMeta m = (SkullMeta) h.getItemMeta();
            m.setOwningPlayer(Bukkit.getOfflinePlayer(n)); m.displayName(color("§e" + n));
            h.setItemMeta(m); inv.addItem(h);
        });
        inv.setItem(49, createSimpleItem(Material.ARROW, "§7« 返回", "")); p.openInventory(inv);
    }

    private void openComments(Player p, String d, boolean isOwner) {
        statusSession.put(p.getUniqueId(), (isOwner ? "OWNER_VIEW|" : "GUEST_VIEW|") + d);
        Inventory inv = Bukkit.createInventory(null, 54, color("§6评论详情"));
        getMeta(String.valueOf(d.hashCode()), "comments").forEach(c -> inv.addItem(createSimpleItem(Material.PAPER, c, "")));
        inv.setItem(49, createSimpleItem(Material.ARROW, "§7« 返回", "")); p.openInventory(inv);
    }

    private void openConfirm(Player p, String d) {
        statusSession.put(p.getUniqueId(), "OWNER_VIEW|" + d);
        Inventory inv = Bukkit.createInventory(null, 9, color("§4确认要撤回这条吗？"));
        inv.setItem(2, createSimpleItem(Material.LIME_WOOL, "§a确认撤回", ""));
        inv.setItem(6, createSimpleItem(Material.RED_WOOL, "§c取消操作", "")); p.openInventory(inv);
    }

    // --- 5. 全息渲染与粒子系统 ---
    private void startHologramCycle() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<String> b = getConfig().getStringList("data.blessings");
            if (b.isEmpty() || holoAnchors.isEmpty()) return;
            for (String id : holoAnchors.keySet()) {
                String type = holoTypeMap.getOrDefault(id, "ALL");
                List<String> pool = type.equals("ALL") ? b : b.stream().filter(s -> s.split("\\|")[2].equals(type)).toList();
                if (pool.isEmpty()) continue;
                String[] d = pool.get(new Random().nextInt(pool.size())).split("\\|");
                updateHoloView(id, d[1], d[2], d[3]);
            }
        }, 0L, Math.max(20L, getConfig().getLong("hologram.update_interval", 100L)));
    }

    private void updateHoloView(String id, String sender, String target, String msg) {
        Location loc = holoAnchors.get(id); if (loc == null || loc.getWorld() == null) return;
        List<ArmorStand> group = holoGroups.computeIfAbsent(id, k -> new ArrayList<>());
        for (ArmorStand as : group) as.remove(); group.clear();
        List<String> lines = new ArrayList<>();
        lines.add(getConfig().getString("hologram.footer", "&7&m-----"));
        List<String> splitMsg = splitText(msg, getConfig().getInt("hologram.max_line_length", 24));
        for (int i = splitMsg.size() - 1; i >= 0; i--) lines.add("§f" + splitMsg.get(i));
        lines.add("§e" + sender + " §f对 §d" + target + " §f说：");
        lines.add(getConfig().getString("hologram.header", "&x&0&f&b&6&f§l✦ 云琉祝福 ✦"));
        for (int i = 0; i < lines.size(); i++) {
            ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, i * 0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setCustomNameVisible(true);
            as.customName(color(lines.get(i))); group.add(as);
        }
    }

    private void startParticleEffect() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Location l : holoAnchors.values()) {
                World w = l.getWorld(); if (w == null) continue;
                for (int i = 0; i < 360; i += 72) {
                    double r = Math.toRadians(i + (System.currentTimeMillis() / 20.0) % 360);
                    w.spawnParticle(Particle.VILLAGER_HAPPY, l.clone().add(Math.cos(r)*0.8, 0.15, Math.sin(r)*0.8), 1, 0,0,0,0);
                }
            }
        }, 0L, 2L);
    }

    // --- 6. 数据工具与辅助 ---
    private void openTargetMenu(Player p) {
        if (cooldownMap.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) {
            p.sendMessage(color(getConfig().getString("messages.cooldown").replace("%time%", ""+((cooldownMap.get(p.getUniqueId())-System.currentTimeMillis())/1000)))); return;
        }
        Inventory inv = Bukkit.createInventory(null, 9, color(getConfig().getString("target_menu.title")));
        inv.setItem(getConfig().getInt("target_menu.self.slot"), createGuiItem("target_menu.self"));
        inv.setItem(getConfig().getInt("target_menu.others.slot"), createGuiItem("target_menu.others"));
        inv.setItem(getConfig().getInt("target_menu.server.slot"), createGuiItem("target_menu.server"));
        inv.setItem(8, createSimpleItem(Material.ARROW, "§7« 返回主菜单", "")); p.openInventory(inv);
    }

    private void saveBlessing(Player p, String t, String m) { List<String> l = getConfig().getStringList("data.blessings"); l.add(p.getUniqueId()+"|"+p.getName()+"|"+t+"|"+m); getConfig().set("data.blessings", l); saveConfig(); cooldownMap.put(p.getUniqueId(), System.currentTimeMillis() + (getConfig().getLong("settings.post_cooldown")*1000)); p.sendMessage(color(getConfig().getString("messages.success"))); }
    private void updateBlessing(String old, String n) { List<String> l = getConfig().getStringList("data.blessings"); int i = l.indexOf(old); if(i!=-1) { String[] p = old.split("\\|"); l.set(i, p[0]+"|"+p[1]+"|"+p[2]+"|"+n); getConfig().set("data.blessings", l); saveConfig(); } }
    private void executeDelete(Player p) { String sess = statusSession.remove(p.getUniqueId()); if(sess==null) return; String data = sess.split("\\|", 2)[1]; List<String> l = getConfig().getStringList("data.blessings"); l.remove(data); getConfig().set("data.blessings", l); saveConfig(); p.sendMessage("§c已撤回该祝福。"); openMyBlessings(p); }
    private void addMeta(String k, String t, String v) { List<String> l = getMeta(k, t); l.add(v); getConfig().set("data.metadata."+k+"."+t, l); saveConfig(); }
    private List<String> getMeta(String k, String t) { return getConfig().getStringList("data.metadata."+k+"."+t); }
    private void toggleLike(Player p, String k) { List<String> l = getMeta(k, "likes"); if(l.contains(p.getName())) l.remove(p.getName()); else l.add(p.getName()); getConfig().set("data.metadata."+k+".likes", l); saveConfig(); }
    private void createHologramGroup(String id, Location loc, String type) { removeHologram(id); holoAnchors.put(id, loc.clone()); holoTypeMap.put(id, type); }
    private void removeHologram(String id) { if (holoGroups.containsKey(id)) { holoGroups.get(id).forEach(ArmorStand::remove); holoGroups.remove(id); } holoAnchors.remove(id); holoTypeMap.remove(id); getConfig().set("data.holograms." + id, null); saveConfig(); }
    private void loadHologramsFromConfig() { if (!getConfig().contains("data.holograms")) return; for (String id : getConfig().getConfigurationSection("data.holograms").getKeys(false)) { String v = getConfig().getString("data.holograms." + id); String[] p = v.split(","); holoAnchors.put(id, new Location(Bukkit.getWorld(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]))); if (p.length >= 5) holoTypeMap.put(id, p[4]); } }
    private void saveHoloToConfig(String id, Location l, String t) { getConfig().set("data.holograms." + id, l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + t); saveConfig(); }
    private List<String> splitText(String t, int l) { List<String> r = new ArrayList<>(); StringBuilder sb = new StringBuilder(); for (char c : t.toCharArray()) { sb.append(c); if (sb.length() >= l) { r.add(sb.toString()); sb.setLength(0); } } if (sb.length() > 0) r.add(sb.toString()); return r; }
    private ItemStack createGuiItem(String p) { ItemStack i = new ItemStack(Material.valueOf(getConfig().getString(p + ".material", "PAPER"))); ItemMeta m = i.getItemMeta(); m.displayName(color(getConfig().getString(p + ".name", ""))); m.lore(getConfig().getStringList(p + ".lore").stream().map(this::color).collect(Collectors.toList())); i.setItemMeta(m); return i; }
    private ItemStack createSimpleItem(Material m, String n, String lore) { ItemStack i = new ItemStack(m); ItemMeta mt = i.getItemMeta(); mt.displayName(color(n)); if (lore != null && !lore.isEmpty()) { List<Component> l = new ArrayList<>(); for (String line : lore.split("\n")) l.add(color(line)); mt.lore(l); } i.setItemMeta(mt); return i; }
    private Component color(String t) { return LegacyComponentSerializer.legacyAmpersand().deserialize(t.replace("§", "&")); }
    private String colorToString(String pt) { return LegacyComponentSerializer.legacySection().serialize(color(getConfig().getString(pt, ""))); }
}