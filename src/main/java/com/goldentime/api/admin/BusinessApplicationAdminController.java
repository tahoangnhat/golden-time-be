package com.goldentime.api.admin;

import com.goldentime.api.business.BusinessApplicationService;
import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/admin/business-applications")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class BusinessApplicationAdminController {
    private final BusinessApplicationService applications;

    public BusinessApplicationAdminController(BusinessApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public List<BusinessApplicationService.ApplicationSummary> list() {
        return applications.list();
    }

    @GetMapping("/{id}/license")
    public ResponseEntity<Resource> license(@PathVariable long id) {
        BusinessApplicationService.LicenseDocument document = applications.license(id);
        MediaType contentType = MediaType.parseMediaType(document.contentType());
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(document.resource());
    }

    @PostMapping("/{id}/decision")
    public BusinessApplicationService.ApplicationSummary decide(@PathVariable long id,
                                                                 @Valid @RequestBody DecisionRequest request,
                                                                 Authentication authentication) {
        return applications.decide(id, request.approved(), request.reason(), CurrentUser.id(authentication));
    }

    public record DecisionRequest(boolean approved, @Size(max = 1000) String reason) {}
}
