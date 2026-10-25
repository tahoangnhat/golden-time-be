package com.goldentime.api.map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/map")
public class OsmNearbyStoreController {
    private final OsmNearbyStoreService nearbyStoreService;

    public OsmNearbyStoreController(OsmNearbyStoreService nearbyStoreService) {
        this.nearbyStoreService = nearbyStoreService;
    }

    @GetMapping("/nearby-chain-stores")
    public List<OsmNearbyStoreService.NearbyStore> nearbyChainStores(@RequestParam double lat, @RequestParam double lon) {
        return nearbyStoreService.nearby(lat, lon);
    }
}
