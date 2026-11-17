package dev.indyproto.gen;

import com.google.protobuf.ByteString;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.lang.constant.ClassDesc;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * protoc plugin entry point ({@code protoc-gen-indy}): reads a {@code CodeGeneratorRequest} on
 * stdin and answers with one {@code .class} file per generated class.
 *
 * <pre>
 * protoc --plugin=protoc-gen-indy=path/to/protoc-gen-indy --indy_out=build/classes foo.proto
 * protoc --plugin=... --indy_out=build/foo-indy.jar foo.proto           # protoc zips it itself
 * protoc --plugin=... --indy_opt=rewrite=foo=indy.foo --indy_out=... foo.proto
 * </pre>
 *
 * <p>Classes are written as file content bytes. protoc copies them through unchanged, to a
 * directory or (for an {@code _out} ending in {@code .jar}/{@code .zip}) into the archive.
 *
 * <p>Options ({@code --indy_opt}, comma separated): {@code rewrite=FROM=TO} moves Java package
 * {@code FROM} (and its subpackages) to {@code TO}; repeatable.
 */
public final class Plugin {
  private Plugin() {}

  public static void main(String[] args) throws Exception {
    CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);
    CodeGeneratorResponse response = run(request);
    try (OutputStream out = new BufferedOutputStream(System.out)) {
      response.writeTo(out);
    }
  }

  /** Never throws for bad input: problems go in {@code error}, which protoc prints and fails on. */
  public static CodeGeneratorResponse run(CodeGeneratorRequest request) {
    CodeGeneratorResponse.Builder response = CodeGeneratorResponse.newBuilder()
        .setSupportedFeatures(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL_VALUE);
    try {
      Map<String, String> rewrites = parseOptions(request.getParameter());
      Map<ClassDesc, byte[]> classes =
          Gen.generate(request.getProtoFileList(), rewrites, request.getFileToGenerateList());
      for (var e : classes.entrySet()) {
        response.addFile(CodeGeneratorResponse.File.newBuilder()
            .setName(Names.internal(e.getKey()) + ".class")
            .setContentBytes(ByteString.copyFrom(e.getValue())));
      }
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      response.clearFile().setError(e.getMessage());
    } catch (Exception e) {
      response.clearFile().setError("protoc-gen-indy failed: " + e);
    }
    return response.build();
  }

  static Map<String, String> parseOptions(String parameter) {
    Map<String, String> rewrites = new LinkedHashMap<>();
    if (parameter.isBlank()) return rewrites;
    for (String opt : parameter.split(",")) {
      String o = opt.strip();
      if (o.isEmpty()) continue;
      String[] kv = o.split("=", 3);
      if (kv[0].equals("rewrite") && kv.length == 3 && !kv[1].isEmpty() && !kv[2].isEmpty()) {
        rewrites.put(kv[1], kv[2]);
      } else {
        throw new IllegalArgumentException("unknown --indy_opt '" + o + "' (expected rewrite=FROM=TO)");
      }
    }
    return rewrites;
  }
}
