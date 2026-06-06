package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final CheckHacksPlugin plugin;
    private Connection connection;

    public DatabaseManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
    }

    private synchronized void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            File db = new File(plugin.getDataFolder(), "data.db");
            db.getParentFile().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.execute("PRAGMA cache_size = 1000");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to SQLite: " + e.getMessage());
        }
    }

    private synchronized void createTables() {
        if (connection == null) return;
        try (Statement s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS scans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    checker_name TEXT NOT NULL,
                    reason TEXT,
                    timestamp INTEGER NOT NULL,
                    has_detected INTEGER NOT NULL DEFAULT 0
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS hack_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_id INTEGER NOT NULL,
                    hack_id TEXT NOT NULL,
                    hack_name TEXT NOT NULL,
                    result TEXT NOT NULL,
                    FOREIGN KEY (scan_id) REFERENCES scans(id) ON DELETE CASCADE
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS lang_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_id INTEGER NOT NULL,
                    language TEXT,
                    response TEXT,
                    FOREIGN KEY (scan_id) REFERENCES scans(id) ON DELETE CASCADE
                )""");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
        }
    }

    public synchronized long saveScan(String type, String targetName, String targetUUID,
                                      String checkerName, String reason, boolean hasDetected) {
        if (connection == null) return -1;
        String sql = "INSERT INTO scans (type,target_name,target_uuid,checker_name,reason,timestamp,has_detected) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, targetName);
            ps.setString(3, targetUUID);
            ps.setString(4, checkerName);
            ps.setString(5, reason);
            ps.setLong(6, System.currentTimeMillis());
            ps.setInt(7, hasDetected ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save scan: " + e.getMessage());
        }
        return -1;
    }

    public synchronized void saveHackResult(long scanId, String hackId, String hackName, String result) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
            ps.setLong(1, scanId);
            ps.setString(2, hackId);
            ps.setString(3, hackName);
            ps.setString(4, result);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save hack result: " + e.getMessage());
        }
    }

    public synchronized void saveLangResult(long scanId, String language, String response) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO lang_results (scan_id,language,response) VALUES (?,?,?)")) {
            ps.setLong(1, scanId);
            ps.setString(2, language);
            ps.setString(3, response);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save lang result: " + e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> getRecentScans(String type, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (connection == null) return list;

        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        String sql = type != null
                ? "SELECT * FROM scans WHERE type=? ORDER BY timestamp DESC LIMIT ?"
                : "SELECT * FROM scans ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (type != null) { ps.setString(1, type); ps.setInt(2, limit); }
            else ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = rowToMap(rs);
                    rows.add(row);
                    ids.add(((Number) row.get("id")).longValue());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getRecentScans: " + e.getMessage());
            return list;
        }

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long id = ids.get(i);
            String t = (String) row.get("type");
            row.put("results", "hack".equals(t) ? getHackResultsInternal(id) : getLangResultsInternal(id));
            list.add(row);
        }
        return list;
    }

    public synchronized Map<String, Object> getScan(long id) {
        if (connection == null) return null;
        Map<String, Object> row = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) row = rowToMap(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getScan: " + e.getMessage());
            return null;
        }
        if (row == null) return null;
        String type = (String) row.get("type");
        row.put("results", "hack".equals(type) ? getHackResultsInternal(id) : getLangResultsInternal(id));
        return row;
    }

    private List<Map<String, Object>> getHackResultsInternal(long scanId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hack_name, result FROM hack_results WHERE scan_id=? ORDER BY id ASC")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("hack_name", rs.getString("hack_name"));
                    r.put("result",    rs.getString("result"));
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getHackResults: " + e.getMessage());
        }
        return list;
    }

    private List<Map<String, Object>> getLangResultsInternal(long scanId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT language, response FROM lang_results WHERE scan_id=? ORDER BY id ASC")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("language", rs.getString("language"));
                    r.put("response", rs.getString("response"));
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getLangResults: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<Map<String, Object>> getPlayerScans(String playerName) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (connection == null) return list;

        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM scans WHERE LOWER(target_name)=LOWER(?) ORDER BY timestamp DESC LIMIT 100")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = rowToMap(rs);
                    rows.add(row);
                    ids.add(((Number) row.get("id")).longValue());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getPlayerScans: " + e.getMessage());
            return list;
        }

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long id = ids.get(i);
            String type = (String) row.get("type");
            row.put("results", "hack".equals(type) ? getHackResultsInternal(id) : getLangResultsInternal(id));
            list.add(row);
        }
        return list;
    }

    public synchronized boolean deleteScan(long id) {
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("deleteScan: " + e.getMessage());
            return false;
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnName(i), rs.getObject(i));
        return row;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("close: " + e.getMessage());
        }
    }
}
