package com.example.ecommercemcp;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class PolicyRagTool {

    private final EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;

    @PostConstruct
    public void initStore() {
        embeddingStore = new InMemoryEmbeddingStore<>();

        List<String> policyRows = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("policies/order_policies.txt");
            InputStream inputStream = resource.getInputStream();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isBlank())
                        .forEach(policyRows::add);
            }
            log.info("Loaded {} policy lines from classpath", policyRows.size());
        } catch (Exception ex) {
            log.warn("Policy file not found/readable, using defaults: {}", ex.getMessage());
            policyRows.add("PROCESSING: Orders in processing can be cancelled within 30 minutes if payment is settled.");
            policyRows.add("SHIPPED: Shipped orders cannot be cancelled; offer return label after delivery.");
            policyRows.add("DELIVERED: Delivered orders are eligible for return within 14 days.");
            policyRows.add("PAYMENT_FAILED: Ask customer to retry payment or use another payment method.");
            policyRows.add("CANCELLED: Cancelled orders can be re-created as a new order only.");
        }

        for (String policy : policyRows) {
            TextSegment segment = TextSegment.from(policy);
            embeddingStore.add(embeddingModel.embed(segment).content(), segment);
        }
    }

    public String resolvePolicyForStatus(String status) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed("Policy for status " + status).content())
                .maxResults(1)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        EmbeddingMatch<TextSegment> match = result.matches().stream().findFirst().orElse(null);
        if (match == null || match.embedded() == null) {
            return "No matching policy found. Escalate to support.";
        }
        return match.embedded().text();
    }

    @PreDestroy
    public void clearStore() {
        embeddingStore = new InMemoryEmbeddingStore<>();
        log.info("PolicyRagTool embedding store cleared");
    }
}
