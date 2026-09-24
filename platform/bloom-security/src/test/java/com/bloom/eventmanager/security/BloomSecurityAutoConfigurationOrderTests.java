package com.bloom.eventmanager.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot backs its default servlet {@code SecurityFilterChain}s off with
 * {@code @ConditionalOnDefaultWebSecurity}, a missing-bean check that only sees
 * {@code bloomSecurityFilterChain} if this auto-configuration was processed first. That
 * used to hold by accident -- "com.bloom" sorts before "org.springframework" -- so the
 * order is declared, and this pins the declaration to the real set: every
 * auto-configuration on the classpath that can contribute such a chain, found by reading
 * Boot's own metadata rather than from a list that goes stale when Boot moves classes
 * (as Boot 4 did when it split security into its own modules).
 */
class BloomSecurityAutoConfigurationOrderTests {

    private static final String DEFAULT_WEB_SECURITY =
            "org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultWebSecurity";

    private final MetadataReaderFactory readers = new CachingMetadataReaderFactory();

    @Test
    void runsBeforeEveryAutoConfigurationThatContributesADefaultServletSecurityChain() {
        List<String> withDefaultChain = new ArrayList<>();
        for (String candidate : ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())) {
            if (!candidate.equals(BloomSecurityAutoConfiguration.class.getName())
                    && contributesDefaultChain(candidate, new HashSet<>())) {
                withDefaultChain.add(candidate);
            }
        }

        AutoConfiguration declared = BloomSecurityAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
        Set<String> before = new HashSet<>(Arrays.asList(declared.beforeName()));
        Arrays.stream(declared.before()).map(Class::getName).forEach(before::add);

        assertThat(withDefaultChain).as("the scan found Boot's servlet security auto-configurations").isNotEmpty();
        assertThat(before).containsAll(withDefaultChain);
    }

    /** The class, its nested configurations and whatever it {@code @Import}s, transitively. */
    private boolean contributesDefaultChain(String className, Set<String> seen) {
        if (!seen.add(className)) {
            return false;
        }
        AnnotationMetadata metadata;
        try {
            metadata = readers.getMetadataReader(className).getAnnotationMetadata();
        }
        catch (IOException ex) {
            return false; // an optional dependency that is not on this classpath
        }
        if (metadata.hasAnnotation(DEFAULT_WEB_SECURITY) || metadata.hasAnnotatedMethods(DEFAULT_WEB_SECURITY)) {
            return true;
        }
        List<String> reachable = new ArrayList<>(Arrays.asList(metadata.getMemberClassNames()));
        Map<String, Object> imports = metadata.getAnnotationAttributes(Import.class.getName(), true);
        if (imports != null) {
            reachable.addAll(Arrays.asList((String[]) imports.get("value")));
        }
        return reachable.stream().anyMatch(name -> contributesDefaultChain(name, seen));
    }
}
