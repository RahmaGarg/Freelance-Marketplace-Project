package com.example.demo.config;


import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AzureBlobStorageConfig {

    @Value("${azure.storage.connection-string}")
    private String connectionString;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Bean
    public BlobServiceClient blobServiceClient() {
        log.info("🔧 Configuration du client Azure Blob Storage");
        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
    }

    @Bean
    public BlobContainerClient blobContainerClient(BlobServiceClient blobServiceClient) {
        BlobContainerClient containerClient = blobServiceClient
                .getBlobContainerClient(containerName);
        
        // Créer le conteneur s'il n'existe pas
        if (!containerClient.exists()) {
            containerClient.create();
            log.info("✅ Conteneur Azure créé: {}", containerName);
        } else {
            log.info("✅ Conteneur Azure existe déjà: {}", containerName);
        }
        
        return containerClient;
    }
}