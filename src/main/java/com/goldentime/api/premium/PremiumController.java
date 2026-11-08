package com.goldentime.api.premium;

import com.goldentime.api.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/premium")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class PremiumController {
    private final PremiumService premium;

    public PremiumController(PremiumService premium) {
        this.premium = premium;
    }

    @GetMapping("/status")
    public PremiumService.Overview status(Authentication authentication) {
        return premium.overview(CurrentUser.id(authentication));
    }

    @PostMapping("/payments")
    public PremiumService.PaymentInfo createPayment(Authentication authentication) {
        return premium.createPayment(CurrentUser.id(authentication));
    }

    @GetMapping("/payments/{orderCode}")
    public PremiumService.PaymentInfo paymentStatus(Authentication authentication, @PathVariable long orderCode) {
        return premium.paymentStatus(CurrentUser.id(authentication), orderCode);
    }

    @GetMapping("/health-profile")
    public PremiumService.HealthProfile healthProfile(Authentication authentication) {
        return premium.getHealthProfile(CurrentUser.id(authentication));
    }

    @PutMapping("/health-profile")
    public PremiumService.HealthProfile saveHealthProfile(Authentication authentication,
                                                           @RequestBody PremiumService.ProfilePayload payload) {
        return premium.saveHealthProfile(CurrentUser.id(authentication), payload);
    }

    @DeleteMapping("/health-profile")
    public void deleteHealthProfile(Authentication authentication) {
        premium.deleteHealthProfile(CurrentUser.id(authentication));
    }

    @PostMapping("/recommendations")
    public PremiumService.RecommendationResponse recommend(Authentication authentication,
                                                            @RequestBody PremiumService.RecommendationRequest request) {
        return premium.recommend(CurrentUser.id(authentication), request);
    }
}
