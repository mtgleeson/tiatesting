package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.SelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover how the {@code tiaSelectAllTests} / {@code tiaReseed} runtime flags resolve: a {@code -P}
 * property wins over the extension, which wins over the default. See the "Forced runs and
 * re-seed" chapter in {@code WIKI.md}.
 */
class TiaRuntimeFlagsTest {

    /**
     * A -P property wins over the extension's setting.
     */
    @Test
    void commandLinePropertyOverridesTheExtension() {
        // given
        Project project = ProjectBuilder.builder().build();
        project.getExtensions().getExtraProperties().set("tiaReseed", "true");
        TiaBaseTaskExtension extension = new TiaBaseTaskExtension();
        extension.setReseed(false);

        // when
        SelectionMode mode = TiaRuntimeFlags.selectionMode(project, extension);

        // then
        assertEquals(SelectionMode.RESEED, mode);
    }

    /**
     * With no -P property, the extension's setting applies.
     */
    @Test
    void extensionValueIsUsedWhenNoPropertyIsSet() {
        // given
        Project project = ProjectBuilder.builder().build();
        TiaBaseTaskExtension extension = new TiaBaseTaskExtension();
        extension.setSelectAllTests(true);

        // when
        SelectionMode mode = TiaRuntimeFlags.selectionMode(project, extension);

        // then
        assertEquals(SelectionMode.SELECT_ALL, mode);
    }

    /**
     * With nothing set, the mode is ordinary selection.
     */
    @Test
    void nothingSetIsSelective() {
        // given
        Project project = ProjectBuilder.builder().build();
        TiaBaseTaskExtension extension = new TiaBaseTaskExtension();

        // when
        SelectionMode mode = TiaRuntimeFlags.selectionMode(project, extension);

        // then
        assertEquals(SelectionMode.SELECTIVE, mode);
    }
}
