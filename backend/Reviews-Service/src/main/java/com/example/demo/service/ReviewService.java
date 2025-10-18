package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.dtos.CanReviewResponse;
import com.example.demo.dtos.CreateReviewRequest;
import com.example.demo.dtos.ProductReviewStats;
import com.example.demo.dtos.ReviewResponse;
import com.example.demo.dtos.UpdateReviewRequest;
import com.example.demo.entities.Review;
import com.example.demo.exceptions.ReviewAlreadyExistsException;
import com.example.demo.exceptions.ReviewNotFoundException;
import com.example.demo.exceptions.UnauthorizedException;
import com.example.demo.repositories.ReviewRepository;

import java.io.IOException;
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {
    
    private final ReviewRepository reviewRepository;
    private final AzureBlobStorageService azureBlobStorageService;
    
    @Value("${azure.storage.generate-sas-on-read:true}")
    private boolean generateSasOnRead;
    
    @Transactional
    public ReviewResponse createReview(CreateReviewRequest request) {
        log.info("Création d'un avis pour le produit {} par le client {}", 
                 request.getProductId(), request.getClientId());
        
        validateReviewCreation(request.getClientId(), request.getProductId());
        
        Review review = Review.builder()
                .clientId(request.getClientId())
                .productId(request.getProductId())
                .orderId(request.getOrderId())
                .rating(request.getRating())
                .comment(request.getComment())
                .verified(true)
                .active(true)
                .build();
        
        review = reviewRepository.save(review);
        log.info("Avis créé avec succès: {}", review.getId());
        
        return mapToResponse(review);
    }
    
    @Transactional
    public ReviewResponse createReviewWithImage(CreateReviewRequest request, 
                                               MultipartFile image) throws IOException {
        log.info("Création d'un avis avec image pour le produit {} par le client {}", 
                 request.getProductId(), request.getClientId());
        
        validateReviewCreation(request.getClientId(), request.getProductId());
        
        Review review = Review.builder()
                .clientId(request.getClientId())
                .productId(request.getProductId())
                .orderId(request.getOrderId())
                .rating(request.getRating())
                .comment(request.getComment())
                .verified(true)
                .active(true)
                .build();
        
        // Sauvegarder l'image dans Azure si présente
        if (image != null && !image.isEmpty()) {
            String imageUrl = azureBlobStorageService.storeFile(image);
            review.setImagePath(imageUrl); // Stocker l'URL complète
        }
        
        review = reviewRepository.save(review);
        log.info("Avis créé avec succès avec image: {}", review.getId());
        
        return mapToResponse(review);
    }
    
    @Transactional
    public ReviewResponse uploadImage(Long reviewId, Long clientId, 
                                     MultipartFile file) throws IOException {
        log.info("Upload d'image pour l'avis {} par le client {}", reviewId, clientId);
        
        Review review = getReviewAndValidateOwnership(reviewId, clientId);
        
        // Supprimer l'ancienne image d'Azure si elle existe
        if (review.getImagePath() != null) {
            azureBlobStorageService.deleteFile(review.getImagePath());
        }
        
        // Sauvegarder la nouvelle image dans Azure
        String imageUrl = azureBlobStorageService.storeFile(file);
        review.setImagePath(imageUrl);
        review = reviewRepository.save(review);
        
        log.info("Image uploadée avec succès pour l'avis {}: {}", reviewId, imageUrl);
        return mapToResponse(review);
    }
    
    @Transactional
    public void deleteImage(Long reviewId, Long clientId) {
        log.info("Suppression de l'image de l'avis {} par le client {}", reviewId, clientId);
        
        Review review = getReviewAndValidateOwnership(reviewId, clientId);
        
        if (review.getImagePath() != null) {
            azureBlobStorageService.deleteFile(review.getImagePath());
            review.setImagePath(null);
            reviewRepository.save(review);
            log.info("Image supprimée avec succès pour l'avis {}", reviewId);
        }
    }
    
    @Transactional
    public ReviewResponse updateReview(Long reviewId, Long clientId, 
                                      UpdateReviewRequest request) {
        log.info("Mise à jour de l'avis {} par le client {}", reviewId, clientId);
        
        Review review = getReviewAndValidateOwnership(reviewId, clientId);
        
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        
        review = reviewRepository.save(review);
        log.info("Avis mis à jour avec succès: {}", reviewId);
        
        return mapToResponse(review);
    }
    
    @Transactional
    public void deleteReview(Long reviewId, Long clientId) {
        log.info("Suppression de l'avis {} par le client {}", reviewId, clientId);
        
        Review review = getReviewAndValidateOwnership(reviewId, clientId);
        
        // Supprimer l'image associée d'Azure
        if (review.getImagePath() != null) {
            azureBlobStorageService.deleteFile(review.getImagePath());
        }
        
        // Soft delete
        review.setActive(false);
        reviewRepository.save(review);
        
        log.info("Avis supprimé avec succès: {}", reviewId);
    }
    
    public Page<ReviewResponse> getProductReviews(Long productId, Pageable pageable) {
        log.info("Récupération des avis pour le produit {}", productId);
        return reviewRepository.findByProductIdAndActiveTrueOrderByCreatedAtDesc(productId, pageable)
                .map(this::mapToResponse);
    }
    
    public Page<ReviewResponse> getClientReviews(Long clientId, Pageable pageable) {
        log.info("Récupération des avis du client {}", clientId);
        return reviewRepository.findByClientIdAndActiveTrueOrderByCreatedAtDesc(clientId, pageable)
                .map(this::mapToResponse);
    }
    
    public ReviewResponse getReviewById(Long reviewId) {
        log.info("Récupération de l'avis {}", reviewId);
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException("Avis non trouvé"));
        return mapToResponse(review);
    }
    
    public ProductReviewStats getProductStats(Long productId) {
        log.info("Calcul des statistiques pour le produit {}", productId);
        
        Long totalReviews = reviewRepository.countByProductIdAndActiveTrue(productId);
        Double averageRating = reviewRepository.getAverageRatingByProductId(productId);
        
        return ProductReviewStats.builder()
                .productId(productId)
                .totalReviews(totalReviews)
                .averageRating(averageRating != null ? averageRating : 0.0)
                .fiveStars(reviewRepository.countByProductIdAndRatingAndActiveTrue(productId, 5))
                .fourStars(reviewRepository.countByProductIdAndRatingAndActiveTrue(productId, 4))
                .threeStars(reviewRepository.countByProductIdAndRatingAndActiveTrue(productId, 3))
                .twoStars(reviewRepository.countByProductIdAndRatingAndActiveTrue(productId, 2))
                .oneStar(reviewRepository.countByProductIdAndRatingAndActiveTrue(productId, 1))
                .build();
    }
    
    public CanReviewResponse canClientReview(Long clientId, Long productId, Long orderId) {
        log.info("Vérification si le client {} peut laisser un avis pour le produit {}", 
                 clientId, productId);
        
        boolean hasExistingReview = reviewRepository.existsByClientIdAndProductIdAndActiveTrue(
                clientId, productId);
        
        if (hasExistingReview) {
            return CanReviewResponse.builder()
                    .canReview(false)
                    .reason("Vous avez déjà laissé un avis pour ce produit")
                    .hasExistingReview(true)
                    .hasPurchased(true)
                    .build();
        }
        
        boolean hasPurchased = orderId != null;
        
        return CanReviewResponse.builder()
                .canReview(hasPurchased)
                .reason(hasPurchased ? "Vous pouvez laisser un avis" : 
                       "Vous devez avoir acheté ce produit pour laisser un avis")
                .hasExistingReview(false)
                .hasPurchased(hasPurchased)
                .build();
    }
    
    // ========== Méthodes utilitaires privées ==========
    
    private void validateReviewCreation(Long clientId, Long productId) {
        if (reviewRepository.existsByClientIdAndProductIdAndActiveTrue(clientId, productId)) {
            throw new ReviewAlreadyExistsException(
                "Vous avez déjà laissé un avis pour ce produit");
        }
    }
    
    private Review getReviewAndValidateOwnership(Long reviewId, Long clientId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException("Avis non trouvé"));
        
        if (!review.getClientId().equals(clientId)) {
            throw new UnauthorizedException(
                "Vous n'êtes pas autorisé à modifier cet avis");
        }
        
        return review;
    }
    
    private ReviewResponse mapToResponse(Review review) {
        // Générer une URL SAS pour l'image si elle existe
        String imageUrl = null;
        if (review.getImagePath() != null && !review.getImagePath().isEmpty()) {
            imageUrl = azureBlobStorageService.generateSasUrl(review.getImagePath());
        }
        
        return ReviewResponse.builder()
                .id(review.getId())
                .clientId(review.getClientId())
                .productId(review.getProductId())
                .orderId(review.getOrderId())
                .rating(review.getRating())
                .comment(review.getComment())
                .imageUrl(imageUrl) // URL avec SAS token pour accès temporaire
                .verified(review.getVerified())
                .createdAt(review.getCreatedAt())
                .build();
    }
}