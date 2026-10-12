package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.mapper.AiAgentDatasourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

@Service
public class AiAgentDatasourceService {
    private final AiAgentDatasourceMapper mapper;
    private final AiAgentDatasourceConnections connections;
    private final AiAgentDatasourceSecrets secrets;
    private final AiAgentTabularImportService imports;

    public AiAgentDatasourceService(AiAgentDatasourceMapper mapper, AiAgentDatasourceConnections connections,
                                   AiAgentDatasourceSecrets secrets, AiAgentTabularImportService imports) {
        this.mapper = mapper; this.connections = connections; this.secrets = secrets; this.imports = imports;
    }

    public List<View> list(String org, String user) { requireIdentity(org, user); return mapper.list(org, user).stream().map(this::view).toList(); }
    public View get(String id, String org, String user) { return view(owned(id, org, user)); }

    Stored owned(String id, String org, String user) {
        requireIdentity(org, user);
        Stored source = mapper.get(id, org, user);
        if (source == null) throw new IllegalArgumentException("数据源不存在或无权访问");
        return source;
    }

    private static void requireIdentity(String org, String user) {
        if (org == null || org.isBlank() || user == null || user.isBlank()) throw new IllegalArgumentException("当前用户或组织不可用");
    }

    Config resolved(Config request, String org, String user) {
        requireIdentity(org, user);
        Config c = JSON.parseObject(JSON.toJSONString(request), Config.class);
        if (c.getId() != null && !c.getId().isBlank()) {
            Stored existing = owned(c.getId(), org, user);
            if (!existing.getType().equals(c.getType())) throw new IllegalArgumentException("不能修改数据源类型，请新建数据源");
            Config old = JSON.parseObject(existing.getConfigJson(), Config.class);
            if ("FILE".equals(c.getType())) {
                old.setName(c.getName()); old.setSelectedTables(c.getSelectedTables()); old.setRevision(c.getRevision());
                old.setMetadata(c.getMetadata()); old.setRelations(c.getRelations());
                c = old;
                c.setPassword(secrets.decrypt(c.getPassword()));
            } else if (c.getPassword() == null || c.getPassword().isEmpty()) c.setPassword(secrets.decrypt(old.getPassword()));
        } else if ("FILE".equals(c.getType())) throw new IllegalArgumentException("请先上传 Excel/CSV 文件");
        AiAgentDatasourceConnections.validate(c);
        c.setSchema(AiAgentDatasourceConnections.schema(c));
        return c;
    }

    public void test(Config request, String org, String user) {
        Config c = resolved(request, org, user);
        try (Connection connection = connections.open(c)) {
            if (!connection.isValid(10)) throw new SQLException("Invalid connection");
        } catch (SQLException e) { throw connectionFailure(e); }
    }

    public List<Table> tables(Config request, String org, String user) {
        Config c = resolved(request, org, user);
        if ("FILE".equals(c.getType())) return allTables(owned(c.getId(), org, user));
        try { return connections.inspect(c); } catch (SQLException e) { throw connectionFailure(e); }
    }

    public View save(Config request, String org, String user) {
        Config c = resolved(request, org, user);
        Stored previous = c.getId() == null || c.getId().isBlank() ? null : owned(c.getId(), org, user);
        if (previous != null && !Objects.equals(request.getRevision(), previous.getRevision())) {
            throw new IllegalArgumentException("数据源已被修改，请刷新后重试");
        }
        List<Table> tables = tables(request, org, user);
        Set<String> available = new HashSet<>(tables.stream().map(Table::getName).toList());
        List<String> chosen = c.getSelectedTables() == null ? List.of() : c.getSelectedTables().stream().distinct().toList();
        if (chosen.isEmpty() || chosen.size() > 60 || !available.containsAll(chosen)) {
            throw new IllegalArgumentException("请选择 1 至 60 张当前数据源的数据表");
        }
        applyMetadata(tables, c.getMetadata());
        validateRelations(tables, chosen, c.getRelations());
        Stored stored = previous == null ? new Stored() : previous;
        long version = previous == null ? 0 : previous.getRevision();
        stored.setId(previous == null ? UUID.randomUUID().toString().replace("-", "") : previous.getId());
        stored.setOrganizationId(org); stored.setOwnerId(user);
        stored.setName(c.getName().trim()); stored.setType(c.getType());
        c.setId(stored.getId()); c.setPassword(secrets.encrypt(Objects.requireNonNullElse(c.getPassword(), "")));
        c.setSelectedTables(new ArrayList<>()); c.setRevision(null);
        c.setMetadata(new ArrayList<>());
        stored.setConfigJson(JSON.toJSONString(c)); stored.setTablesJson(JSON.toJSONString(tables));
        stored.setSelectedJson(JSON.toJSONString(chosen)); stored.setRevision(version + 1); stored.setUpdateTime(System.currentTimeMillis());
        int changed = previous == null ? mapper.insert(stored) : mapper.update(stored, version);
        if (changed != 1) throw new IllegalArgumentException("数据源已被修改，请刷新后重试");
        return view(stored);
    }

    public View upload(String name, MultipartFile file, String encoding, String org, String user) {
        requireIdentity(org, user);
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("请输入不超过 100 字的数据源名称");
        String id = UUID.randomUUID().toString().replace("-", "");
        var imported = imports.importFile(id, name.trim(), file, encoding);
        try {
            Config c = imported.config(); c.setPassword(secrets.encrypt(c.getPassword()));
            Stored stored = new Stored(); stored.setId(id); stored.setName(c.getName()); stored.setType("FILE");
            stored.setOrganizationId(org); stored.setOwnerId(user); stored.setConfigJson(JSON.toJSONString(c));
            stored.setTablesJson(JSON.toJSONString(imported.tables())); stored.setSelectedJson("[]");
            stored.setRevision(1); stored.setUpdateTime(System.currentTimeMillis());
            mapper.insert(stored);
            return view(stored);
        } catch (RuntimeException e) { imports.deleteFile(id); throw e; }
    }

    public void delete(String id, String org, String user) {
        Stored source = owned(id, org, user);
        if (mapper.delete(id, org, user) != 1) throw new IllegalArgumentException("数据源已被删除，请刷新后重试");
        if ("FILE".equals(source.getType())) imports.deleteFile(id);
    }

    public void populateContext(String id, AiAgentContext context) {
        View source = get(id, context.getOrganizationId(), context.getUserId());
        if (source.getSelectedTables().isEmpty()) throw new IllegalArgumentException("数据源尚未选择数据表，请先完成配置");
        context.setDatasourceId(source.getId()); context.setDatasourceRevision(source.getRevision());
        context.setDatasourceType(source.getType()); context.setDatasourceName(source.getName());
        context.setDatasourceSchema(source.getTables().stream().filter(t -> source.getSelectedTables().contains(t.getName())).toList());
        context.setDatasourceNamespace(source.getSchema());
        context.setDatasourceRelations(source.getRelations());
    }

    Connection openForQuery(AiAgentContext context) throws SQLException {
        Stored source = owned(context.getDatasourceId(), context.getOrganizationId(), context.getUserId());
        if (source.getRevision() != context.getDatasourceRevision()) throw new IllegalArgumentException("数据源已变更，请重新发起查询");
        Config c = JSON.parseObject(source.getConfigJson(), Config.class);
        c.setPassword(secrets.decrypt(c.getPassword()));
        return connections.open(c);
    }

    private List<Table> allTables(Stored source) { return JSON.parseArray(source.getTablesJson(), Table.class); }
    private View view(Stored source) {
        Config c = JSON.parseObject(source.getConfigJson(), Config.class);
        View v = new View(); v.setId(source.getId()); v.setName(source.getName()); v.setType(source.getType());
        v.setHost(c.getHost()); v.setPort(c.getPort()); v.setDatabase(c.getDatabase()); v.setSchema(c.getSchema());
        v.setUsername("FILE".equals(c.getType()) ? null : c.getUsername()); v.setSsl(c.isSsl());
        v.setPasswordConfigured(c.getPassword() != null && !c.getPassword().isBlank());
        v.setRevision(source.getRevision()); v.setUpdateTime(source.getUpdateTime());
        v.setTables(allTables(source)); v.setTableCount(v.getTables().size());
        v.setSelectedTables(JSON.parseArray(source.getSelectedJson(), String.class));
        v.setRelations(c.getRelations() == null ? List.of() : c.getRelations());
        return v;
    }

    static void applyMetadata(List<Table> tables, List<Table> metadata) {
        if (metadata == null) return;
        if (metadata.size() > 500) throw new IllegalArgumentException("表元数据过多");
        for (Table edit : metadata) {
            Table table = tables.stream().filter(t -> t.getName().equals(edit.getName())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("元数据引用了不存在的表"));
            table.setComment(description(edit.getComment()));
            if (edit.getColumns() == null || edit.getColumns().size() > 256) throw new IllegalArgumentException("字段元数据无效");
            for (Column field : edit.getColumns()) {
                Column column = table.getColumns().stream().filter(c -> c.getName().equals(field.getName())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("元数据引用了不存在的字段"));
                column.setComment(description(field.getComment()));
            }
        }
    }

    static void validateRelations(List<Table> tables, List<String> chosen, List<Relation> relations) {
        if (relations == null || relations.size() > 100) throw new IllegalArgumentException("最多配置 100 条表关系");
        Set<String> unique = new HashSet<>();
        for (Relation r : relations) {
            if (r == null || !chosen.contains(r.getSourceTable()) || !chosen.contains(r.getTargetTable()))
                throw new IllegalArgumentException("表关系只能引用选中的表");
            for (var pair : List.of(Map.entry(r.getSourceTable(), Objects.requireNonNullElse(r.getSourceColumn(), "")),
                    Map.entry(r.getTargetTable(), Objects.requireNonNullElse(r.getTargetColumn(), "")))) {
                if (tables.stream().filter(t -> t.getName().equals(pair.getKey())).flatMap(t -> t.getColumns().stream())
                        .noneMatch(c -> c.getName().equals(pair.getValue()))) throw new IllegalArgumentException("表关系字段不存在");
            }
            if (!unique.add(JSON.toJSONString(r))) throw new IllegalArgumentException("表关系重复");
        }
    }

    private static String description(String value) {
        if (value == null) return "";
        if (value.length() > 2000 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("说明最多 2000 字符");
        return value.strip();
    }

    private IllegalArgumentException connectionFailure(SQLException e) {
        // Driver messages can contain hosts, usernames or password-bearing connection properties.
        String state = e.getSQLState();
        return new IllegalArgumentException("连接失败，请检查地址、端口、数据库、Schema、账号、密码和服务器网络。"
                + (state != null && state.matches("[A-Z0-9]{5}") ? "（SQLState: " + state + "）" : ""));
    }
}
