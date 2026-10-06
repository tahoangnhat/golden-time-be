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
            @RequestParam("registrationNumber") String registrationNumber,
            @RequestParam("contactName") String contactName,
            @RequestParam("email") String email,
            @RequestParam("phone") String phone,
            @RequestParam("address") String address,
            @RequestParam("password") String password,
            @RequestParam("licenseFile") MultipartFile licenseFile) {
        return applications.submit(businessName, registrationNumber, contactName, email,
                phone, address, password, licenseFile);
    }
}
