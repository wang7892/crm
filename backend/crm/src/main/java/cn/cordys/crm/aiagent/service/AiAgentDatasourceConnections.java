package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Config;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Table;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Column;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

@Component
public class AiAgentDatasourceConnections {
    private final Path root;

    public AiAgentDatasourceConnections(@Value("${crm.ai-agent.datasource.storage-path:runtime/ai-agent/datasources}") String path) {
        root = Path.of(path).toAbsolutePath().normalize();
    }

    public Path filePath(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid file datasource id");
        return root.resolve(id);
    }

    public static void validate(Config c) {
        if (!Set.of("MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "FILE").contains(c.getType())) {
            throw new IllegalArgumentException("不支持的数据源类型");
        }
        if (c.getName() == null || c.getName().isBlank()) throw new IllegalArgumentException("请输入数据源名称");
        if ("FILE".equals(c.getType())) return;
        if (c.getHost() == null || !c.getHost().matches("[A-Za-z0-9][A-Za-z0-9.\\-]*")) {
            throw new IllegalArgumentException("主机仅支持 IPv4 或域名，不能填写 JDBC URL");
        }
        if (c.getPort() == null || c.getPort() < 1 || c.getPort() > 65535) throw new IllegalArgumentException("请输入有效端口");
        if (c.getDatabase() == null || !c.getDatabase().matches("[\\p{L}\\p{N}_.$-]{1,128}")) {
            throw new IllegalArgumentException("请输入数据库名（Oracle 为服务名），不能包含连接参数");
        }
        if (c.getUsername() == null || c.getUsername().isBlank()) throw new IllegalArgumentException("请输入数据库用户名");
        if (c.getSchema() != null && !c.getSchema().isBlank() && !c.getSchema().matches("[\\p{L}\\p{N}_$]{1,128}")) {
            throw new IllegalArgumentException("请输入有效 Schema");
        }
    }

    public static String schema(Config c) {
        if ("MYSQL".equals(c.getType())) return c.getDatabase();
        if (c.getSchema() != null && !c.getSchema().isBlank()) return c.getSchema();
        return switch (c.getType()) {
            case "POSTGRESQL" -> "public";
            case "SQLSERVER" -> "dbo";
            case "ORACLE" -> c.getUsername().toUpperCase(Locale.ROOT);
            default -> "PUBLIC";
        };
    }

    public Connection open(Config c) throws SQLException {
        validate(c);
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", Objects.requireNonNullElse(c.getPassword(), ""));
        String server = c.getHost() + ":" + c.getPort();
        String url;
        String driver;
        switch (c.getType()) {
            case "MYSQL" -> {
                driver = "com.mysql.cj.jdbc.Driver";
                url = "jdbc:mysql://" + server + "/" + c.getDatabase();
                p.setProperty("connectTimeout", "10000"); p.setProperty("socketTimeout", "30000");
                p.setProperty("sslMode", c.isSsl() ? "VERIFY_IDENTITY" : "DISABLED");
                p.setProperty("allowLoadLocalInfile", "false"); p.setProperty("allowUrlInLocalInfile", "false");
                p.setProperty("allowMultiQueries", "false"); p.setProperty("autoDeserialize", "false");
                p.setProperty("allowPublicKeyRetrieval", "false");
            }
            case "POSTGRESQL" -> {
                driver = "org.postgresql.Driver";
                url = "jdbc:postgresql://" + server + "/" + c.getDatabase();
                p.setProperty("connectTimeout", "10"); p.setProperty("socketTimeout", "30");
                p.setProperty("sslmode", c.isSsl() ? "verify-full" : "disable");
                p.setProperty("currentSchema", schema(c));
            }
            case "SQLSERVER" -> {
                driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                url = "jdbc:sqlserver://" + server;
                p.setProperty("databaseName", c.getDatabase()); p.setProperty("loginTimeout", "10");
                p.setProperty("socketTimeout", "30000"); p.setProperty("encrypt", String.valueOf(c.isSsl()));
                p.setProperty("trustServerCertificate", "false");
            }
            case "ORACLE" -> {
                driver = "oracle.jdbc.OracleDriver";
                url = c.isSsl() ? "jdbc:oracle:thin:@tcps://" + server + "/" + c.getDatabase()
                        : "jdbc:oracle:thin:@//" + server + "/" + c.getDatabase();
                p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000"); p.setProperty("oracle.jdbc.ReadTimeout", "30000");
                p.setProperty("oracle.net.ssl_server_dn_match", "true");
            }
            case "FILE" -> {
                driver = "org.h2.Driver";
                url = "jdbc:h2:file:" + filePath(c.getId()).toString().replace('\\', '/') + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
            }
            default -> throw new IllegalArgumentException("不支持的数据源类型");
        }
        try {
            Driver d = (Driver) Class.forName(driver).getDeclaredConstructor().newInstance();
            Connection connection = d.connect(url, p);
            if (connection == null) throw new SQLException("Driver rejected connection");
            return connection;
        } catch (SQLException e) { throw e; }
        catch (ReflectiveOperationException e) { throw new SQLException("Missing JDBC driver", e); }
    }

    public List<Table> inspect(Config c) throws SQLException {
        try (Connection connection = open(c)) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = Set.of("MYSQL", "SQLSERVER").contains(c.getType()) ? c.getDatabase() : null;
            String schema = "MYSQL".equals(c.getType()) ? null : schema(c);
            String currentCatalog = catalog == null ? connection.getCatalog() : catalog;
            List<Table> tables = new ArrayList<>();
            try (ResultSet rows = meta.getTables(catalog, escaped(meta, schema), "%", new String[]{"TABLE", "VIEW"})) {
                while (rows.next()) {
                    if (tables.size() >= 500) throw new IllegalArgumentException("当前库超过 500 张表，请缩小 Schema 范围");
                    String name = rows.getString("TABLE_NAME");
                    if (!safeIdentifier(name)) continue;
                    Table table = new Table(); table.setName(name); table.setComment(trim(rows.getString("REMARKS")));
                    try (ResultSet columns = meta.getColumns(catalog, escaped(meta, schema), escaped(meta, name), "%")) {
                        while (columns.next()) {
                            if (table.getColumns().size() >= 256) throw new IllegalArgumentException("表字段超过 256 列，请缩小查询视图");
                            Column column = new Column(); column.setName(columns.getString("COLUMN_NAME"));
                            column.setType(columns.getString("TYPE_NAME")); column.setComment(trim(columns.getString("REMARKS")));
                            table.getColumns().add(column);
                        }
                    }
                    if (!table.getColumns().isEmpty()) tables.add(table);
                }
            }
            tables.sort(Comparator.comparing(Table::getName));
            for (Table table : tables) {
                try (ResultSet keys = meta.getImportedKeys(catalog, schema, table.getName())) {
                    while (keys.next() && table.getRelations().size() < 100) {
                        String target = keys.getString("PKTABLE_NAME");
                        String targetCatalog = keys.getString("PKTABLE_CAT");
                        if (currentCatalog != null && targetCatalog != null && !java.util.Objects.equals(currentCatalog, targetCatalog)
                                || !java.util.Objects.equals(schema, keys.getString("PKTABLE_SCHEM"))
                                || tables.stream().noneMatch(t -> t.getName().equals(target))) continue;
                        var relation = new cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Relation();
                        relation.setSourceTable(table.getName()); relation.setSourceColumn(keys.getString("FKCOLUMN_NAME"));
                        relation.setTargetTable(target); relation.setTargetColumn(keys.getString("PKCOLUMN_NAME"));
                        table.getRelations().add(relation);
                    }
                } catch (java.sql.SQLFeatureNotSupportedException ignored) {
                    // Some views/drivers do not expose foreign keys; manual relationships remain available.
                }
            }
            return tables;
        }
    }

    static boolean safeIdentifier(String name) { return name != null && name.matches("[\\p{L}\\p{N}_$ -]{1,128}"); }
    private static String trim(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 400)); }
    private static String escaped(DatabaseMetaData m, String s) throws SQLException {
        if (s == null) return null;
        String escape = m.getSearchStringEscape();
        return s.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
