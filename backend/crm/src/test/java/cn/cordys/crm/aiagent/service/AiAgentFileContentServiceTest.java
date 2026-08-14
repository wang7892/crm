package cn.cordys.crm.aiagent.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiAgentFileContentServiceTest {

    private final AiAgentFileContentService service = new AiAgentFileContentService(mock(AiAgentLlmClient.class));

    @Test
    void shouldParseUtf8TextAttachment() {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "customer-notes.txt",
                "text/plain",
                "客户关注交期和包装要求".getBytes(StandardCharsets.UTF_8));

        List<AiAgentFileContentService.ParsedSection> sections = service.parse(file, "primary");

        assertThat(sections).singleElement()
                .satisfies(section -> {
                    assertThat(section.title()).isEqualTo("customer-notes.txt");
                    assertThat(section.content()).contains("客户关注交期和包装要求");
                });
    }

    @Test
    void shouldParseExcelAttachment() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("订单号");
            header.createCell(1).setCellValue("客户");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("SO-1001");
            data.createCell(1).setCellValue("示例客户");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytes);

        List<AiAgentFileContentService.ParsedSection> sections = service.parse(file, "primary");

        assertThat(sections).singleElement()
                .satisfies(section -> {
                    assertThat(section.title()).isEqualTo("订单");
                    assertThat(section.content()).contains("订单号", "客户", "SO-1001", "示例客户");
                });
    }

    @Test
    void shouldParseWordParagraphsAndTables() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("客户交付要求");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("要求");
            table.getRow(1).getCell(0).setText("包装");
            table.getRow(1).getCell(1).setText("防潮");
            document.write(output);
            documentBytes = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "delivery.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                documentBytes);

        List<AiAgentFileContentService.ParsedSection> sections = service.parse(file, "primary");

        assertThat(sections).singleElement()
                .satisfies(section -> assertThat(section.content())
                        .contains("客户交付要求", "项目", "要求", "包装", "防潮"));
    }
}
