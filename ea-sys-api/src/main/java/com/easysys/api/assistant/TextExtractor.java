package com.easysys.api.assistant;

import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档文本抽取：txt/md（UTF-8）、csv（引号感知的小解析器）、xlsx/docx（POI）、
 * pdf（PDFBox 3）。统一产出扁平文本，交由 {@link TextChunker} 分块后入知识库。
 */
public final class TextExtractor {

    private static final String SUPPORTED = "支持 txt/md/csv/xlsx/docx/pdf";

    private TextExtractor() {
    }

    public static String extract(byte[] bytes, String filename) {
        String ext = extension(filename);
        return switch (ext) {
            case "txt", "md", "markdown" -> textPlain(bytes);
            case "csv" -> textCsv(bytes);
            case "xlsx" -> textXlsx(bytes);
            case "docx" -> textDocx(bytes);
            case "pdf" -> textPdf(bytes);
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型：." + ext + "，" + SUPPORTED);
        };
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    private static String textPlain(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }

    /** CSV：RFC4180 引号感知（支持字段内含逗号/换行/双引号转义）；行内字段以逗号拼接。 */
    private static String textCsv(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> row = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                lines.add(String.join("，", row));
                row.clear();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            lines.add(String.join("，", row));
        }
        return String.join("\n", lines);
    }

    private static String textXlsx(byte[] bytes) {
        DataFormatter fmt = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes); Workbook wb = WorkbookFactory.create(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    joinRow(sb, fmt, row);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "xlsx 解析失败：" + e.getMessage());
        }
        return sb.toString();
    }

    private static void joinRow(StringBuilder sb, DataFormatter fmt, Row row) {
        List<String> cells = new ArrayList<>();
        for (Cell cell : row) {
            cells.add(fmt.formatCellValue(cell).trim());
        }
        if (!cells.isEmpty()) {
            sb.append(String.join("，", cells)).append('\n');
        }
    }

    private static String textDocx(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes); XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String line = p.getText().trim();
                if (line.isBlank()) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().replace('\n', ' ').trim());
                    }
                    sb.append(String.join("，", cells)).append('\n');
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "docx 解析失败：" + e.getMessage());
        }
        return sb.toString();
    }

    private static String textPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "pdf 解析失败（加密或损坏？）：" + e.getMessage());
        }
    }
}