package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PomPropertyResolver}.
 */
class PomPropertyResolverTest {

    @Test
    void nestedPropertiesResolveFully() {
        // given
        PomModel model = PomModel.parse("<project><artifactId>app</artifactId><version>1.0</version><properties>"
                + "<base>2</base><lib.version>${base}.5</lib.version></properties></project>");
        PomPropertyResolver resolver = PomPropertyResolver.forProject(model, Collections.<String, String>emptyMap());

        // when
        String resolved = resolver.resolve("v${lib.version}");

        // then
        assertEquals("v2.5", resolved);
    }

    @Test
    void unknownReferencesAndCyclesAreLeftAsWritten() {
        // given
        PomModel model = PomModel.parse("<project><artifactId>app</artifactId><properties>"
                + "<a>${b}</a><b>${a}</b></properties></project>");
        PomPropertyResolver resolver = PomPropertyResolver.forProject(model, Collections.<String, String>emptyMap());

        // when
        String unknown = resolver.resolve("${env.HOME}");
        String cyclic = resolver.resolve("${a}");

        // then - the cycle stops at the depth bound instead of looping
        assertEquals("${env.HOME}", unknown);
        assertEquals(true, cyclic.equals("${a}") || cyclic.equals("${b}"));
    }

    @Test
    void projectBuiltInsFallBackToTheParent() {
        // given - groupId and version inherited from the parent
        PomModel model = PomModel.parse("<project><parent><groupId>com.acme</groupId><artifactId>p</artifactId>"
                + "<version>3.1</version></parent><artifactId>app</artifactId></project>");
        PomPropertyResolver resolver = PomPropertyResolver.forProject(model, Collections.<String, String>emptyMap());

        // when
        String coordinates = resolver.resolve("${project.groupId}:${project.artifactId}:${project.version}");

        // then
        assertEquals("com.acme:app:3.1", coordinates);
    }

    @Test
    void profilePropertiesOverrideTheProjectProperties() {
        // given
        PomModel model = PomModel.parse("<project><artifactId>app</artifactId><properties><v>1</v></properties>"
                + "<profiles><profile><id>ci</id><properties><v>2</v></properties></profile></profiles></project>");
        PomPropertyResolver resolver = PomPropertyResolver.forProfile(model, model.getProfiles().get("ci"),
                Collections.<String, String>emptyMap());

        // when
        String resolved = resolver.resolve("${v}");

        // then
        assertEquals("2", resolved);
    }

    @Test
    void referencesListsDirectReferencesInOrder() {
        // given
        String value = "${a}-${b}-${a}";

        // when
        Set<String> references = PomPropertyResolver.references(value);

        // then
        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "b")), references);
    }
}
