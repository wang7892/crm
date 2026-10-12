package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Table;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class AiAgentTabularImportService {
    private static final int MAX_CELLS = 200_000;
    private final AiAgentDatasourceConnections connections;
    public AiAgentTabularImportService(AiAgentDatasourceConnections connections) { this.connections = connections; }

    record SheetData(String title, List<String> headers, List<List<Object>> rows) { }
    record Imported(Config config, List<Table> tables) { }

    public Imported importFile(String id, String name, MultipartFile file, String encoding) {
        if (file == null || file.isEmpty() || file.getSize() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("请选择不超过 20MB 的 Excel/CSV 文件");
        }
        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        List<SheetData> sheets;
        try (InputStream input = file.getInputStream()) {
            if (filename.endsWith(".csv")) sheets = List.of(readCsv(input, encoding));
            else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) sheets = readExcel(input);
            else throw new IllegalArgumentException("仅支持 .xlsx、.xls 和 .csv 文件");
        } catch (IOException e) { throw new IllegalArgumentException("无法读取文件，请检查文件格式和编码"); }
        if (sheets.isEmpty()) throw new IllegalArgumentException("文件中没有包含表头的数据表");
        String password = UUID.randomUUID().toString();
        Config config = new Config(); config.setId(id); config.setName(name); config.setType("FILE");
        config.setUsername("agent_reader"); config.setPassword(password); config.setSchema("PUBLIC");
        String original = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
        config.setDatabase(original.substring(0, Math.min(128, original.length())));
        List<Table> tables = new ArrayList<>();
        try {
            AiAgentDatasourceStorage.directory(connections.filePath(id).getParent());
            String url = "jdbc:h2:file:" + connections.filePath(id).toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
            try (Connection db = DriverManager.getConnection(url, "sa", "")) {
                db.setAutoCommit(false);
                for (SheetData sheet : sheets) tables.add(importSheet(db, sheet, tables.size() + 1));
                try (Statement statement = db.createStatement()) {
                    statement.execute("CREATE USER agent_reader PASSWORD '" + password + "'");
                    for (Table table : tables) statement.execute("GRANT SELECT ON \"" + table.getName() + "\" TO agent_reader");
                    statement.execute("ALTER USER sa SET PASSWORD '" + UUID.randomUUID() + "'");
                }
                db.commit();
            }
            AiAgentDatasourceStorage.ownerOnly(connections.filePath(id).resolveSibling(id + ".mv.db"));
            return new Imported(config, tables);
        } catch (Exception e) {
            deleteFile(id);
            throw new IllegalArgumentException("文件导入失败，请检查表头、数值范围和文件内容");
        }
    }

    List<SheetData> readExcel(InputStream input) throws IOException {
        List<SheetData> sheets = new ArrayList<>();
        int cells = 0;
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() > 30) throw new IllegalArgumentException("Excel 最多支持 30 个工作表");
            for (Sheet sheet : workbook) {
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null || header.getLastCellNum() <= 0) continue;
                int width = header.getLastCellNum();
                if (width > 256) throw new IllegalArgumentException("数据表最多支持 256 列");
                List<String> headers = new ArrayList<>();
                for (int i = 0; i < width; i++) headers.add(formatter.formatCellValue(header.getCell(i)));
                List<List<Object>> rows = new ArrayList<>();
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    if ((cells += width) > MAX_CELLS) throw new IllegalArgumentException("文件最多支持 20 万个数据单元格");
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    List<Object> values = new ArrayList<>();
                    for (int i = 0; i < width; i++) values.add(excelValue(row.getCell(i)));
                    if (values.stream().anyMatch(Objects::nonNull)) rows.add(values);
                }
                sheets.add(new SheetData(sheet.getSheetName(), headers, rows));
            }
        }
        return sheets;
    }

    private Object excelValue(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        // Use cached formula values only; never evaluate formulas, links or external data.
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue() : BigDecimal.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue();
            case STRING -> scalar(cell.getStringCellValue());
            case ERROR -> cell.getErrorCellValue() + "";
            default -> null;
        };
    }

    SheetData readCsv(InputStream input, String encoding) throws IOException {
        if (!Set.of("UTF-8", "GB18030").contains(encoding)) throw new IllegalArgumentException("不支持的 CSV 编码");
        try (Reader reader = new InputStreamReader(input, Charset.forName(encoding));
             CSVParser parser = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get().parse(reader)) {
            List<String> headers = null;
            List<List<Object>> rows = new ArrayList<>();
            int cells = 0;
            for (var record : parser) {
                if (record.size() > 256 || (cells += record.size()) > MAX_CELLS) {
                    throw new IllegalArgumentException("文件超过 256 列或 20 万个单元格限制");
                }
                if (headers == null) {
                    headers = new ArrayList<>();
                    for (String value : record) headers.add(value.replace("\uFEFF", ""));
                    continue;
                }
                if (record.size() != headers.size()) throw new IllegalArgumentException("CSV 行列数与表头不一致，行号：" + record.getRecordNumber());
                List<Object> values = new ArrayList<>();
                for (String value : record) values.add(scalar(value));
                rows.add(values);
            }
            if (headers == null) throw new IllegalArgumentException("CSV 文件没有表头");
            return new SheetData("CSV", headers, rows);
        }
    }

    private static Object scalar(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (trimmed.length() > 8000) throw new IllegalArgumentException("单个单元格最多支持 8000 个字符");
        // Preserve identifiers such as 00123 and phone numbers as text.
        if (trimmed.matches("-?(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,12})?")) return new BigDecimal(trimmed);
        if (Set.of("true", "false").contains(trimmed.toLowerCase(Locale.ROOT))) return Boolean.valueOf(trimmed);
        try { if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(trimmed); } catch (RuntimeException ignored) { }
        try { if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) return LocalDateTime.parse(trimmed.replace(' ', 'T')); } catch (RuntimeException ignored) { }
        return text;
    }

    private Table importSheet(Connection db, SheetData sheet, int index) throws SQLException {
        Table table = new Table(); table.setName("table_" + index); table.setComment(sheet.title());
        List<String> types = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < sheet.headers().size(); i++) {
            String header = sheet.headers().get(i).trim();
            if (header.isBlank()) throw new IllegalArgumentException("表头不能为空");
            String name = header.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") ? header.toLowerCase(Locale.ROOT) : "col_" + (i + 1);
            if (!names.add(name)) { name = "col_" + (i + 1) + "_dup"; names.add(name); }
            String type = columnType(sheet.rows(), i);
            Column column = new Column(); column.setName(name); column.setComment(header); column.setType(type);
            table.getColumns().add(column); types.add(type);
        }
        String definitions = table.getColumns().stream().map(c -> "\"" + c.getName() + "\" " + c.getType()).collect(java.util.stream.Collectors.joining(","));
        try (Statement statement = db.createStatement()) { statement.execute("CREATE TABLE \"" + table.getName() + "\" (" + definitions + ")"); }
        String columns = table.getColumns().stream().map(c -> "\"" + c.getName() + "\"").collect(java.util.stream.Collectors.joining(","));
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO \"" + table.getName() + "\" (" + columns + ") VALUES ("
                + String.join(",", Collections.nCopies(types.size(), "?")) + ")")) {
            int batch = 0;
            for (List<Object> row : sheet.rows()) {
                for (int i = 0; i < row.size(); i++) {
                    Object value = row.get(i);
                    insert.setObject(i + 1, value == null ? null : types.get(i).startsWith("VARCHAR") ? value.toString() : value);
                }
                insert.addBatch();
                if (++batch % 500 == 0) insert.executeBatch();
            }
            insert.executeBatch();
        }
        return table;
    }

    private String columnType(List<List<Object>> rows, int column) {
        Set<String> types = new HashSet<>();
        for (var row : rows) {
            Object v = row.get(column);
            if (v != null) types.add(v instanceof BigDecimal ? "DECIMAL(38,12)" : v instanceof Boolean ? "BOOLEAN"
                    : v instanceof LocalDateTime ? "TIMESTAMP" : v instanceof LocalDate ? "DATE" : "VARCHAR(8000)");
        }
        if (types.size() == 1) return types.iterator().next();
        if (types.equals(Set.of("DATE", "TIMESTAMP"))) return "TIMESTAMP";
        return "VARCHAR(8000)";
    }

    public void deleteFile(String id) {
        try {
            Files.deleteIfExists(connections.filePath(id).resolveSibling(id + ".mv.db"));
            Files.deleteIfExists(connections.filePath(id).resolveSibling(id + ".trace.db"));
        } catch (IOException e) { throw new IllegalStateException("无法删除数据源文件，请检查存储目录权限", e); }
    }
}
