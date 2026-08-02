package run.halo.weapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/** Guards constructor selection before a plugin jar reaches a real Halo runtime. */
class SpringComponentWiringTest {

    @Test
    void multiConstructorComponentsWithoutDefaultConstructorSelectOneForAutowiring()
        throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        for (var definition : scanner.findCandidateComponents("run.halo.weapp")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }

            Constructor<?>[] constructors = type.getDeclaredConstructors();
            boolean hasDefault = Arrays.stream(constructors)
                .anyMatch(constructor -> constructor.getParameterCount() == 0);
            if (constructors.length <= 1 || hasDefault) {
                continue;
            }

            long selected = Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
            assertEquals(1, selected,
                () -> type.getName() + " has multiple constructors and no default constructor; "
                    + "Spring requires exactly one @Autowired constructor");
        }
    }
}
