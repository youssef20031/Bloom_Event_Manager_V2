package com.bloom.eventmanager.retrieval.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * V1's RAG_TOP_K.
 *
 * <p>A mutable JavaBean rather than a record so {@code POST /actuator/refresh} can change
 * it on a running service. Spring Cloud's {@code ConfigurationPropertiesRebinder} rebinds
 * {@code @ConfigurationProperties} beans in place on the refresh's
 * {@code EnvironmentChangeEvent}; a record is bound once through its constructor and the
 * rebinder leaves it untouched, and a final record cannot be {@code @RefreshScope}-proxied
 * either. The rebinder first resets the bean from a default-constructed instance through
 * the getter/setter pair, so removing the key from config-repo falls back to 15 rather
 * than keeping the last value.
 *
 * <p>Two fields because the binder calls the setter <em>before</em> validation runs: with
 * one, a refresh to an out-of-range value would fail and still leave the service running
 * on it. Callers read {@link #topK()}, which is only published from
 * {@link #afterPropertiesSet()} -- reached on a rebind only once binding and validation
 * have succeeded -- so a rejected refresh keeps the last valid value in effect.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.retrieval")
public class RetrievalProperties implements InitializingBean {

    /** What the binder writes. */
    @Min(1)
    @Max(100)
    private int topK = 15;

    /** What callers read; volatile because the refresh thread writes it. */
    private volatile int validatedTopK = topK;

    /** The accessor RetrievalService reads; kept from the record so callers are unchanged. */
    public int topK() {
        return validatedTopK;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    @Override
    public void afterPropertiesSet() {
        this.validatedTopK = topK;
    }
}
