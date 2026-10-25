package com.goldentime.api.map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/map/geocoding")
public class OsmGeocodingController {
    private final OsmGeocodingService geocodingService;

    public OsmGeocodingController(OsmGeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @GetMapping("/search")
    public JsonNode search(@RequestParam("q") String query) {
        return geocodingService.search(query);
    }

    @GetMapping("/reverse")
    public JsonNode reverse(@RequestParam double lat, @RequestParam double lon) {
        return geocodingService.reverse(lat, lon);
    }
}
