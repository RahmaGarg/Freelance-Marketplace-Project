package com.example.demo.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.dtos.CanReviewResponse;
import com.example.demo.dtos.CreateReviewRequest;
import com.example.demo.dtos.ProductReviewStats;
import com.example.demo.dtos.ReviewResponse;
import com.example.demo.dtos.UpdateReviewRequest;
import com.example.demo.exceptions.ErrorResponse;
import com.example.demo.exceptions.InvalidFileException;
import com.example.demo.service.AzureBlobStorageService;
import com.example.demo.service.ReviewService;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;


@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {
    
    private final ReviewService reviewService;
    private final AzureBlobStorageService azureBlobStorageService;
    
    /**
     * Créer un avis SANS image
     */
    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            @Valid @RequestBody CreateReviewRequest request) {
        ReviewResponse response = reviewService.createReview(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * Créer un avis AVEC image en une seule requête
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewResponse> createReviewWithImage(
            @RequestParam("clientId") Long clientId,
            @RequestParam("productId") Long productId,
            @RequestParam("orderId") Long orderId,
            @RequestParam("rating") Integer rating,
            @RequestParam(value = "comment", required = false) String comment,
            @RequestParam(value = "image", required = false) MultipartFile image) throws IOException {
        
        CreateReviewRequest request = CreateReviewRequest.builder()
                .clientId(clientId)
                .productId(productId)
                .orderId(orderId)
                .rating(rating)
                .comment(comment)
                .build();
        
        ReviewResponse response = reviewService.createReviewWithImage(request, image);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * Ajouter ou modifier l'image d'un avis existant
     */
    @PostMapping("/{reviewId}/image")
    public ResponseEntity<ReviewResponse> uploadImage(
            @PathVariable Long reviewId,
            @RequestParam Long clientId,
            @RequestParam("file") MultipartFile file) throws IOException {
        
        ReviewResponse response = reviewService.uploadImage(reviewId, clientId, file);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Supprimer l'image d'un avis
     */
    @DeleteMapping("/{reviewId}/image")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long reviewId,
            @RequestParam Long clientId) {
        reviewService.deleteImage(reviewId, clientId);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Note: Plus besoin d'endpoint pour servir les images car Azure fournit les URLs directement
     * Les images sont accessibles via l'URL Azure stockée dans imageUrl
     */
    
    @PutMapping("/{reviewId}")
    public ResponseEntity<ReviewResponse> updateReview(
            @PathVariable Long reviewId,
            @RequestParam Long clientId,
            @Valid @RequestBody UpdateReviewRequest request) {
        ReviewResponse response = reviewService.updateReview(reviewId, clientId, request);
        return ResponseEntity.ok(response);
    }
    
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> deleteReview(
            @PathVariable Long reviewId,
            @RequestParam Long clientId) {
        reviewService.deleteReview(reviewId, clientId);
        return ResponseEntity.noContent().build();
    }
    
    @GetMapping("/{reviewId}")
    public ResponseEntity<ReviewResponse> getReviewById(@PathVariable Long reviewId) {
        ReviewResponse response = reviewService.getReviewById(reviewId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/product/{productId}")
    public ResponseEntity<Page<ReviewResponse>> getProductReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewResponse> reviews = reviewService.getProductReviews(productId, pageable);
        return ResponseEntity.ok(reviews);
    }
    
    @GetMapping("/client/{clientId}")
    public ResponseEntity<Page<ReviewResponse>> getClientReviews(
            @PathVariable Long clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewResponse> reviews = reviewService.getClientReviews(clientId, pageable);
        return ResponseEntity.ok(reviews);
    }
    
    @GetMapping("/product/{productId}/stats")
    public ResponseEntity<ProductReviewStats> getProductStats(@PathVariable Long productId) {
        ProductReviewStats stats = reviewService.getProductStats(productId);
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/can-review")
    public ResponseEntity<CanReviewResponse> canClientReview(
            @RequestParam Long clientId,
            @RequestParam Long productId,
            @RequestParam(required = false) Long orderId) {
        CanReviewResponse response = reviewService.canClientReview(clientId, productId, orderId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Exception handler pour les erreurs de fichiers
     */
    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFile(InvalidFileException e) {
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
