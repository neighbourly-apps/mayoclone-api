package com.mayoclone.web;

import com.mayoclone.dto.BulkImportResult;
import com.mayoclone.dto.CreateMenuItemRequest;
import com.mayoclone.dto.MenuItemDto;
import com.mayoclone.dto.UpdateMenuItemRequest;
import com.mayoclone.service.MenuItemService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Tenant-scoped menu catalog CRUD. */
@RestController
@RequestMapping("/api/menu-items")
public class MenuItemController {

    private final MenuItemService menuItemService;

    public MenuItemController(MenuItemService menuItemService) {
        this.menuItemService = menuItemService;
    }

    @GetMapping
    public List<MenuItemDto> list(@RequestParam(required = false) Boolean available) {
        return menuItemService.list(available);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemDto create(@Valid @RequestBody CreateMenuItemRequest req) {
        return menuItemService.create(req);
    }

    @PatchMapping("/{id}")
    public MenuItemDto update(@PathVariable Long id, @Valid @RequestBody UpdateMenuItemRequest req) {
        return menuItemService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        menuItemService.delete(id);
    }

    /**
     * {@code POST /api/menu-items/bulk-import} — multipart field {@code file} (.xlsx or .csv,
     * ≤2MB). Creates a menu item per valid row for the caller's tenant; invalid rows are
     * reported in {@code errors} without aborting the rest.
     */
    @PostMapping("/bulk-import")
    public BulkImportResult bulkImport(@RequestParam("file") MultipartFile file) {
        return menuItemService.bulkImport(file);
    }

    /** {@code GET /api/menu-items/sample-template} — a ready-to-fill .xlsx download. */
    @GetMapping("/sample-template")
    public ResponseEntity<byte[]> sampleTemplate() {
        byte[] body = menuItemService.sampleTemplateXlsx();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"menu-template.xlsx\"")
                .body(body);
    }
}
