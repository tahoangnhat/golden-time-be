package com.goldentime.api.admin;

import com.goldentime.api.security.CurrentUser;
import com.goldentime.api.trace.TraceabilityManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/farms")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminFarmController {
    private final TraceabilityManagementService traceability;

    public AdminFarmController(TraceabilityManagementService traceability) {
        this.traceability = traceability;
    }

    @GetMapping
    public List<TraceabilityManagementService.Farm> list() {
        return traceability.adminFarms();
    }

    @PostMapping("/{farmId}/decision")
    public TraceabilityManagementService.Farm decide(@PathVariable long farmId,
            @Valid @RequestBody FarmDecision request, Authentication authentication) {
        return traceability.decideFarm(farmId, request.approved(), request.reason(), CurrentUser.id(authentication));
    }

    public record FarmDecision(boolean approved, @Size(max = 1000) String reason) {}
}
