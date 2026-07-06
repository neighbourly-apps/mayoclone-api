package com.mayoclone.web;

import com.mayoclone.dto.CreateRiderRequest;
import com.mayoclone.dto.RiderDto;
import com.mayoclone.dto.UpdateRiderRequest;
import com.mayoclone.service.RiderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/riders")
public class RiderController {

    private final RiderService riderService;

    public RiderController(RiderService riderService) {
        this.riderService = riderService;
    }

    @GetMapping
    public List<RiderDto> list() {
        return riderService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiderDto create(@Valid @RequestBody CreateRiderRequest req) {
        return riderService.create(req);
    }

    @PatchMapping("/{id}")
    public RiderDto update(@PathVariable Long id, @Valid @RequestBody UpdateRiderRequest req) {
        return riderService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        riderService.delete(id);
    }
}
