package com.goldentime.api.partner;

import com.goldentime.api.articles.ArticleImageStorage;
import com.goldentime.api.security.CurrentUser;
import com.goldentime.api.trace.TraceabilityManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/shop")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class ShopTraceabilityController {
    private final TraceabilityManagementService traceability;
    private final ArticleImageStorage imageStorage;

    public ShopTraceabilityController(TraceabilityManagementService traceability, ArticleImageStorage imageStorage) {
        this.traceability = traceability;
        this.imageStorage = imageStorage;
    }

    @GetMapping("/farms")
    public List<TraceabilityManagementService.Farm> farms(Authentication authentication) {
        return traceability.farms(CurrentUser.id(authentication));
    }

    @PostMapping("/farms")
    @ResponseStatus(HttpStatus.CREATED)
    public TraceabilityManagementService.Farm createFarm(Authentication authentication,
            @Valid @RequestBody FarmWrite request) {
        return traceability.createFarm(CurrentUser.id(authentication), request.toServiceRequest());
    }

    @PutMapping("/farms/{farmId}")
    public TraceabilityManagementService.Farm updateFarm(Authentication authentication, @PathVariable long farmId,
            @Valid @RequestBody FarmWrite request) {
        return traceability.updateFarm(CurrentUser.id(authentication), farmId, request.toServiceRequest());
    }

    @DeleteMapping("/farms/{farmId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFarm(Authentication authentication, @PathVariable long farmId) {
        traceability.deleteFarm(CurrentUser.id(authentication), farmId);
    }

    @PostMapping(value = "/farms/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadFarmImage(Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        traceability.farms(CurrentUser.id(authentication));
        return imageStorage.saveFarmImage(file);
    }

    @PostMapping(value = "/farms/certifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadFarmCertification(Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        traceability.farms(CurrentUser.id(authentication));
        return imageStorage.saveFarmImage(file);
    }

    @GetMapping("/batches")
    public List<TraceabilityManagementService.BatchSummary> batches(Authentication authentication) {
        return traceability.batches(CurrentUser.id(authentication));
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public TraceabilityManagementService.BatchSummary createBatch(Authentication authentication,
            @Valid @RequestBody BatchWrite request) {
        return traceability.createBatch(CurrentUser.id(authentication), request.toServiceRequest());
    }

    @PutMapping("/batches/{batchCode}")
    public TraceabilityManagementService.BatchSummary updateBatch(Authentication authentication,
            @PathVariable String batchCode, @Valid @RequestBody BatchWrite request) {
        return traceability.updateBatch(CurrentUser.id(authentication), batchCode, request.toServiceRequest());
    }

    @PostMapping(value = "/batches/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadBatchEvidence(Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        traceability.batches(CurrentUser.id(authentication));
        return imageStorage.saveBatchEvidence(file);
    }

    public record FarmWrite(@NotBlank @Size(max = 255) String name,
                            @NotBlank @Size(max = 512) String address,
                            @NotBlank @Size(max = 255) String managerName,
                            @NotBlank @Size(max = 32) String phone,
                            @NotBlank @Size(max = 2000) String fruitTypes,
                            @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal areaHectares,
                            @Size(max = 2000) String certifications,
                            @NotBlank @Size(max = 1024) String certificationImageUrl,
                            @Size(max = 1024) String imageUrl,
                            @Size(max = 10) List<@Size(max = 1024) String> imageUrls) {
        TraceabilityManagementService.FarmWrite toServiceRequest() {
            return new TraceabilityManagementService.FarmWrite(name, address, managerName, phone, fruitTypes,
                    areaHectares, certifications, certificationImageUrl, imageUrl, imageUrls);
        }
    }

    public record BatchWrite(@NotNull @Positive Long productId,
                             @NotNull @Positive Long farmId,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal quantityKg,
                             LocalDate harvestDate,
                             LocalDate packagingDate,
                             LocalDate qualityCheckedAt,
                             @Size(max = 64) String qualityResult,
                             Integer qualityScore,
                             @Size(max = 2000) String qualityNote,
                             LocalDate transportDate,
                             @Size(max = 2000) String transportInfo,
                             @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 1024) String> evidenceUrls) {
        TraceabilityManagementService.BatchWrite toServiceRequest() {
            return new TraceabilityManagementService.BatchWrite(productId, farmId, quantityKg, harvestDate,
                    packagingDate, qualityCheckedAt, qualityResult, qualityScore, qualityNote,
                    transportDate, transportInfo, evidenceUrls);
        }
    }
}
