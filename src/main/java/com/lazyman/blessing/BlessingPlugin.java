package com.lazyman.blessing;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BlessingPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private File zhufuFile, holoFile;
    private FileConfiguration zhufuConfig, holoConfig;
    private final Map<UUID, String> pendingTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> postCooldowns = new ConcurrentHashMap<>();
    private final Map<String, List<ArmorStand>> holoGroups = new HashMap<>();
    private final Map<String, Location> holoAnchors = new HashMap<>();
    private final Map<String, String> holoTypeMap = new HashMap<>();
    private final Map<UUID, String> statusSession = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pageCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 1. 配置备份与初始化
        handleConfigMigration();
        initFiles();
        loadSystem();

        // 2. 注册核心逻辑
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zhufu").setExecutor(this);
        getCommand("zhufu").setTabCompleter(this);
        getLogger().info("云琉祝福系统 - 已完成全功能装载。");
    }

    private void handleConfigMigration() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            FileConfiguration currentCfg = YamlConfiguration.loadConfiguration(configFile);
            if (!currentCfg.contains("hologram.update_interval")) {
                File backupFile = new File(getDataFolder(), "config_backup_old.yml");
                configFile.renameTo(backupFile);
                getLogger().info("旧配置文件已重命名为 config_backup_old.yml 进行备份。");
            }
        }
        saveDefaultConfig();
        reloadConfig();
    }

    private void initFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        zhufuFile = new File(getDataFolder(), "zhufu.yml");
        holoFile = new File(getDataFolder(), "hologram.yml");
        if (!zhufuFile.exists()) { try { zhufuFile.createNewFile(); } catch (IOException ignored) {} }
        if (!holoFile.exists()) { try { holoFile.createNewFile(); } catch (IOException ignored) {} }

        zhufuConfig = YamlConfiguration.loadConfiguration(zhufuFile);
        holoConfig = YamlConfiguration.loadConfiguration(holoFile);

        // 3. 核心数据升级 (4段转5段)
        List<String> raw = zhufuConfig.getStringList("list");
        List<String> upgraded = new ArrayList<>();
        boolean changed = false;
        for (String s : raw) {
            if (s.split("\\|").length == 4) {
                upgraded.add("ID" + UUID.randomUUID().toString().substring(0, 8) + "|" + s); // 补齐ID字段
                changed = true;
            } else upgraded.add(s);
        }
        if (changed) { zhufuConfig.set("list", upgraded); saveZhufu(); }
    }

    private void saveZhufu() { try { zhufuConfig.save(zhufuFile); } catch (IOException ignored) {} }
    private void saveHolo() { try { holoConfig.save(holoFile); } catch (IOException ignored) {} }

    private void loadSystem() {
        cleanupAllHolograms(); holoAnchors.clear(); holoTypeMap.clear(); holoGroups.clear();
        for (String id : holoConfig.getKeys(false)) {
            String val = holoConfig.getString(id);
            if (val == null || val.split(",").length < 4) continue;
            String[] pts = val.split(",");
            World w = Bukkit.getWorld(pts[0]);
            if (w == null) continue;
            holoAnchors.put(id, new Location(w, Double.parseDouble(pts[1]), Double.parseDouble(pts[2]), Double.parseDouble(pts[3])));
            if (pts.length >= 5) holoTypeMap.put(id, pts[4]);
        }
        startHologramCycle(); startParticleEffect();
    }

    private void cleanupAllHolograms() { holoGroups.values().forEach(g -> g.forEach(ArmorStand::remove)); }

    private String translate(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            try { matcher.appendReplacement(sb, net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString()); } catch (Exception ignored) {}
        }
        return ChatColor.translateAlternateColorCodes('&', matcher.appendTail(sb).toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) { openMainMenu(p); return true; }
        String sub = args[0].toLowerCase();
        if (sub.equals("help")) { sendHelp(p); }
        else if (sub.equals("reload") && p.hasPermission("blessing.admin")) {
            reloadConfig(); initFiles(); loadSystem();
            p.sendMessage(translate("&a[系统] 指令、配置、全息与历史数据已全部重载。"));
        } else if (sub.equals("hologram") && p.hasPermission("blessing.admin")) {
            handleHologram(p, args);
        } else {
            openMainMenu(p);
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(translate("&b&l&m-----&r &#0fb6ff&l云琉祝福手册 &b&l&m-----"));
        p.sendMessage(translate("&e/zhufu &7- 呼出主控制面板"));
        if (p.hasPermission("blessing.admin")) {
            p.sendMessage(translate("&c&l[管理员专区]"));
            p.sendMessage(translate("&e/zhufu hologram create [ID] [分类] &7- 在脚下放全息展示窗"));
            p.sendMessage(translate("&e/zhufu hologram delete [ID] &7- 移除指定窗口"));
            p.sendMessage(translate("&e/zhufu hologram purge &7- 彻底扫清半径5格内实体残影"));
        }
    }

    private void handleHologram(Player p, String[] args) {
        if (args.length < 2) return;
        String action = args[1].toLowerCase();
        if (action.equals("purge")) {
            int r = (args.length > 2) ? Integer.parseInt(args[2]) : 5;
            int c = 0;
            for (Entity e : p.getNearbyEntities(r, r, r)) {
                if (e instanceof ArmorStand as && (as.getScoreboardTags().contains("blessing_holo") || (as.getCustomName()!=null && as.getCustomName().contains("祝福")))) {
                    as.remove(); c++;
                }
            }
            p.sendMessage(translate("&a[清理] 已清扫 " + c + " 个残留实体。"));
        } else if (args.length >= 3) {
            String id = args[2];
            if (action.equals("create")) {
                String type = args.length > 3 ? args[3] : "ALL";
                Location l = p.getLocation();
                holoConfig.set(id, l.getWorld().getName()+","+l.getX()+","+l.getY()+","+l.getZ()+","+type);
                saveHolo(); loadSystem(); p.sendMessage(translate("&a[全息] 窗口「" + id + "」注册成功。"));
            } else if (action.equals("delete")) {
                if (holoGroups.containsKey(id)) { holoGroups.get(id).forEach(ArmorStand::remove); holoGroups.remove(id); }
                holoAnchors.remove(id); holoConfig.set(id, null); saveHolo(); p.sendMessage(translate("&c[全息] 窗口已卸载。"));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("blessing.admin")) return Collections.emptyList();
        if (args.length == 1) return Arrays.asList("help", "reload", "hologram").stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("hologram")) return Arrays.asList("create", "delete", "purge").stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("hologram")) {
            if (args[1].equalsIgnoreCase("delete")) return holoConfig.getKeys(false).stream().filter(id -> id.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            if (args[1].equalsIgnoreCase("create")) return Collections.singletonList("[点位ID]");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("hologram") && args[1].equalsIgnoreCase("create")) return Arrays.asList("自己", "他人", "全服", "ALL").stream().filter(s -> s.startsWith(args[3].toLowerCase())).collect(Collectors.toList());
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        // 核心修正：扩大标题包含关键字，防止“确认撤回”被拦截
        if (!title.contains("祝福") && !title.contains("互动") && !title.contains("详情") && !title.contains("名单") && !title.contains("选项") && !title.contains("管理") && !title.contains("选择") && !title.contains("撤回") && !title.contains("确认")) return;

        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getType() == Material.ARROW) { handleBack(p, title, e.getRawSlot()); return; }

        if (title.equals("§e大家的祝福")) handleListClick(p, "ALL", e.getRawSlot());
        else if (title.equals("§b我的祝福管理")) handleListClick(p, "MY", e.getRawSlot());
        else if (title.equals("§9互动选项")) handleSocial(p, e.getRawSlot());
        else if (title.contains("确认撤回")) {
            if (e.getRawSlot() == 2) executeDel(p); else if (e.getRawSlot() == 6) openMy(p);
        } else if (title.equals(translate(getConfig().getString("menu.main_title")))) {
            if (e.getRawSlot() == getConfig().getInt("menu.post_button.slot")) openTarget(p);
            else if (e.getRawSlot() == getConfig().getInt("menu.my_button.slot")) { pageCache.put(p.getUniqueId(),0); openMy(p); }
            else if (e.getRawSlot() == getConfig().getInt("menu.all_button.slot")) { pageCache.put(p.getUniqueId(),0); openAll(p); }
        } else if (title.contains("选择")) handleTargetPick(p, e.getRawSlot());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer(); String msg = e.getMessage();
        if (pendingTarget.containsKey(p.getUniqueId()) || statusSession.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            if (pendingTarget.containsKey(p.getUniqueId())) {
                String t = pendingTarget.remove(p.getUniqueId());
                if (!msg.equalsIgnoreCase("cancel")) saveNew(p, t, msg);
                Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
            } else {
                String sess = statusSession.get(p.getUniqueId());
                if (!sess.contains("_MODE")) return;
                statusSession.remove(p.getUniqueId());
                String data = sess.split("\\|", 2)[1];
                if (sess.startsWith("COMMENT_MODE") && !msg.equalsIgnoreCase("cancel")) {
                    addMeta(data.split("\\|")[0], "comments", p.getName() + "§7: " + msg);
                    Bukkit.getScheduler().runTask(this, () -> openSocialSub(p, data, false));
                } else if (sess.startsWith("EDIT_MODE") && !msg.equalsIgnoreCase("cancel")) {
                    updateOld(data, msg); Bukkit.getScheduler().runTask(this, () -> openMy(p));
                } else Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
            }
        }
    }

    private void handleBack(Player p, String title, int slot) {
        int pg = pageCache.getOrDefault(p.getUniqueId(), 0);
        // 核心修正：返回键逻辑闭环
        if (title.contains("名单") || title.contains("详情")) {
            String s = statusSession.getOrDefault(p.getUniqueId(),"");
            if (s.contains("|")) openSocialSub(p, s.split("\\|", 2)[1], s.startsWith("OWNER"));
        } else if (title.equals("§9互动选项")) {
            if (statusSession.getOrDefault(p.getUniqueId(),"").startsWith("OWNER")) openMy(p); else openAll(p);
        } else {
            if (slot == 48 && pg > 0) { pageCache.put(p.getUniqueId(), pg-1); if (title.contains("大家")) openAll(p); else openMy(p); }
            else if (slot == 50) { pageCache.put(p.getUniqueId(), pg+1); if (title.contains("大家")) openAll(p); else openMy(p); }
            else openMainMenu(p);
        }
    }

    private void openAll(Player p) {
        List<String> l = zhufuConfig.getStringList("list"); int pg = pageCache.getOrDefault(p.getUniqueId(), 0);
        Inventory inv = Bukkit.createInventory(null, 54, "§e大家的祝福");
        int start = pg * 45;
        for (int i = start; i < Math.min(start+45, l.size()); i++) {
            String[] pts = l.get(i).split("\\|", 5); String invId = "§" + pts[0].replace("", "§");
            inv.addItem(createItem(Material.PAPER, "&b" + pts[2] + " &f的祝福", "&7对象: &d" + pts[3] + "\n \n&f祝福语: &e" + pts[4] + "\n \n&8[点击开启互动]\n" + invId));
        }
        addPagination(inv, pg, l.size()); p.openInventory(inv);
    }

    private void openMy(Player p) {
        String uid = p.getUniqueId().toString(); List<String> my = zhufuConfig.getStringList("list").stream().filter(s -> s.contains("|"+uid+"|")).toList();
        int pg = pageCache.getOrDefault(p.getUniqueId(), 0); Inventory inv = Bukkit.createInventory(null, 54, "§b我的祝福管理");
        int start = pg * 45;
        for (int i = start; i < Math.min(start+45, my.size()); i++) {
            String[] pts = my.get(i).split("\\|", 5); String invId = "§" + pts[0].replace("", "§");
            inv.addItem(createItem(Material.PAPER, "&f给 &d" + pts[3] + " &f的祝福", "&7内容: &e" + pts[4] + "\n \n&8[点击管理]\n" + invId));
        }
        addPagination(inv, pg, my.size()); p.openInventory(inv);
    }

    private void handleListClick(Player p, String type, int slot) {
        if (slot >= 45) return;
        int pg = pageCache.getOrDefault(p.getUniqueId(), 0);
        int idx = pg * 45 + slot;
        List<String> list = type.equals("ALL") ? zhufuConfig.getStringList("list") : zhufuConfig.getStringList("list").stream().filter(s -> s.contains("|"+p.getUniqueId()+"|")).collect(Collectors.toList());
        if (idx < list.size()) openSocialSub(p, list.get(idx), type.equals("MY"));
    }

    private void openSocialSub(Player p, String data, boolean isOwner) {
        String[] pts = data.split("\\|", 5); statusSession.put(p.getUniqueId(), (isOwner?"OWNER|":"GUEST|") + data);
        Inventory inv = Bukkit.createInventory(null, 9, "§9互动选项");
        if (isOwner) {
            inv.setItem(0, createItem(Material.APPLE, "&c点赞名单", "")); inv.setItem(2, createItem(Material.BOOK, "&a浏览评论区", ""));
            inv.setItem(4, createItem(Material.NAME_TAG, "&e修改内容", "")); inv.setItem(6, createItem(Material.BARRIER, "&4确认撤回", ""));
        } else {
            boolean liked = getMeta(pts[0], "likes").contains(p.getName());
            inv.setItem(1, createItem(liked?Material.REDSTONE:Material.GUNPOWDER, liked?"&c[已点赞]":"&a[给予点赞]", ""));
            inv.setItem(3, createItem(Material.PAPER, "&b发布评论", "")); inv.setItem(5, createItem(Material.BOOK, "&e查看评论区", ""));
        }
        inv.setItem(8, createItem(Material.ARROW, "&7« 返回", "")); p.openInventory(inv);
    }

    private void handleSocial(Player p, int slot) {
        String sess = statusSession.get(p.getUniqueId()); if (sess == null) return;
        String[] pts = sess.split("\\|", 2); boolean isOwner = pts[0].equals("OWNER"); String data = pts[1]; String sid = data.split("\\|")[sidIndex(data)];
        if (isOwner) {
            if (slot == 0) openDetails(p, sid, "点赞"); else if (slot == 2) openDetails(p, sid, "评论");
            else if (slot == 4) {
                statusSession.put(p.getUniqueId(), "EDIT_MODE|" + data); p.closeInventory();
                p.sendMessage(translate("&b请输入新内容 (发 'cancel' 中止输入)"));
                TextComponent tc = new TextComponent(translate("&e&l[点击这里自动填入原祝福语进行修改]"));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, data.split("\\|", 5)[4]));
                p.spigot().sendMessage(tc);
            } else if (slot == 6) {
                Inventory c = Bukkit.createInventory(null, 9, "§4确认撤回？");
                c.setItem(2, createItem(Material.LIME_WOOL,"&a确定撤回","")); c.setItem(6, createItem(Material.RED_WOOL,"&c保留祝福","")); p.openInventory(c);
            }
        } else {
            if (slot == 1) { toggleLike(p, sid); openSocialSub(p, data, false); }
            else if (slot == 3) { statusSession.put(p.getUniqueId(), "COMMENT_MODE|" + data); p.closeInventory(); p.sendMessage(translate("&b请开始输入您的评论...")); }
            else if (slot == 5) openDetails(p, sid, "评论");
        }
    }

    private int sidIndex(String data) { return data.startsWith("ID") ? 0 : 0; }

    private void openDetails(Player p, String id, String type) {
        Inventory inv = Bukkit.createInventory(null, 54, type.equals("点赞") ? "§d点赞名单" : "§6评论详情");
        getMeta(id, type.equals("点赞")?"likes":"comments").forEach(s -> inv.addItem(createItem(type.equals("点赞")?Material.PLAYER_HEAD:Material.PAPER, "§e"+s, "")));
        inv.setItem(49, createItem(Material.ARROW, "&7« 返回互动菜单", "")); p.openInventory(inv);
    }

    private void saveNew(Player p, String t, String m) {
        if (!p.hasPermission("blessing.admin")) {
            long d = (System.currentTimeMillis() - postCooldowns.getOrDefault(p.getUniqueId(), 0L))/1000;
            if (d < getConfig().getLong("post_cooldown", 60L)) { p.sendMessage(translate("&c过于频繁！请等待 " + (getConfig().getLong("post_cooldown") - d) + " 秒")); return; }
        }
        List<String> l = zhufuConfig.getStringList("list"); String uid = "ID" + UUID.randomUUID().toString().substring(0,8) + System.currentTimeMillis();
        l.add(uid + "|" + p.getUniqueId() + "|" + p.getName() + "|" + t + "|" + m);
        zhufuConfig.set("list", l); saveZhufu(); postCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(translate(getConfig().getString("messages.success")));
    }

    private void updateOld(String old, String n) {
        List<String> l = zhufuConfig.getStringList("list"); int i = -1;
        for(int k=0; k<l.size(); k++) if(l.get(k).startsWith(old.split("\\|")[0])) { i = k; break; }
        if (i != -1) {
            String[] pts = l.get(i).split("\\|", 5);
            l.set(i, pts[0]+"|"+pts[1]+"|"+pts[2]+"|"+pts[3]+"|"+n);
            zhufuConfig.set("list", l); saveZhufu();
        }
    }

    private void executeDel(Player p) {
        String sess = statusSession.remove(p.getUniqueId()); if (sess == null) return; String data = sess.split("\\|", 2)[1];
        List<String> l = zhufuConfig.getStringList("list");
        l.removeIf(s -> s.startsWith(data.split("\\|")[0]));
        zhufuConfig.set("list", l); saveZhufu();
        p.sendMessage(translate("&a[系统] 该祝福已成功从数据库撤回。"));
        openMy(p);
    }

    private void openMainMenu(Player p) { statusSession.remove(p.getUniqueId()); Inventory i = Bukkit.createInventory(null, 9, translate(getConfig().getString("menu.main_title"))); i.setItem(getConfig().getInt("menu.post_button.slot"), createBtn("menu.post_button")); i.setItem(getConfig().getInt("menu.my_button.slot"), createBtn("menu.my_button")); i.setItem(getConfig().getInt("menu.all_button.slot"), createBtn("menu.all_button")); p.openInventory(i); }
    private void openTarget(Player p) { Inventory i = Bukkit.createInventory(null, 9, translate(getConfig().getString("target_menu.title"))); i.setItem(getConfig().getInt("target_menu.self.slot"), createBtn("target_menu.self")); i.setItem(getConfig().getInt("target_menu.others.slot"), createBtn("target_menu.others")); i.setItem(getConfig().getInt("target_menu.server.slot"), createBtn("target_menu.server")); i.setItem(8, createItem(Material.ARROW, "&7« 返回", "")); p.openInventory(i); }
    private void handleTargetPick(Player p, int slot) { String t = (slot==getConfig().getInt("target_menu.self.slot"))?"自己":(slot==getConfig().getInt("target_menu.others.slot"))?"他人":(slot==getConfig().getInt("target_menu.server.slot"))?"全服":null; if (t!=null) { pendingTarget.put(p.getUniqueId(), t); p.closeInventory(); p.sendMessage(translate(getConfig().getString("messages.enter_blessing"))); } }
    private void toggleLike(Player p, String id) { List<String> l = getMeta(id, "likes"); if (l.contains(p.getName())) l.remove(p.getName()); else l.add(p.getName()); zhufuConfig.set("metadata."+id+".likes", l); saveZhufu(); }
    private List<String> getMeta(String id, String type) { return zhufuConfig.getStringList("metadata."+id+"."+type); }
    private void addMeta(String id, String type, String val) { List<String> l = getMeta(id, type); l.add(val); zhufuConfig.set("metadata."+id+"."+type, l); saveZhufu(); }
    private void addPagination(Inventory inv, int pg, int tot) { if (pg > 0) inv.setItem(48, createItem(Material.ARROW, "&e上一页", "")); inv.setItem(49, createItem(Material.ARROW, "&7« 返回首页", "")); if ((pg+1)*45 < tot) inv.setItem(50, createItem(Material.ARROW, "&e下一页", "")); }
    private ItemStack createBtn(String path) { return createItem(Material.valueOf(getConfig().getString(path+".material")), getConfig().getString(path+".name"), String.join("\n", getConfig().getStringList(path+".lore"))); }
    private ItemStack createItem(Material m, String n, String lore) { ItemStack i = new ItemStack(m); ItemMeta mt = i.getItemMeta(); mt.setDisplayName(translate(n)); if(!lore.isEmpty()) mt.setLore(Arrays.stream(lore.split("\n")).map(this::translate).collect(Collectors.toList())); i.setItemMeta(mt); return i; }
    private void startHologramCycle() { Bukkit.getScheduler().runTaskTimer(this, () -> { List<String> b = zhufuConfig.getStringList("list"); if (b.isEmpty()||holoAnchors.isEmpty()) return; for (String sid : holoAnchors.keySet()) { String type = holoTypeMap.getOrDefault(sid, "ALL"); List<String> pool = type.equals("ALL")?b:b.stream().filter(s -> s.split("\\|", 5)[3].equals(type)).collect(Collectors.toList()); if (!pool.isEmpty()) { String[] d = pool.get(new Random().nextInt(pool.size())).split("\\|", 5); updateHolo(sid, d[2], d[3], d[4]); } } }, 0L, getConfig().getLong("hologram.update_interval", 10L)*20L); }
    private void updateHolo(String id, String s, String t, String m) {
        Location loc = holoAnchors.get(id); if (loc==null) return;
        List<ArmorStand> g = holoGroups.computeIfAbsent(id, k -> new ArrayList<>()); g.forEach(ArmorStand::remove); g.clear();
        List<String> lines = new ArrayList<>(); lines.add(getConfig().getString("hologram.footer"));
        List<String> sm = splitMsg(m, 24); for (int i = sm.size()-1; i>=0; i--) lines.add("&f"+sm.get(i));
        lines.add("&e"+s+" &f对 &d"+t+" &f说："); lines.add(getConfig().getString("hologram.header"));
        for (int i=0; i<lines.size(); i++) { ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, i*0.28, 0), EntityType.ARMOR_STAND); as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setCustomNameVisible(true); as.setCustomName(translate(lines.get(i))); as.addScoreboardTag("blessing_holo"); g.add(as); }
    }
    private List<String> splitMsg(String t, int l) { List<String> r = new ArrayList<>(); StringBuilder sb = new StringBuilder(); for (char c : t.toCharArray()) { sb.append(c); if (sb.length() >= l) { r.add(sb.toString()); sb.setLength(0); } } if (sb.length() > 0) r.add(sb.toString()); return r; }
    private void startParticleEffect() { Bukkit.getScheduler().runTaskTimer(this, () -> holoAnchors.values().forEach(l -> l.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, l.clone().add(0, 0.5, 0), 5, 0.4, 0.4, 0.4, 0.05)), 0L, 20L); }
}
