package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.controller.dto.CreateDynamicUserProfileRequest;
import com.pulsehub.profileservice.controller.dto.IncPageViewsRequest;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.factory.DynamicUserProfileFactory;
import com.pulsehub.profileservice.service.DynamicProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/dynamic_profiles")
@RequiredArgsConstructor
public class DynamicUserProfileController {

    private final DynamicProfileService dynamicProfileService;
    private final DynamicUserProfileFactory profileFactory;

    /**
     * 创建或更新动态用户画像
     * 
     * 使用工厂模式简化业务逻辑，自动处理设备分类和数据验证
     * 
     * @param request 创建请求
     * @return 创建或更新后的用户画像
     */
    @PostMapping
    public ResponseEntity<DynamicUserProfile> createDynamicUserProfile(@RequestBody CreateDynamicUserProfileRequest request) {
        try {
            log.info("📝 收到动态画像创建请求: userId={}, device={}", 
                    request.getUserId(), request.getDevice());

            // 使用工厂创建画像对象
            DynamicUserProfile profile = profileFactory.createFromRequest(request);
            
            // 根据用户是否存在决定创建或更新
            DynamicUserProfile result;
            if (dynamicProfileService.profileExists(profile.getUserId())) {
                log.debug("🔄 用户画像已存在，执行更新操作: {}", profile.getUserId());
                result = dynamicProfileService.updateProfile(profile);
            } else {
                log.debug("✨ 创建新的用户画像: {}", profile.getUserId());
                result = dynamicProfileService.createProfile(profile);
            }

            log.info("✅ 动态画像操作成功: userId={}, 设备分类={}, 页面浏览={}",
                    result.getUserId(), result.getDeviceClassification(), result.getPageViewCount());

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ 请求参数无效: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
            
        } catch (Exception e) {
            log.error("❌ 创建动态画像失败: userId={}, error={}", 
                    request.getUserId(), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取动态用户画像
     * 
     * @param userId 用户ID
     * @return 用户画像信息
     */
    @GetMapping("/{userId}")
    public ResponseEntity<DynamicUserProfile> getDynamicUserProfile(@PathVariable String userId) {
        try {
            log.debug("🔍 查询动态用户画像: {}", userId);
            
            Optional<DynamicUserProfile> profile = dynamicProfileService.getProfile(userId);

            if (profile.isPresent()) {
                log.debug("✅ 用户画像查询成功: {}", userId);
                return ResponseEntity.ok(profile.get());
            } else {
                log.debug("❌ 用户画像不存在: {}", userId);
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("❌ 查询用户画像失败: userId={}, error={}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 增加用户页面浏览数
     * 
     * @param userId 用户ID
     * @param pageViews 要增加的页面浏览数
     * @return 更新后的用户画像
     */
    @PostMapping("/{userId}/{pageViews}")
    public ResponseEntity<DynamicUserProfile> increasePageViews(
            @PathVariable String userId, 
            @PathVariable String pageViews) {
        try {
            log.info("📊 增加页面浏览数: userId={}, pageViews={}", userId, pageViews);
            
            Long viewCount = Long.parseLong(pageViews);
            if (viewCount <= 0) {
                log.warn("⚠️ 页面浏览数必须大于0: {}", viewCount);
                return ResponseEntity.badRequest().build();
            }
            
            DynamicUserProfile updatedProfile = dynamicProfileService.recordPageViews(userId, viewCount);
            
            log.info("✅ 页面浏览数更新成功: userId={}, 新总数={}", 
                    userId, updatedProfile.getPageViewCount());
            
            return ResponseEntity.ok(updatedProfile);
            
        } catch (NumberFormatException e) {
            log.warn("⚠️ 页面浏览数格式无效: {}", pageViews);
            return ResponseEntity.badRequest().build();
            
        } catch (Exception e) {
            log.error("❌ 更新页面浏览数失败: userId={}, pageViews={}, error={}", 
                    userId, pageViews, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
