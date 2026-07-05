package com.choruskube.core.controller;

import com.choruskube.core.dto.StagingRefsResponse;
import com.choruskube.core.service.StoragePrefixResolver;
import com.choruskube.core.service.UploadService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final UploadService uploadService;
    private final StoragePrefixResolver storagePrefixResolver;

    public AttachmentController(UploadService uploadService, StoragePrefixResolver storagePrefixResolver) {
        this.uploadService = uploadService;
        this.storagePrefixResolver = storagePrefixResolver;
    }

    @PostMapping("/temp")
    @PreAuthorize("@orgSecurity.canOperate()")
    public ResponseEntity<StagingRefsResponse> uploadTemp(@RequestParam("files") List<MultipartFile> files)
            throws Exception {
        String orgSlug = storagePrefixResolver.currentStoragePrefix();
        String stagingRefs = uploadService.uploadTempFiles(orgSlug, files);
        return ResponseEntity.ok(new StagingRefsResponse(stagingRefs));
    }
}
