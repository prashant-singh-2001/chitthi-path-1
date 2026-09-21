package com.chitthi.config;

import com.chitthi.sarvam.SarvamProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient sarvamRestClient(SarvamProperties properties) {
        // Pinned to HTTP/1.1: the JDK HttpClient's h2c upgrade attempt drops
        // streaming multipart bodies against plaintext servers (reproduced
        // against WireMock as "Received RST_STREAM: Stream cancelled").
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-subscription-key", properties.apiSubscriptionKey())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }
}
