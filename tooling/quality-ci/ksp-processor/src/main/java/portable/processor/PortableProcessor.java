package portable.processor;

import com.google.devtools.ksp.processing.*;
import com.google.devtools.ksp.symbol.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only processor: deterministic Kotlin output and optional actual class output. */
public final class PortableProcessor implements SymbolProcessorProvider {
    @Override public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new SymbolProcessor() {
            private boolean emitted;
            @Override public List<KSAnnotated> process(Resolver resolver) {
                String mode = environment.getOptions().getOrDefault("portable.mode", "kotlin");
                if (mode.equals("fail")) {
                    throw new IllegalStateException("PORTABLE_KSP_PRODUCER_FAILED");
                }
                if (!emitted) {
                    emitted = true;
                    Dependencies dependencies = new Dependencies(true, new KSFile[0]);
                    try {
                        try (OutputStream output = environment.getCodeGenerator().createNewFile(
                                dependencies, "portable.generated", "GeneratedToken", "kt")) {
                            output.write(("package portable.generated\n" +
                                "data class GeneratedToken(val value: String)\n")
                                .getBytes(StandardCharsets.UTF_8));
                        }
                        if (mode.equals("binary")) {
                            try (OutputStream output = environment.getCodeGenerator().createNewFile(
                                    dependencies, "portable.generated", "BinaryToken", "class");
                                 InputStream input = PortableProcessor.class.getResourceAsStream(
                                    "/portable/generated/BinaryToken.class")) {
                                if (input == null) throw new IOException("Missing synthetic class resource");
                                input.transferTo(output);
                            }
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException("Portable processor output failure", exception);
                    }
                }
                return Collections.emptyList();
            }
            @Override public void finish() {}
            @Override public void onError() {}
        };
    }
}
