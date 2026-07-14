package com.mayoclone.service;

import com.mayoclone.domain.MenuItem;
import com.mayoclone.dto.BulkImportResult;
import com.mayoclone.dto.BulkImportResult.RowError;
import com.mayoclone.dto.CreateMenuItemRequest;
import com.mayoclone.dto.MenuItemDto;
import com.mayoclone.dto.UpdateMenuItemRequest;
import com.mayoclone.repository.MenuItemRepository;
import com.mayoclone.security.AccountPrincipal;
import com.mayoclone.security.CurrentAccountService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

/** Tenant-scoped CRUD + bulk import for the menu catalog. Cross-tenant ids surface as 404. */
@Service
public class MenuItemService {

    /** Hard cap in bytes (2 MB), matching {@code spring.servlet.multipart.max-file-size}. */
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Column length ceilings mirroring the create DTO / DB columns. */
    private static final int MAX_TEXT = 255;

    private final MenuItemRepository repo;
    private final CurrentAccountService currentAccount;
    private final AuditService auditService;

    public MenuItemService(MenuItemRepository repo, CurrentAccountService currentAccount,
                           AuditService auditService) {
        this.repo = repo;
        this.currentAccount = currentAccount;
        this.auditService = auditService;
    }

    public List<MenuItemDto> list(Boolean available) {
        Long accountId = currentAccount.accountId();
        List<MenuItem> items = available == null
                ? repo.findByAccountIdOrderByCreatedAtDesc(accountId)
                : repo.findByAccountIdAndAvailableOrderByCreatedAtDesc(accountId, available);
        return items.stream().map(MenuItemDto::from).toList();
    }

    @Transactional
    public MenuItemDto create(CreateMenuItemRequest req) {
        MenuItem m = new MenuItem();
        m.setAccountId(currentAccount.accountId());
        m.setName(req.name());
        m.setCategory(req.category());
        m.setImageUrl(req.imageUrl());
        m.setPrice(req.price());
        m.setAvailable(req.available() == null || req.available()); // default true
        m.setCreatedAt(Instant.now());
        return MenuItemDto.from(repo.save(m));
    }

    @Transactional
    public MenuItemDto update(Long id, UpdateMenuItemRequest req) {
        MenuItem m = find(id);
        if (req.name() != null) {
            m.setName(req.name());
        }
        if (req.category() != null) {
            m.setCategory(req.category());
        }
        if (req.imageUrl() != null) {
            m.setImageUrl(req.imageUrl());
        }
        if (req.price() != null) {
            m.setPrice(req.price());
        }
        if (req.available() != null) {
            m.setAvailable(req.available());
        }
        return MenuItemDto.from(repo.save(m));
    }

    @Transactional
    public void delete(Long id) {
        repo.delete(find(id));
    }

    private MenuItem find(Long id) {
        return repo.findByIdAndAccountId(id, currentAccount.accountId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Menu item " + id + " not found"));
    }

    // ---- Bulk import ----

    /**
     * Parse an uploaded .xlsx or .csv (columns: name, category, price, available)
     * and create a menu item per valid row for the caller's tenant. Invalid rows
     * are reported (row number + reason) but do not abort the import.
     */
    @Transactional
    public BulkImportResult bulkImport(MultipartFile file) {
        AccountPrincipal principal = currentAccount.require();
        Long accountId = principal.id();

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "file exceeds 2MB limit");
        }

        List<List<String>> rows = readRows(file);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "The file has no rows");
        }

        Map<String, Integer> cols = headerIndex(rows.get(0));
        Integer nameIdx = cols.get("name");
        Integer categoryIdx = cols.get("category");
        Integer priceIdx = cols.get("price");
        Integer availableIdx = cols.get("available");
        if (nameIdx == null || categoryIdx == null || priceIdx == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Header row must include the columns: name, category, price");
        }

        int created = 0;
        int skipped = 0;
        List<RowError> errors = new ArrayList<>();

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int rowNum = r + 1; // 1-based; the header is row 1
            String name = cell(row, nameIdx);
            String category = cell(row, categoryIdx);
            String priceRaw = cell(row, priceIdx);
            String availableRaw = cell(row, availableIdx);

            if (isBlank(name) && isBlank(category) && isBlank(priceRaw) && isBlank(availableRaw)) {
                skipped++; // blank line
                continue;
            }
            if (isBlank(name)) {
                errors.add(new RowError(rowNum, "name is required"));
                continue;
            }
            if (isBlank(category)) {
                errors.add(new RowError(rowNum, "category is required"));
                continue;
            }
            if (name.trim().length() > MAX_TEXT || category.trim().length() > MAX_TEXT) {
                errors.add(new RowError(rowNum, "name/category exceeds " + MAX_TEXT + " characters"));
                continue;
            }
            BigDecimal price;
            try {
                price = new BigDecimal(priceRaw.trim());
            } catch (NumberFormatException e) {
                errors.add(new RowError(rowNum, "price is not a number"));
                continue;
            }
            if (price.signum() < 0) {
                errors.add(new RowError(rowNum, "price must be zero or positive"));
                continue;
            }

            MenuItem m = new MenuItem();
            m.setAccountId(accountId);
            m.setName(name.trim());
            m.setCategory(category.trim());
            m.setPrice(price);
            m.setAvailable(parseBool(availableRaw));
            m.setCreatedAt(Instant.now());
            repo.save(m);
            created++;
        }

        if (created == 0 && errors.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No data rows found in the file");
        }

        auditService.record(accountId, principal.email(), "menu.bulk_import", "menu_item", null,
                Map.of("created", created, "skipped", skipped, "errors", errors.size()));

        return new BulkImportResult(created, skipped, errors);
    }

    /** Build a valid .xlsx template (header + 3 example rows) as raw bytes. */
    public byte[] sampleTemplateXlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Menu");

            Row header = sheet.createRow(0);
            String[] headers = {"name", "category", "price", "available"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }

            Object[][] examples = {
                    {"Veg Thali", "Thali", 120d, true},
                    {"Chicken Biryani", "Biryani", 180d, true},
                    {"Masala Chai", "Beverages", 20d, true},
            };
            int r = 1;
            for (Object[] ex : examples) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) ex[0]);
                row.createCell(1).setCellValue((String) ex[1]);
                row.createCell(2).setCellValue((Double) ex[2]);
                row.createCell(3).setCellValue((Boolean) ex[3]);
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not build the template");
        }
    }

    // ---- Parsing helpers ----

    private List<List<String>> readRows(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        boolean xlsx = name.endsWith(".xlsx")
                || contentType.contains("spreadsheetml")
                || contentType.contains("ms-excel");
        boolean csv = name.endsWith(".csv")
                || contentType.contains("csv")
                || contentType.equals("text/plain");
        try {
            if (xlsx) {
                return parseXlsx(file);
            }
            if (csv) {
                return parseCsv(file);
            }
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not read the file — is it a valid .xlsx/.csv?");
        }
        throw new ResponseStatusException(BAD_REQUEST, "Unsupported file type. Upload a .xlsx or .csv file");
    }

    private List<List<String>> parseXlsx(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
            if (wb.getNumberOfSheets() == 0) {
                return rows;
            }
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                short last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<List<String>> parseCsv(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) {
                    line = stripBom(line);
                    first = false;
                }
                rows.add(parseCsvLine(line));
            }
        }
        return rows;
    }

    /** Minimal RFC-4180-ish parser: handles quoted fields and escaped ("") quotes. */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).trim().toLowerCase();
            if (!h.isEmpty()) {
                cols.putIfAbsent(h, i);
            }
        }
        return cols;
    }

    private static String cell(List<String> row, Integer idx) {
        if (idx == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        String v = row.get(idx);
        return v == null ? "" : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Optional availability flag. Blank/unrecognised defaults to true; only explicit falsey is false. */
    private static boolean parseBool(String s) {
        if (isBlank(s)) {
            return true;
        }
        String v = s.trim().toLowerCase();
        return !(v.equals("false") || v.equals("no") || v.equals("0") || v.equals("n") || v.equals("f"));
    }

    private static String stripBom(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == 0xFEFF) ? s.substring(1) : s;
    }
}
