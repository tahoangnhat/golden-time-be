package com.goldentime.api.business;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/business/applications")
public class BusinessApplicationController {
    private final BusinessApplicationService applications;

    public BusinessApplicationController(BusinessApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessApplicationService.ApplicationReceipt submit(
            @RequestParam("businessName") String businessName,
            @RequestParam(value = "registrationNumber", required = false) String registrationNumber,
            @RequestParam(value = "taxCode", required = false) String taxCode,
            @RequestParam("contactName") String contactName,
            @RequestParam(value = "representativePhone", required = false) String representativePhone,
            @RequestParam("email") String email,
            @RequestParam("phone") String phone,
            @RequestParam("address") String address,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @RequestParam(value = "googlePlaceId", required = false) String googlePlaceId,
            @RequestParam("password") String password,
            @RequestParam(value = "licenseFile", required = false) MultipartFile licenseFile,
            @RequestParam(value = "logoFile", required = false) MultipartFile logoFile) {
        String resolvedTaxCode = taxCode == null || taxCode.isBlank() ? registrationNumber : taxCode;
        return applications.submit(businessName, resolvedTaxCode, contactName, email,
                phone, address, latitude, longitude, googlePlaceId, password, representativePhone,
                licenseFile, logoFile);
    }
}
