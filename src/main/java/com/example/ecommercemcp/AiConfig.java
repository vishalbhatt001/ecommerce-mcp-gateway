package com.example.ecommercemcp;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public Semaphore llmSemaphore() {
        return new Semaphore(1, true);
    }

    @Bean
    public ChatLanguageModel ollamaChatModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("phi3")
                .temperature(0.0)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public EmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("nomic-embed-text")
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public OrderResolutionAssistant orderResolutionAssistant(ChatLanguageModel ollamaChatModel) {
        return AiServices.builder(OrderResolutionAssistant.class)
                .chatLanguageModel(ollamaChatModel)
                .build();
    }

    public interface OrderResolutionAssistant {

        @SystemMessage(fromResource = "prompts/order-resolution-system.st")
        String answer(String prompt);
    }
}
