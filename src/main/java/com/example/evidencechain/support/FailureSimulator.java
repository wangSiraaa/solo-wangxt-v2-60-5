package com.example.evidencechain.support;

import org.springframework.stereotype.Component;

/**
 * Test-only fault injection point. When a hook is registered for the current
 * thread, it runs at the end of a command transaction (after the business
 * row and evidence event have been written, before commit), allowing tests
 * to force a rollback and assert that no half-finished record survives.
 */
@Component
public class FailureSimulator {

    private static final ThreadLocal<Runnable> HOOK = new ThreadLocal<>();

    public void install(Runnable hook) {
        HOOK.set(hook);
    }

    public void clear() {
        HOOK.remove();
    }

    /** Invoked by EvidenceService right before the transaction commits. */
    public void faultPoint() {
        Runnable hook = HOOK.get();
        if (hook != null) {
            hook.run();
        }
    }
}
