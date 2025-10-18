package com.example.demo.service;


import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.example.demo.exceptions.InvalidFileException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageService {

    private final BlobContainerClient blobContainerClient;
    
    @Value("${azure.storage.sas-token-validity-hours:24}")
    private int sasTokenValidityHours;
    
    private static final List<String> ALLOWED_EXTENSIONS = 
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    /**
     * Upload un fichier vers Azure Blob Storage et retourne l'URL avec SAS token
     */
    public String storeFile(MultipartFile file) throws IOException {
        validateFile(file);

        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        String extension = getFileExtension(originalFilename);
        String blobName = UUID.randomUUID().toString() + "." + extension;

        try {
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            
            // Définir les headers HTTP pour le blob
            BlobHttpHeaders headers = new BlobHttpHeaders()
                    .setContentType(file.getContentType());

            // Upload le fichier
            try (InputStream inputStream = file.getInputStream()) {
                blobClient.upload(inputStream, file.getSize(), true);
                blobClient.setHttpHeaders(headers);
            }

            log.info("✅ Fichier uploadé vers Azure Blob Storage: {}", blobName);
            
            // Retourner l'URL du blob (sans SAS token pour le stocker en base)
            // Le SAS token sera généré à la demande lors de la récupération
            return blobClient.getBlobUrl();
            
        } catch (BlobStorageException e) {
            log.error("❌ Erreur lors de l'upload vers Azure: {}", e.getMessage());
            throw new IOException("Erreur lors de l'upload du fichier", e);
        }
    }

    /**
     * Générer une URL avec SAS token pour accéder à un blob privé
     * Cette méthode doit être appelée chaque fois qu'on veut accéder à l'image
     */
    public String generateSasUrl(String blobUrl) {
        try {
            String blobName = extractBlobNameFromUrl(blobUrl);
            if (blobName == null || blobName.isEmpty()) {
                log.warn("⚠️ Nom de blob invalide pour génération SAS");
                return null;
            }

            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            
            // Vérifier si le blob existe
            if (!blobClient.exists()) {
                log.warn("⚠️ Le blob n'existe pas: {}", blobName);
                return null;
            }

            // Définir les permissions du SAS token (lecture seule)
            BlobSasPermission permission = new BlobSasPermission()
                    .setReadPermission(true);

            // Définir la durée de validité du token
            OffsetDateTime expiryTime = OffsetDateTime.now()
                    .plusHours(sasTokenValidityHours);

            // Créer les valeurs de signature SAS
            BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                    expiryTime, permission);

            // Générer le SAS token
            String sasToken = blobClient.generateSas(values);

            // Retourner l'URL complète avec le SAS token
            String urlWithSas = blobClient.getBlobUrl() + "?" + sasToken;
            
            log.debug("🔐 SAS URL générée pour: {}", blobName);
            return urlWithSas;
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la génération du SAS token", e);
            return null;
        }
    }

    /**
     * Générer une URL SAS avec durée personnalisée
     */
    public String generateSasUrl(String blobUrl, Duration validity) {
        try {
            String blobName = extractBlobNameFromUrl(blobUrl);
            if (blobName == null) return null;

            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            
            if (!blobClient.exists()) {
                return null;
            }

            BlobSasPermission permission = new BlobSasPermission()
                    .setReadPermission(true);

            OffsetDateTime expiryTime = OffsetDateTime.now().plus(validity);

            BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                    expiryTime, permission);

            String sasToken = blobClient.generateSas(values);
            return blobClient.getBlobUrl() + "?" + sasToken;
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la génération du SAS token personnalisé", e);
            return null;
        }
    }

    /**
     * Supprimer un fichier d'Azure Blob Storage
     */
    public void deleteFile(String blobUrl) {
        try {
            String blobName = extractBlobNameFromUrl(blobUrl);
            
            if (blobName == null || blobName.isEmpty()) {
                log.warn("⚠️ Nom de blob invalide, impossible de supprimer");
                return;
            }

            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            
            if (blobClient.exists()) {
                blobClient.delete();
                log.info("✅ Fichier supprimé d'Azure Blob Storage: {}", blobName);
            } else {
                log.warn("⚠️ Le blob n'existe pas: {}", blobName);
            }
            
        } catch (BlobStorageException e) {
            log.error("❌ Erreur lors de la suppression du blob: {}", e.getMessage());
        }
    }

    /**
     * Vérifier si un fichier existe dans Azure Blob Storage
     */
    public boolean fileExists(String blobUrl) {
        try {
            String blobName = extractBlobNameFromUrl(blobUrl);
            if (blobName == null) return false;
            
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            return blobClient.exists();
        } catch (Exception e) {
            log.error("❌ Erreur lors de la vérification de l'existence du blob", e);
            return false;
        }
    }

    // ========== Méthodes de validation ==========

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("Le fichier est vide");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidFileException("Le fichier ne doit pas dépasser 5MB");
        }

        String filename = file.getOriginalFilename();
        String extension = getFileExtension(filename);

        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new InvalidFileException(
                "Type de fichier non autorisé. Extensions acceptées: " +
                String.join(", ", ALLOWED_EXTENSIONS));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidFileException("Seules les images sont acceptées");
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new InvalidFileException("Nom de fichier invalide");
        }

        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1) {
            throw new InvalidFileException("Le fichier doit avoir une extension");
        }

        return filename.substring(lastDotIndex + 1);
    }

    /**
     * Extraire le nom du blob depuis une URL Azure complète
     * Ex: https://mystorageaccount.blob.core.windows.net/reviews/uuid.jpg -> uuid.jpg
     */
    private String extractBlobNameFromUrl(String blobUrl) {
        if (blobUrl == null || blobUrl.isEmpty()) {
            return null;
        }
        
        try {
            // Retirer le SAS token si présent
            String urlWithoutSas = blobUrl.split("\\?")[0];
            
            // L'URL Azure a le format: https://{account}.blob.core.windows.net/{container}/{blobName}
            String[] parts = urlWithoutSas.split("/");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
        } catch (Exception e) {
            log.error("❌ Erreur lors de l'extraction du nom du blob", e);
        }
        
        return null;
    }
}