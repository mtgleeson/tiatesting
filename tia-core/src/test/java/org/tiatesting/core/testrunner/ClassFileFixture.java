package org.tiatesting.core.testrunner;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes minimal synthetic {@code .class} files into a directory, so classifier tests control the
 * exact access flags, hierarchy and annotations of each class without compiling fixture sources.
 * Also lets a test craft shapes javac cannot produce, such as an interface hierarchy cycle.
 */
final class ClassFileFixture {

    private final Path dir;
    private final String internalName;
    private int access = Opcodes.ACC_PUBLIC;
    private String superName = "java/lang/Object";
    private final List<String> interfaces = new ArrayList<>();
    private final List<String> classAnnotations = new ArrayList<>();
    private final List<String[]> methods = new ArrayList<>();

    /**
     * Start describing one class to be written under a directory.
     *
     * @param dir the test-classes directory to write into
     * @param binaryName the class's binary name, e.g. {@code com.example.FooTest}
     */
    private ClassFileFixture(final Path dir, final String binaryName) {
        this.dir = dir;
        this.internalName = binaryName.replace('.', '/');
    }

    /**
     * Start describing a public, concrete class extending {@code Object}.
     *
     * @param dir the test-classes directory to write into
     * @param binaryName the class's binary name, e.g. {@code com.example.FooTest}
     * @return the builder
     */
    static ClassFileFixture classIn(final Path dir, final String binaryName) {
        return new ClassFileFixture(dir, binaryName);
    }

    /**
     * Add access flags to the class, e.g. {@link Opcodes#ACC_ABSTRACT}.
     *
     * @param flags the flags to OR into the class's access
     * @return this builder
     */
    ClassFileFixture access(final int flags) {
        this.access |= flags;
        return this;
    }

    /**
     * Set the class's superclass.
     *
     * @param binaryName the superclass binary name
     * @return this builder
     */
    ClassFileFixture extending(final String binaryName) {
        this.superName = binaryName.replace('.', '/');
        return this;
    }

    /**
     * Add an interface the class implements (or, for an interface, extends).
     *
     * @param binaryName the interface binary name
     * @return this builder
     */
    ClassFileFixture implementing(final String binaryName) {
        this.interfaces.add(binaryName.replace('.', '/'));
        return this;
    }

    /**
     * Add a runtime-visible class-level annotation.
     *
     * @param annotationBinaryName the annotation type's binary name
     * @return this builder
     */
    ClassFileFixture annotatedWith(final String annotationBinaryName) {
        this.classAnnotations.add(descriptor(annotationBinaryName));
        return this;
    }

    /**
     * Add a public no-arg void method, optionally carrying one runtime-visible annotation. On an
     * interface the method is written abstract.
     *
     * @param name the method name
     * @param annotationBinaryName the annotation type's binary name, or null for none
     * @return this builder
     */
    ClassFileFixture method(final String name, final String annotationBinaryName) {
        this.methods.add(new String[]{name,
                annotationBinaryName == null ? null : descriptor(annotationBinaryName)});
        return this;
    }

    /**
     * Write the described class to {@code <dir>/<internalName>.class}, creating package
     * directories as needed.
     *
     * @throws IOException if the file cannot be written
     */
    void write() throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, access, internalName, null, superName,
                interfaces.toArray(new String[0]));
        for (String annotation : classAnnotations) {
            writer.visitAnnotation(annotation, true).visitEnd();
        }
        for (String[] method : methods) {
            int methodAccess = (access & Opcodes.ACC_INTERFACE) != 0
                    ? Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC;
            MethodVisitor visitor = writer.visitMethod(methodAccess, method[0], "()V", null, null);
            if (method[1] != null) {
                visitor.visitAnnotation(method[1], true).visitEnd();
            }
            visitor.visitEnd();
        }
        writer.visitEnd();

        Path file = dir.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }

    /**
     * Convert a binary class name into a field descriptor.
     *
     * @param binaryName e.g. {@code org.junit.jupiter.api.Test}
     * @return e.g. {@code Lorg/junit/jupiter/api/Test;}
     */
    private static String descriptor(final String binaryName) {
        return "L" + binaryName.replace('.', '/') + ";";
    }
}
