package pl.anticheat;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AntiCheat extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> data = new HashMap<UUID, PlayerData>();
    private final Set<UUID> alertsOff = new HashSet<UUID>();
    private Method glideMethod;

    private String prefix;
    private String kickMessage;
    private boolean flyOn, speedOn, timerOn, reachOn, clickerOn, nukerOn, xrayOn;
    private double speedBase, reachMax;
    private int timerMax, cpsMax, nukerMax, xrayCount, kickAt;
    private long xrayWindowMs, xrayCooldownMs, alertCooldownMs;
    private Set<String> oreNames = new HashSet<String>();

    private static class PlayerData {
        long joinTime;
        long exemptUntil;
        long selfTpAt;
        Location lastGround;
        int airTicks;
        int flyBuffer;
        double speedBuffer;
        int moveCount;
        long windowStart;
        int timerBuffer;
        long blockHitsUntil;
        long lastOreAlert;
        final ArrayDeque<Long> clicks = new ArrayDeque<Long>();
        final ArrayDeque<Long> breaks = new ArrayDeque<Long>();
        final ArrayDeque<Long> ores = new ArrayDeque<Long>();
        final Map<String, Integer> vl = new HashMap<String, Integer>();
        final Map<String, Long> lastAlert = new HashMap<String, Long>();

        PlayerData(long joinTime) {
            this.joinTime = joinTime;
        }

        void reset() {
            lastGround = null;
            airTicks = 0;
            flyBuffer = 0;
            speedBuffer = 0;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        try {
            glideMethod = Player.class.getMethod("isGliding");
        } catch (Exception ex) {
            glideMethod = null;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        long period = Math.max(10, getConfig().getInt("vl-decay-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                for (PlayerData d : data.values()) {
                    Iterator<Map.Entry<String, Integer>> it = d.vl.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Integer> en = it.next();
                        if (en.getValue() <= 1) {
                            it.remove();
                        } else {
                            en.setValue(en.getValue() - 1);
                        }
                    }
                }
            }
        }, period, period);
    }

    private void loadSettings() {
        reloadConfig();
        prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("prefix", "&c[AC] &7"));
        kickMessage = ChatColor.translateAlternateColorCodes('&', getConfig().getString("kick-message", "&cWykryto nieuczciwe modyfikacje klienta."));
        flyOn = getConfig().getBoolean("checks.fly", true);
        speedOn = getConfig().getBoolean("checks.speed", true);
        timerOn = getConfig().getBoolean("checks.timer", true);
        reachOn = getConfig().getBoolean("checks.reach", true);
        clickerOn = getConfig().getBoolean("checks.autoclicker", true);
        nukerOn = getConfig().getBoolean("checks.nuker", true);
        xrayOn = getConfig().getBoolean("checks.xray", true);
        speedBase = getConfig().getDouble("speed-base", 0.65);
        timerMax = getConfig().getInt("timer-max-packets-per-second", 26);
        reachMax = getConfig().getDouble("reach-max", 4.5);
        cpsMax = getConfig().getInt("max-cps", 25);
        nukerMax = getConfig().getInt("nuker-max-blocks-per-second", 15);
        xrayCount = getConfig().getInt("xray.count", 8);
        xrayWindowMs = getConfig().getLong("xray.window-seconds", 120) * 1000L;
        xrayCooldownMs = getConfig().getLong("xray.cooldown-seconds", 30) * 1000L;
        alertCooldownMs = getConfig().getLong("alert-cooldown-seconds", 3) * 1000L;
        kickAt = getConfig().getInt("kick-at", 40);
        oreNames.clear();
        List<String> ores = getConfig().getStringList("xray.ores");
        for (String s : ores) {
            oreNames.add(s.toUpperCase(Locale.ROOT));
        }
    }

    private PlayerData get(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) {
            d = new PlayerData(0);
            data.put(p.getUniqueId(), d);
        }
        return d;
    }

    private boolean isGliding(Player p) {
        if (glideMethod == null) {
            return false;
        }
        try {
            return (Boolean) glideMethod.invoke(p);
        } catch (Exception ex) {
            return false;
        }
    }

    private int effectLevel(Player p, String name) {
        for (PotionEffect pe : p.getActivePotionEffects()) {
            String n = pe.getType().getName();
            if (n != null && n.toUpperCase(Locale.ROOT).startsWith(name)) {
                return pe.getAmplifier() + 1;
            }
        }
        return 0;
    }

    private boolean isCreativeLike(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private boolean exempt(Player p, PlayerData d, long now) {
        if (isCreativeLike(p)) {
            return true;
        }
        if (p.getAllowFlight() || p.isFlying() || p.isInsideVehicle() || isGliding(p)) {
            return true;
        }
        if (now < d.exemptUntil || now - d.joinTime < 3000) {
            return true;
        }
        return p.hasPermission("anticheat.bypass");
    }

    private boolean isAir(String name) {
        return name.equals("AIR") || name.endsWith("_AIR");
    }

    private boolean standable(Location l) {
        World w = l.getWorld();
        double[] off = {-0.3, 0, 0.3};
        int y = (int) Math.floor(l.getY() - 0.1);
        for (double ox : off) {
            for (double oz : off) {
                Block b = w.getBlockAt((int) Math.floor(l.getX() + ox), y, (int) Math.floor(l.getZ() + oz));
                if (!isAir(b.getType().name())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean typeContains(Block b, String... parts) {
        String n = b.getType().name();
        for (String s : parts) {
            if (n.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean climbOrLiquid(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        int y = l.getBlockY();
        String[] parts = {"WATER", "LAVA", "LADDER", "VINE", "SCAFFOLD", "WEB", "BUBBLE", "HONEY", "POWDER_SNOW", "KELP", "SEAGRASS"};
        for (int dy = -1; dy <= 1; dy++) {
            if (typeContains(w.getBlockAt(x, y + dy, z), parts)) {
                return true;
            }
        }
        return false;
    }

    private boolean bouncy(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        int y = l.getBlockY();
        for (int dy = 0; dy <= 3; dy++) {
            if (typeContains(w.getBlockAt(x, y - dy, z), "SLIME", "BED", "HONEY")) {
                return true;
            }
        }
        return false;
    }

    private boolean onIce(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        int y = (int) Math.floor(l.getY() - 0.5);
        return typeContains(w.getBlockAt(x, y, z), "ICE") || typeContains(w.getBlockAt(x, y - 1, z), "ICE");
    }

    private Location back(Location base, Location to) {
        Location r = base.clone();
        r.setYaw(to.getYaw());
        r.setPitch(to.getPitch());
        return r;
    }

    private void flag(Player p, PlayerData d, String check, String info) {
        int v = d.vl.containsKey(check) ? d.vl.get(check) + 1 : 1;
        d.vl.put(check, v);
        long now = System.currentTimeMillis();
        Long last = d.lastAlert.get(check);
        if (last == null || now - last >= alertCooldownMs) {
            d.lastAlert.put(check, now);
            sendAlert(p, ChatColor.RED + check + ChatColor.GRAY + " " + info + ChatColor.DARK_GRAY + " (VL " + v + ")");
        }
        if (kickAt > 0 && v >= kickAt) {
            d.vl.clear();
            p.kickPlayer(kickMessage);
        }
    }

    private void sendAlert(Player target, String text) {
        String line = prefix + ChatColor.WHITE + target.getName() + " " + text;
        TextComponent comp = new TextComponent("");
        for (BaseComponent c : TextComponent.fromLegacyText(line)) {
            comp.addExtra(c);
        }
        TextComponent btn = new TextComponent(" [TELEPORT]");
        btn.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        btn.setBold(true);
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ac tp " + target.getName()));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{new TextComponent("Teleportuj do gracza " + target.getName())}));
        comp.addExtra(btn);
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission("anticheat.alerts") && !alertsOff.contains(s.getUniqueId())) {
                s.spigot().sendMessage(comp);
            }
        }
        getLogger().info(ChatColor.stripColor(line));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data.put(e.getPlayer().getUniqueId(), new PlayerData(System.currentTimeMillis()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        get(e.getPlayer()).exemptUntil = System.currentTimeMillis() + 2500;
    }

    @EventHandler
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            get((Player) e.getEntity()).exemptUntil = System.currentTimeMillis() + 1500;
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = get(e.getPlayer());
        d.reset();
        long now = System.currentTimeMillis();
        if (now - d.selfTpAt < 500) {
            return;
        }
        d.exemptUntil = now + 1500;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        PlayerData d = get(p);
        long now = System.currentTimeMillis();
        if (exempt(p, d, now)) {
            d.reset();
            return;
        }

        if (timerOn) {
            if (now - d.windowStart > 2500) {
                d.windowStart = now;
                d.moveCount = 0;
            }
            d.moveCount++;
            long el = now - d.windowStart;
            if (el >= 1000) {
                double rate = d.moveCount * 1000.0 / el;
                d.windowStart = now;
                d.moveCount = 0;
                if (rate > timerMax) {
                    d.timerBuffer++;
                } else if (d.timerBuffer > 0) {
                    d.timerBuffer--;
                }
                if (d.timerBuffer >= 3) {
                    d.timerBuffer = 1;
                    flag(p, d, "Timer", String.format(Locale.US, "%.1f pakietow/s (limit %d)", rate, timerMax));
                    d.selfTpAt = now;
                    e.setTo(back(from, to));
                    return;
                }
            }
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }

        boolean ground = standable(to);
        boolean special = climbOrLiquid(to) || effectLevel(p, "LEVITATION") > 0 || effectLevel(p, "SLOW_FALLING") > 0;

        if (flyOn) {
            if (ground || special) {
                d.airTicks = 0;
                d.flyBuffer = 0;
            } else {
                d.airTicks++;
                int jump = effectLevel(p, "JUMP");
                boolean bad = false;
                String why = "";
                if (dy > (jump > 0 ? 1.3 : 0.62) && !bouncy(to)) {
                    bad = true;
                    why = String.format(Locale.US, "skok o %.2f bloku", dy);
                } else if (d.airTicks > (jump > 0 ? 30 : 14) && dy > -0.1) {
                    bad = true;
                    why = d.airTicks + " ticków w powietrzu, dy=" + String.format(Locale.US, "%.2f", dy);
                }
                if (bad) {
                    d.flyBuffer++;
                    if (d.flyBuffer >= 3) {
                        d.flyBuffer = 1;
                        flag(p, d, "Fly", why);
                        Location base = (d.lastGround != null && d.lastGround.getWorld() == to.getWorld()) ? d.lastGround : from;
                        d.selfTpAt = now;
                        e.setTo(back(base, to));
                        return;
                    }
                } else if (d.flyBuffer > 0 && dy < -0.3) {
                    d.flyBuffer = 0;
                }
            }
        }

        if (speedOn && !onIce(to)) {
            double h = Math.sqrt(dx * dx + dz * dz);
            double limit = speedBase + 0.15 * effectLevel(p, "SPEED");
            if (h > limit) {
                d.speedBuffer += 1;
            } else {
                d.speedBuffer = Math.max(0, d.speedBuffer - 0.5);
            }
            if (d.speedBuffer >= 5) {
                d.speedBuffer = 2;
                flag(p, d, "Speed", String.format(Locale.US, "%.2f bloku/tick (limit %.2f)", h, limit));
                d.selfTpAt = now;
                e.setTo(back(from, to));
                return;
            }
        }

        if (ground) {
            d.lastGround = to.clone();
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (!clickerOn) {
            return;
        }
        Player p = e.getPlayer();
        PlayerData d = get(p);
        if (isCreativeLike(p) || p.hasPermission("anticheat.bypass")) {
            return;
        }
        long now = System.currentTimeMillis();
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) {
            d.clicks.pollFirst();
        }
        if (d.clicks.size() > cpsMax) {
            flag(p, d, "AutoClicker", d.clicks.size() + " klikniec/s (limit " + cpsMax + ")");
            d.blockHitsUntil = now + 1000;
            d.clicks.clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getDamager();
        if (isCreativeLike(p) || p.hasPermission("anticheat.bypass")) {
            return;
        }
        PlayerData d = get(p);
        long now = System.currentTimeMillis();
        if (now < d.blockHitsUntil) {
            e.setCancelled(true);
            return;
        }
        if (!reachOn) {
            return;
        }
        Entity victim = e.getEntity();
        if (victim.getWorld() != p.getWorld()) {
            return;
        }
        Location eye = p.getEyeLocation();
        double dist = eye.distance(victim.getLocation());
        if (victim instanceof LivingEntity) {
            dist = Math.min(dist, eye.distance(((LivingEntity) victim).getEyeLocation()));
        }
        if (dist > reachMax) {
            e.setCancelled(true);
            flag(p, d, "Reach", String.format(Locale.US, "%.2f bloku (limit %.1f)", dist, reachMax));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (isCreativeLike(p) || p.hasPermission("anticheat.bypass")) {
            return;
        }
        PlayerData d = get(p);
        long now = System.currentTimeMillis();

        if (nukerOn) {
            d.breaks.addLast(now);
            while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000) {
                d.breaks.pollFirst();
            }
            if (d.breaks.size() > nukerMax) {
                e.setCancelled(true);
                flag(p, d, "Nuker", d.breaks.size() + " blokow/s (limit " + nukerMax + ")");
                return;
            }
        }

        if (xrayOn) {
            String type = e.getBlock().getType().name();
            if (oreNames.contains(type)) {
                d.ores.addLast(now);
                while (!d.ores.isEmpty() && now - d.ores.peekFirst() > xrayWindowMs) {
                    d.ores.pollFirst();
                }
                if (d.ores.size() >= xrayCount && now - d.lastOreAlert >= xrayCooldownMs) {
                    long seconds = Math.max(1, (now - d.ores.peekFirst()) / 1000);
                    int count = d.ores.size();
                    d.lastOreAlert = now;
                    d.ores.clear();
                    int v = d.vl.containsKey("XRay") ? d.vl.get("XRay") + 1 : 1;
                    d.vl.put("XRay", v);
                    sendAlert(p, ChatColor.RED + "XRay" + ChatColor.GRAY + " wykopal " + ChatColor.YELLOW + count + ChatColor.GRAY
                            + " rud (" + type + ") w " + ChatColor.YELLOW + seconds + "s" + ChatColor.DARK_GRAY + " (VL " + v + ")");
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(prefix + "/ac alerts | /ac tp <gracz> | /ac vl <gracz> | /ac reload");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("alerts")) {
            if (!(sender instanceof Player) || !sender.hasPermission("anticheat.alerts")) {
                sender.sendMessage(prefix + "Brak uprawnien.");
                return true;
            }
            UUID id = ((Player) sender).getUniqueId();
            if (alertsOff.remove(id)) {
                sender.sendMessage(prefix + "Alerty wlaczone.");
            } else {
   
