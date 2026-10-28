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
public final class ClassFileFixture {

    private final Path dir;
    private final String internalName;
    private int access = Opcodes.ACC_PUBLIC;
    private String superName = "java/lang/Object";
    private final List<String> interfaces = new ArrayList<>();
    private final List<String> classAnnotations = new ArrayList<>();
    private final List<String[]> methods = new ArrayList<>();
    private final List<String> fieldTypes = new ArrayList<>();
    private final List<String> parameterTypes = new ArrayList<>();
    private final List<String> staticCallOwners = new ArrayList<>();

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
    public static ClassFileFixture classIn(final Path dir, final String binaryName) {
        return new ClassFileFixture(dir, binaryName);
    }

    /**
     * Add access flags to the class, e.g. {@link Opcodes#ACC_ABSTRACT}.
     *
     * @param flags the flags to OR into the class's access
     * @return this builder
     */
    public ClassFileFixture access(final int flags) {
        this.access |= flags;
        return this;
    }

    /**
     * Set the class's superclass.
     *
     * @param binaryName the superclass binary name
     * @return this builder
     */
    public ClassFileFixture extending(final String binaryName) {
        this.superName = binaryName.replace('.', '/');
        return this;
    }

    /**
     * Add an interface the class implements (or, for an interface, extends).
     *
     * @param binaryName the interface binary name
     * @return this builder
     */
    public ClassFileFixture implementing(final String binaryName) {
        this.interfaces.add(binaryName.replace('.', '/'));
        return this;
    }

    /**
     * Add a runtime-visible class-level annotation.
     *
     * @param annotationBinaryName the annotation type's binary name
     * @return this builder
     */
    public ClassFileFixture annotatedWith(final String annotationBinaryName) {
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
    public ClassFileFixture method(final String name, final String annotationBinaryName) {
        this.methods.add(new String[]{name,
                annotationBinaryName == null ? null : descriptor(annotationBinaryName)});
        return this;
    }

    /**
     * Add a field of the given type. The type is named only in the field's descriptor, not in a
     * constant pool class entry.
     *
     * @param typeBinaryName the field type's binary name
     * @return this builder
     */
    public ClassFileFixture field(final String typeBinaryName) {
        this.fieldTypes.add(descriptor(typeBinaryName));
        return this;
    }

    /**
     * Add a method taking one parameter of the given type and doing nothing with it,
     * so the type is named only in the method's descriptor.
     *
     * @param typeBinaryName the parameter type's binary name
     * @return this builder
     */
    public ClassFileFixture methodTaking(final String typeBinaryName) {
        this.parameterTypes.add(descriptor(typeBinaryName));
        return this;
    }

    /**
     * Add a method whose code calls a static no-arg void method on the given class, which puts the
     * class in a constant pool class entry (as the method's owner).
     *
     * @param ownerBinaryName the called class's binary name
     * @return this builder
     */
    public ClassFileFixture callingStatic(final String ownerBinaryName) {
        this.staticCallOwners.add(ownerBinaryName.replace('.', '/'));
        return this;
    }

    /**
     * Write the described class to {@code <dir>/<internalName>.class}, creating package
     * directories as needed.
     *
     * @throws IOException if the file cannot be written
     */
    public void write() throws IOException {
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
        for (int i = 0; i < fieldTypes.size(); i++) {
            writer.visitField(Opcodes.ACC_PRIVATE, "field" + i, fieldTypes.get(i), null, null).visitEnd();
        }
        for (int i = 0; i < parameterTypes.size(); i++) {
            MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "take" + i,
                    "(" + parameterTypes.get(i) + ")V", null, null);
            visitor.visitCode();
            visitor.visitInsn(Opcodes.RETURN);
            visitor.visitMaxs(0, 2);
            visitor.visitEnd();
        }
        for (int i = 0; i < staticCallOwners.size(); i++) {
            MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "call" + i, "()V", null, null);
            visitor.visitCode();
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, staticCallOwners.get(i), "help", "()V", false);
            visitor.visitInsn(Opcodes.RETURN);
            visitor.visitMaxs(0, 1);
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
