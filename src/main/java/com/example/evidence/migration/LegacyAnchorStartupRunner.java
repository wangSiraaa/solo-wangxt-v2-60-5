package com.example.evidence.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Optionally anchors all legacy plans once the application starts
 * (evidence.anchor-on-startup=true). The operation is idempotent, so enabling
 * it permanently is safe across restarts.
 */
@Component
public class LegacyAnchorStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyAnchorStartupRunner.class);

    private final LegacyAnchorService anchorService;

    @Value("${evidence.anchor-on-startup:false}")
    private boolean anchorOnStartup;

    public LegacyAnchorStartupRunner(LegacyAnchorService anchorService) {
        this.anchorService = anchorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!anchorOnStartup) {
            return;
        }
        LegacyAnchorService.AnchorResult result = anchorService.anchorAll();
        log.info("Legacy anchor on startup: total={}, anchored={}, skipped={}, failed={}",
                result.totalLegacy(), result.anchored(), result.skipped(), result.failed());
        result.items().stream()
                .filter(i -> i.status().equals("FAILED"))
                .forEach(i -> log.warn("Legacy anchor failed for legacyId={}: {}", i.legacyId(), i.detail()));
    }
}
