package com.easysys.api.controller;

import com.easysys.api.dto.audience.ContactRequest;
import com.easysys.api.dto.audience.ContactResponse;
import com.easysys.api.service.ContactService;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping
    public ApiResponse<ContactResponse> create(@Valid @RequestBody ContactRequest req) {
        return ApiResponse.ok(contactService.create(req));
    }

    @GetMapping
    public ApiResponse<PageResponse<ContactResponse>> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(contactService.list(keyword, normPage(page), normSize(size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContactResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(contactService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ContactResponse> update(@PathVariable Long id, @Valid @RequestBody ContactRequest req) {
        return ApiResponse.ok(contactService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        contactService.delete(id);
        return ApiResponse.ok(null);
    }

    static long normPage(long page) {
        return page < 1 ? 1 : page;
    }

    static long normSize(long size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, 200);
    }
}