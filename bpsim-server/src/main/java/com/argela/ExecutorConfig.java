package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;

@ApplicationScoped
public class ExecutorConfig {

    @Produces
    @ApplicationScoped
    public ManagedExecutor managedExecutor() {
        return ManagedExecutor.builder()
                .propagated(ThreadContext.CDI, ThreadContext.APPLICATION)
                .build();
    }
}
