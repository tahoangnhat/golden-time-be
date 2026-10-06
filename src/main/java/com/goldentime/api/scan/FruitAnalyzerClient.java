package com.goldentime.api.scan;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class FruitAnalyzerClient {
    private final String serviceUrl;
    private final String apiKey;
    private final RestClient restClient;

    public FruitAnalyzerClient(
            @Value("${app.ai.service-url:}") String serviceUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            RestClient.Builder restClientBuilder) {
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.restClient = restClientBuilder.build();
    }

    public AnalysisResult analyze(byte[] image, String filename, String contentType) {
        if (serviceUrl.isBlank()) {
            throw ApiException.unavailable("Chưa cấu hình dịch vụ phân tích ảnh.");
        }
        try {
            ByteArrayResource file = new ByteArrayResource(image) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(contentType));
            fileHeaders.setContentDispositionFormData("file", filename);
            body.add("file", new HttpEntity<>(file, fileHeaders));
            FruitAnalysis analysis = restClient.post()
                    .uri(serviceUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(headers -> {
                        if (!apiKey.isBlank()) headers.setBearerAuth(apiKey);
                    })
                    .body(body)
                    .retrieve()
                    .body(FruitAnalysis.class);
            if (analysis == null) throwProviderResponse();
            return new AnalysisResult(analysis, "PROVIDER");
        } catch (RestClientException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Dịch vụ AI chưa trả được kết quả. Vui lòng thử lại sau.");
        }
    }

    private AnalysisResult throwProviderResponse() {
        throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Dịch vụ AI trả về dữ liệu rỗng hoặc sai định dạng.");
    }

    public record AnalysisResult(FruitAnalysis analysis, String mode) {}
}
