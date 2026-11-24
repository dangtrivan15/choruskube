package com.choruskube.core;

import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.TestContextAnnotationUtils;

/**
 * Runs the test classes that share a Spring application context back to back, each group at the
 * position its first class was discovered in. The key is the {@code MergedContextConfiguration}
 * Spring's context cache itself uses, so "same group" means "same cached context".
 *
 * <p>This is what lets {@code spring.test.context.cache.maxSize} stay small: once a group has run,
 * no later class asks for its context, so evicting it never forces a rebuild.
 */
public class ContextGroupingClassOrderer implements ClassOrderer {

    private static final Object NO_SPRING_CONTEXT = new Object();

    @Override
    public void orderClasses(ClassOrdererContext context) {
        Map<Object, Integer> groupByKey = new HashMap<>();
        Map<ClassDescriptor, Integer> groupOf = new IdentityHashMap<>();
        for (ClassDescriptor descriptor : context.getClassDescriptors()) {
            Object key = contextKey(descriptor.getTestClass());
            groupOf.put(descriptor, groupByKey.computeIfAbsent(key, k -> groupByKey.size()));
        }
        context.getClassDescriptors().sort(Comparator.comparingInt(groupOf::get));
    }

    private static Object contextKey(Class<?> testClass) {
        if (!TestContextAnnotationUtils.hasAnnotation(testClass, BootstrapWith.class)) {
            return NO_SPRING_CONTEXT;
        }
        try {
            return BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
        } catch (RuntimeException e) {
            // A class whose configuration cannot be resolved keeps a group of its own, so the same
            // error surfaces from the class itself when it runs.
            return testClass;
        }
    }
}
