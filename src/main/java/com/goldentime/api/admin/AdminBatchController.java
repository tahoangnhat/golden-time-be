package com.goldentime.api.admin;

import com.goldentime.api.trace.TraceabilityManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/traceability")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminBatchController {
    private final TraceabilityManagementService traceability;

    public AdminBatchController(TraceabilityManagementService traceability) {
        this.traceability = traceability;
    }

    @PutMapping("/{batchCode}/status")
    public TraceabilityManagementService.BatchSummary updateStatus(@PathVariable String batchCode,
            @Valid @RequestBody BatchStatusUpdate request) {
        return traceability.updateAdminBatchStatus(batchCode, request.batchStatus(), request.note());
    }

    public record BatchStatusUpdate(@NotBlank @Size(max = 24) String batchStatus,
                                    @Size(max = 1000) String note) {}
}
