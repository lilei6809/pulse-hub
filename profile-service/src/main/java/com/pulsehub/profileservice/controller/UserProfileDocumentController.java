package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.document.UserProfileDocument;
import com.pulsehub.profileservice.service.UserProfileDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Slf4j
@RequestMapping("/api/v1/profile_documents")
@RestController
@RequiredArgsConstructor
public class UserProfileDocumentController {

    @Autowired
    private UserProfileDocumentService userProfileDocumentService;

    @PostMapping("/{userId}")
    public ResponseEntity<UserProfileDocument> saveOrUpdateUserProfileDocument(@PathVariable String userId) {
        log.debug("准备开始操作 {}的 document", userId);

        Optional<UserProfileDocument> document = userProfileDocumentService.createOrUpdateDocument(userId);

        if (document.isPresent()) {
            return ResponseEntity.ok().body(document.get());
        } else  {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileDocument> getUserProfileDocument(@PathVariable String userId) {
        log.debug("准备查询 {}的 document", userId);

        Optional<UserProfileDocument> document = userProfileDocumentService.getActiveDocument(userId);

        if (document.isPresent()) {
            return ResponseEntity.ok().body(document.get());
        } else  {
            return ResponseEntity.notFound().build();
        }
    }

}
