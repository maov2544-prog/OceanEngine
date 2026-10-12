package com.aolandai.oceanengine.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「配置类必须被注册」防回归测试。
 *
 * <h2>为什么要写这个测试</h2>
 * <p>2026-10-12 生产启动直接失败：新加的 {@code SuixintuiRebuildProperties}（带
 * {@code @ConfigurationProperties}）<b>忘了加进</b>启动类的
 * {@code @EnableConfigurationProperties({...})}，于是容器里没有该 Bean，注入它的
 * {@code SuixintuiRebuildService} 构造失败 → {@code APPLICATION FAILED TO START}。</p>
 *
 * <p><b>261 个测试全绿也没抓到</b> —— 因为它们全是纯单元测试（手工 {@code new} 对象），
 * 没有一个加载 Spring 容器。装配类遗漏只有真启动才暴露。</p>
 *
 * <p>本测试不启动容器、不联网，仅做静态一致性检查：</p>
 * <ol>
 *   <li>扫描 {@code com.aolandai.oceanengine} 下所有带 {@code @ConfigurationProperties} 的类；</li>
 *   <li>读取 {@link ReinvestApplication} 上 {@code @EnableConfigurationProperties} 的 value；</li>
 *   <li>断言前者被后者（或 {@code @Component}/{@code @Configuration} 直接标注）<b>全覆盖</b>。</li>
 * </ol>
 *
 * <p>⚠️ 以后新增 {@code @ConfigurationProperties} 类时，若忘记注册，本测试立刻失败。</p>
 */
class ConfigurationPropertiesRegistrationTest {

    /** 与 {@code @SpringBootApplication(scanBasePackages = ...)} 保持一致 */
    private static final String BASE_PACKAGE = "com.aolandai.oceanengine";

    @Test
    @DisplayName("所有 @ConfigurationProperties 类都必须被 @EnableConfigurationProperties 注册（防启动失败）")
    void allConfigurationPropertiesAreRegistered() {
        Set<Class<?>> propertyClasses = scanConfigurationPropertiesClasses();

        assertTrue(!propertyClasses.isEmpty(),
                "扫描不到任何 @ConfigurationProperties 类 —— 扫描逻辑本身可能有问题（基准包=" + BASE_PACKAGE + "）");

        Set<Class<?>> registered = registeredViaEnableConfigurationProperties();

        Set<Class<?>> missing = propertyClasses.stream()
                .filter(c -> !registered.contains(c))
                .filter(c -> !isComponentOrConfiguration(c))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String allScan = propertyClasses.stream().map(Class::getName).sorted()
                .collect(Collectors.joining("\n    "));
        String allReg = registered.stream().map(Class::getName).sorted()
                .collect(Collectors.joining("\n    "));

        assertTrue(missing.isEmpty(),
                "以下 @ConfigurationProperties 类既没被 @EnableConfigurationProperties 注册，"
                        + "也没有 @Component/@Configuration 标注 —— 会导致启动时找不到 Bean：\n    "
                        + missing.stream().map(Class::getName).collect(Collectors.joining("\n    "))
                        + "\n\n本次扫描到的全部配置类：\n    " + allScan
                        + "\n\nReinvestApplication 当前注册的：\n    " + allReg
                        + "\n\n修复：把漏掉的类加进 ReinvestApplication 的 @EnableConfigurationProperties。");
    }

    /** 扫描基准包下所有带 {@code @ConfigurationProperties} 的类 */
    private Set<Class<?>> scanConfigurationPropertiesClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        Set<Class<?>> result = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                result.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到但加载不了的类：" + className, e);
            }
        }
        return result;
    }

    /** 读取启动类上 {@code @EnableConfigurationProperties} 声明的类 */
    private Set<Class<?>> registeredViaEnableConfigurationProperties() {
        EnableConfigurationProperties annotation =
                ReinvestApplication.class.getAnnotation(EnableConfigurationProperties.class);
        assertTrue(annotation != null,
                "ReinvestApplication 上找不到 @EnableConfigurationProperties —— 这是配置类注册的唯一入口，不能删。");
        return new LinkedHashSet<>(java.util.Arrays.asList(annotation.value()));
    }

    /** 被 {@code @Component} / {@code @Configuration} 直接标注的类，由组件扫描自动注册 */
    private boolean isComponentOrConfiguration(Class<?> type) {
        return type.isAnnotationPresent(org.springframework.stereotype.Component.class)
                || type.isAnnotationPresent(org.springframework.context.annotation.Configuration.class);
    }
}
