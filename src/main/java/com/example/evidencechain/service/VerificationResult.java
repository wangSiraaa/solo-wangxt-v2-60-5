package com.example.evidencechain.service;

import java.util.List;

/**
 * Outcome of verifying one plan's evidence chain.
 *
 * @param valid           true when no problem was found
 * @param firstBrokenSeq  seq of the first invalid event (the "first break
 *                        point"); null when valid or when the chain is empty
 * @param reason          machine-readable code of the first problem
 * @param detail          human-readable explanation
 * @param eventCount      number of evidence events checked
 * @param expectedTailStatus status the last event transitions to
 */
public record VerificationResult(
        boolean valid,
        Integer firstBrokenSeq,
        String reason,
        String detail,
        int eventCount,
        String expectedTailStatus,
        List<CheckResult> checks
) {
    public record CheckResult(int seq, String check, boolean ok, String detail) {
    }

    public static VerificationResult ok(int eventCount, String tailStatus, List<CheckResult> checks) {
        return new VerificationResult(true, null, null, null, eventCount, tailStatus, checks);
    }

    public static VerificationResult broken(Integer firstBrokenSeq, String reason,
                                            String detail, int eventCount,
                                            String tailStatus, List<CheckResult> checks) {
        return new VerificationResult(false, firstBrokenSeq, reason, detail,
                eventCount, tailStatus, checks);
    }
}
