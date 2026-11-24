package com.choruskube.core;

import java.util.List;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Removes a closing test context's mock and spy beans from Mockito's inline-mock registry.
 *
 * <p>That registry is static and holds each mock's settings strongly; a spy's settings hold the
 * real bean, which holds its bean factory. Without this, every context the cache evicts stays on
 * the heap until the test JVM exits, however small {@code spring.test.context.cache.maxSize} is.
 */
public class ReleaseInlineMocksContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return ReleaseInlineMocksOnClose.INSTANCE;
    }

    /** One shared instance, so every context's cache key carries the same customizer. */
    private enum ReleaseInlineMocksOnClose implements ContextCustomizer {
        INSTANCE;

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            ApplicationListener<ContextClosedEvent> release = event -> {
                if (event.getApplicationContext() == context) {
                    releaseMocks(context.getBeanFactory());
                }
            };
            context.addApplicationListener(release);
        }

        private static void releaseMocks(ConfigurableListableBeanFactory beanFactory) {
            for (String name : beanFactory.getSingletonNames()) {
                Object target = singletonTarget(beanFactory, name);
                if (target != null && Mockito.mockingDetails(target).isMock()) {
                    Mockito.framework().clearInlineMock(target);
                }
            }
        }

        /**
         * The object a singleton bean resolves to, unwrapped through AOP proxies whose target is
         * fixed: a spied {@code @Transactional} bean is exposed as a proxy around the spy, and
         * the registry is keyed by the spy itself.
         */
        private static Object singletonTarget(ConfigurableListableBeanFactory beanFactory, String name) {
            try {
                Object bean = beanFactory.getSingleton(name);
                if (bean instanceof FactoryBean<?> factory && factory.isSingleton()) {
                    bean = beanFactory.getBean(name);
                }
                while (bean instanceof Advised advised
                        && advised.getTargetSource() instanceof SingletonTargetSource source) {
                    bean = source.getTarget();
                }
                return bean;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
