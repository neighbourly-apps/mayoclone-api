package com.mayoclone.web;

import com.mayoclone.dto.TrainStatusResponse;
import com.mayoclone.trains.TrainStatusProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live train running status. Exposes the {@link TrainStatusProvider} seam; with the
 * default (no real integration wired) every response is {@code available=false},
 * {@code source="none"}.
 */
@RestController
@RequestMapping("/api/trains")
public class TrainStatusController {

    private final TrainStatusProvider provider;

    public TrainStatusController(TrainStatusProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/{trainNumber}/status")
    public TrainStatusResponse status(@PathVariable String trainNumber) {
        return TrainStatusResponse.of(trainNumber, provider.status(trainNumber));
    }
}
