package com.sheetsight.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Corrects alphaTab 1.6.1's documented ForceNone behavior without shipping a
 * forked binary. That release lets ForceNone fall through to automatic
 * accidental calculation, which can produce an unwanted natural glyph.
 */
public abstract class AlphaTabForceNoneClassVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String TARGET_CLASS =
            "alphaTab.rendering.utils.AccidentalHelper$Companion";
    private static final String TARGET_METHOD = "computeAccidental";
    private static final String TARGET_DESCRIPTOR =
            "(LalphaTab/model/KeySignature;LalphaTab/model/NoteAccidentalMode;" +
                    "DZLalphaTab/model/AccidentalType;)LalphaTab/model/AccidentalType;";

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return TARGET_CLASS.equals(classData.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label continueNormally = new Label();
                        visitVarInsn(Opcodes.ALOAD, 2);
                        visitFieldInsn(
                                Opcodes.GETSTATIC,
                                "alphaTab/model/NoteAccidentalMode",
                                "ForceNone",
                                "LalphaTab/model/NoteAccidentalMode;"
                        );
                        visitJumpInsn(Opcodes.IF_ACMPNE, continueNormally);
                        visitFieldInsn(
                                Opcodes.GETSTATIC,
                                "alphaTab/model/AccidentalType",
                                "None",
                                "LalphaTab/model/AccidentalType;"
                        );
                        visitInsn(Opcodes.ARETURN);
                        visitLabel(continueNormally);
                    }
                };
            }
        };
    }
}
