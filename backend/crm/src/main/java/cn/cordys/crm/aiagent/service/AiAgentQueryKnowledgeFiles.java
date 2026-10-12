package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AiAgentQueryKnowledgeFiles {
    private final AiAgentQueryKnowledgeService knowledge;
    private final AiAgentDatasourceService datasources;
    public AiAgentQueryKnowledgeFiles(AiAgentQueryKnowledgeService knowledge, AiAgentDatasourceService datasources) {
        this.knowledge = knowledge; this.datasources = datasources;
    }

    public byte[] export(String kind, List<String> ids, boolean template, String org, String user) {
        List<Item> values = knowledge.list(kind, org, user);
        if (ids != null && !ids.isEmpty()) {
            if (ids.size() > 500 || !values.stream().map(Item::getId).toList().containsAll(ids))
                throw new IllegalArgumentException("选中的知识不存在或无权访问");
            values = values.stream().filter(i -> ids.contains(i.getId())).toList();
        }
        if (template) values = List.of();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("TERM".equals(kind) ? "术语配置" : "SQL 示例库");
            List<String> headers = headers(kind);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) { header.createCell(i).setCellValue(headers.get(i)); sheet.setColumnWidth(i, i == 1 ? 18000 : 7000); }
            for (int i = 0; i < values.size(); i++) {
                Item item = values.get(i); List<String> cells = new ArrayList<>(List.of(item.getName(), item.getContent()));
                if ("TERM".equals(kind)) cells.add(JSON.toJSONString(item.getSynonyms()));
                cells.add(item.isAllDatasources() ? "ALL" : String.join(";", item.getDatasourceIds())); cells.add(item.isEnabled() ? "true" : "false");
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < cells.size(); j++) row.createCell(j).setCellValue(cells.get(j));
            }
            sheet.createFreezePane(0, 1); workbook.write(output); return output.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("无法导出问数知识", e); }
    }

    @Transactional
    public int importFile(String kind, MultipartFile file, String org, String user) {
        knowledge.list(kind, org, user);
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024) throw new IllegalArgumentException("请选择不超过 5MB 的 Excel/CSV 文件");
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        List<List<String>> rows;
        try {
            if (name.endsWith(".csv")) rows = csv(file);
            else if (name.endsWith(".xlsx") || name.endsWith(".xls")) rows = excel(file);
            else throw new IllegalArgumentException("仅支持 .xlsx、.xls 和 UTF-8 CSV");
        } catch (IOException e) { throw new IllegalArgumentException("无法读取导入文件"); }
        List<String> expected = headers(kind);
        if (rows.isEmpty() || !rows.getFirst().equals(expected)) throw new IllegalArgumentException("文件表头不正确，请先下载导入模板");
        if (rows.size() < 2 || rows.size() > 201) throw new IllegalArgumentException("一次导入 1 到 200 条知识");
        Map<String, String> sources = new HashMap<>(); sources.put("CRM", "CRM"); sources.put("内置 CRM", "CRM");
        Map<String, Long> nameCounts = datasources.list(org, user).stream().collect(java.util.stream.Collectors.groupingBy(s -> s.getName(), java.util.stream.Collectors.counting()));
        datasources.list(org, user).forEach(s -> sources.put(s.getId(), s.getId()));
        datasources.list(org, user).forEach(s -> { if (nameCounts.get(s.getName()) == 1) sources.putIfAbsent(s.getName(), s.getId()); });
        int imported = 0;
        for (int n = 1; n < rows.size(); n++) {
            List<String> row = rows.get(n);
            if (row.stream().allMatch(String::isBlank)) continue;
            try {
                if (row.size() != expected.size()) throw new IllegalArgumentException("列数与模板不一致");
                Item item = new Item(); item.setKind(kind); item.setName(row.get(0)); item.setContent(row.get(1));
                int sourceIndex = "TERM".equals(kind) ? 3 : 2;
                if ("TERM".equals(kind) && !row.get(2).isBlank()) item.setSynonyms(JSON.parseArray(row.get(2), String.class));
                String scope = row.get(sourceIndex).strip(); item.setAllDatasources("ALL".equalsIgnoreCase(scope));
                if (!item.isAllDatasources()) {
                    List<String> ids = new ArrayList<>();
                    for (String label : scope.split(";")) {
                        String id = sources.get(label.strip());
                        if (id == null) throw new IllegalArgumentException("生效数据源不存在、名称不唯一或无权访问");
                        ids.add(id);
                    }
                    item.setDatasourceIds(ids);
                }
                String enabled = row.get(sourceIndex + 1).strip();
                if (!List.of("true", "false", "").contains(enabled.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("启用列应为 true 或 false");
                item.setEnabled(!"false".equalsIgnoreCase(enabled));
                knowledge.save(item, org, user); imported++;
            } catch (RuntimeException e) { throw new IllegalArgumentException("第 " + (n + 1) + " 行导入失败：" + (e instanceof IllegalArgumentException ? e.getMessage() : "内容格式不正确")); }
        }
        if (imported == 0) throw new IllegalArgumentException("没有可导入的知识");
        return imported;
    }

    private static List<String> headers(String kind) {
        return "TERM".equals(kind) ? List.of("术语名称", "术语描述", "同义词(JSON数组)", "生效数据源(ID或唯一名称;分隔;ALL表示所有)", "启用(true/false)")
                : List.of("问题描述", "示例 SQL", "生效数据源(ID或唯一名称)", "启用(true/false)");
    }

    private static List<List<String>> csv(MultipartFile file) throws IOException {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8); CSVParser parser = CSVFormat.RFC4180.parse(reader)) {
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() > 200 || record.size() > 5) throw new IllegalArgumentException("导入文件超过行列限制");
                List<String> values = new ArrayList<>(); record.forEach(values::add);
                if (rows.isEmpty() && !values.isEmpty()) values.set(0, values.getFirst().replaceFirst("^\uFEFF", ""));
                rows.add(values);
            }
            return rows;
        }
    }

    private static List<List<String>> excel(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() != 1) throw new IllegalArgumentException("导入文件只能包含一个工作表");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > 200) throw new IllegalArgumentException("一次最多导入 200 条知识");
            List<List<String>> rows = new ArrayList<>(); DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int columns = sheet.getRow(0) == null ? 0 : sheet.getRow(0).getLastCellNum();
            if (columns > 5) throw new IllegalArgumentException("导入文件超过列数限制");
            for (int n = 0; n <= sheet.getLastRowNum(); n++) {
                Row row = sheet.getRow(n); List<String> cells = new ArrayList<>();
                if (row != null && row.getLastCellNum() > columns) throw new IllegalArgumentException("列数与模板不一致");
                for (int c = 0; c < columns; c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    if (cell != null && cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("导入文件不能包含公式");
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
            return rows;
        }
    }
}
