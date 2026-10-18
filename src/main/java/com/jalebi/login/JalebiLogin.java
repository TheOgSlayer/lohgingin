package com.jalebi.login;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JALEBI SMP login/register using Paper's native Dialog screens (Paper 1.21.6+).
 */
public class JalebiLogin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** "JALEBI" in a golden-orange gradient, "SMP" in white. */
    private static final String TITLE_MM =
            "<bold><gradient:#FFD700:#FF8C00>JALEBI</gradient> <white>SMP</white></bold>";

    private static class Session {
        int attempts = 0;
        BukkitTask timeoutTask;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private File dataFile;
    private YamlConfiguration data;

    private int maxAttempts;
    private int timeoutSeconds;
    private int minLength;
    private int confirmations;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        timeoutSeconds = Math.max(10, getConfig().getInt("timeout-seconds", 60));
        minLength = Math.max(1, getConfig().getInt("min-password-length", 4));
        confirmations = Math.max(1, Math.min(5, getConfig().getInt("register-confirmations", 2)));

        dataFile = new File(getDataFolder(), "accounts.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);

        for (Player p : Bukkit.getOnlinePlayers()) startSession(p);
    }

    @Override
    public void onDisable() {
        for (UUID id : sessions.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.removePotionEffect(PotionEffectType.BLINDNESS);
                p.closeDialog();
            }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ helpers

    private boolean isLocked(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    private boolean isRegistered(Player p) {
        return data.contains("players." + p.getUniqueId() + ".hash");
    }

    private void startSession(Player p) {
        Session s = new Session();
        sessions.put(p.getUniqueId(), s);

        // Hide the world until they log in.
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 1, false, false, false));

        s.timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && isLocked(p)) {
                p.kick(MM.deserialize("<red>You took too long to log in!"));
            }
        }, timeoutSeconds * 20L);

        Bukkit.getScheduler().runTaskLater(this, () -> showScreen(p, null), 10L);
    }

    private void finishSession(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null && s.timeoutTask != null) s.timeoutTask.cancel();
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.closeDialog();
        p.showTitle(Title.title(
                MM.deserialize("<gradient:#FFD700:#FF8C00><bold>Welcome!"),
                MM.deserialize("<white>Enjoy JALEBI SMP"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    /** Opens the right screen: login for existing accounts, register for new ones. */
    private void showScreen(Player p, String errorMM) {
        if (!p.isOnline() || !isLocked(p)) return;
        if (isRegistered(p)) showLoginDialog(p, errorMM);
        else showRegisterDialog(p, errorMM);
    }

    /** Runs the click callback's work on the main server thread. */
    private void sync(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    private static ClickCallback.Options once() {
        return ClickCallback.Options.builder().uses(1).build();
    }

    // ------------------------------------------------------------------ dialogs

    private void showLoginDialog(Player p, String errorMM) {
        Session s = sessions.get(p.getUniqueId());
        int left = maxAttempts - (s == null ? 0 : s.attempts);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(MM.deserialize("<gray>Welcome back! Enter your password to join.")));
        if (errorMM != null) {
            body.add(DialogBody.plainMessage(MM.deserialize(errorMM)));
        }
        body.add(DialogBody.plainMessage(MM.deserialize("<yellow>Attempts left: <white>" + left)));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", MM.deserialize("<white>Password"))
                .width(220).maxLength(64).build());

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(MM.deserialize(TITLE_MM))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.notice(ActionButton.create(
                        MM.deserialize("<green><bold>LOGIN"),
                        MM.deserialize("<gray>Click to log in"),
                        150,
                        DialogAction.customClick((DialogResponseView view, net.kyori.adventure.audience.Audience a) -> {
                            String pw = view.getText("password");
                            sync(() -> handleLogin(p, pw));
                        }, once())))));

        p.showDialog(dialog);
    }

    private void showRegisterDialog(Player p, String errorMM) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(MM.deserialize(
                "<gray>Welcome, new player! Create a password (min " + minLength + " characters).")));
        if (errorMM != null) {
            body.add(DialogBody.plainMessage(MM.deserialize(errorMM)));
        }

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", MM.deserialize("<white>Password"))
                .width(220).maxLength(64).build());
        for (int i = 1; i <= confirmations; i++) {
            String label = confirmations == 1 ? "Confirm password" : "Confirm password (" + i + "/" + confirmations + ")";
            inputs.add(DialogInput.text("confirm" + i, MM.deserialize("<white>" + label))
                    .width(220).maxLength(64).build());
        }

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(MM.deserialize(TITLE_MM))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.notice(ActionButton.create(
                        MM.deserialize("<green><bold>REGISTER"),
                        MM.deserialize("<gray>Click to create your account"),
                        150,
                        DialogAction.customClick((DialogResponseView view, net.kyori.adventure.audience.Audience a) -> {
                            String pw = view.getText("password");
                            List<String> confirms = new ArrayList<>();
                            for (int i = 1; i <= confirmations; i++) confirms.add(view.getText("confirm" + i));
                            sync(() -> handleRegister(p, pw, confirms));
                        }, once())))));

        p.showDialog(dialog);
    }

    // ------------------------------------------------------------------ logic

    private void handleLogin(Player p, String input) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !p.isOnline()) return;

        if (input == null || input.isEmpty()) {
            showScreen(p, "<red>Please enter your password.");
            return;
        }

        if (checkPassword(p, input)) {
            p.sendMessage(MM.deserialize("<green>Logged in successfully!"));
            finishSession(p);
            return;
        }

        s.attempts++;
        if (s.attempts >= maxAttempts) {
            p.kick(MM.deserialize("<red>Too many wrong password attempts!"));
            return;
        }
        showScreen(p, "<red><bold>Wrong password!");
    }

    private void handleRegister(Player p, String pw, List<String> confirms) {
        if (!sessions.containsKey(p.getUniqueId()) || !p.isOnline()) return;

        if (pw == null || pw.length() < minLength) {
            showScreen(p, "<red>Password must be at least " + minLength + " characters.");
            return;
        }
        if (pw.contains(" ")) {
            showScreen(p, "<red>Password cannot contain spaces.");
            return;
        }
        for (String c : confirms) {
            if (!pw.equals(c)) {
                showScreen(p, "<red><bold>Passwords do not match!");
                return;
            }
        }

        savePassword(p, pw);
        p.sendMessage(MM.deserialize("<green>Registered successfully!"));
        finishSession(p);
    }

    // ------------------------------------------------------------------ password storage

    private byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void savePassword(Player p, String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String base = "players." + p.getUniqueId();
        data.set(base + ".name", p.getName());
        data.set(base + ".salt", Base64.getEncoder().encodeToString(salt));
        data.set(base + ".hash", Base64.getEncoder().encodeToString(hash(password, salt)));
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save accounts.yml: " + ex.getMessage());
        }
    }

    private boolean checkPassword(Player p, String password) {
        String base = "players." + p.getUniqueId();
        String saltB64 = data.getString(base + ".salt");
        String hashB64 = data.getString(base + ".hash");
        if (saltB64 == null || hashB64 == null) return false;
        byte[] expected = Base64.getDecoder().decode(hashB64);
        byte[] actual = hash(password, Base64.getDecoder().decode(saltB64));
        return MessageDigest.isEqual(expected, actual);
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        startSession(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null && s.timeoutTask != null) s.timeoutTask.cancel();
        e.getPlayer().removePotionEffect(PotionEffectType.BLINDNESS);
    }

    // ------------------------------------------------------------------ restrictions while not logged in

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (isLocked(e.getPlayer()) && e.hasChangedPosition()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        e.viewers().removeIf(a -> a instanceof Player v && isLocked(v));
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(MM.deserialize("<red>You must log in first!"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isLocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isLocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isLocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isLocked(p)) e.setCancelled(true);
    }
}
