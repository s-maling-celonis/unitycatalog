package io.unitycatalog.docker.tests.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Minimal parser for KEY=value lines in a dotenv file (no export/source semantics). */
final class EnvFileSupport {

  private EnvFileSupport() {}

  static Optional<String> readVariable(Path envFile, String key) throws IOException {
    if (!Files.isRegularFile(envFile)) {
      return Optional.empty();
    }
    for (String rawLine : Files.readAllLines(envFile)) {
      String line = rawLine.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = line.substring(0, eq).strip();
      if (!name.equals(key)) {
        continue;
      }
      String value = unquote(line.substring(eq + 1).strip());
      return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
    return Optional.empty();
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
